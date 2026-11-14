package com.rummikub.core.network

import com.rummikub.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Central game networking repository providing "Zéro Rupture" resilience.
 * Coordinates automatic transparent reconnection with Exponential Backoff,
 * Heartbeat monitoring, and authoritative state synchronization.
 */
class GameNetworkRepository(
    private val serverUrl: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val webSocketClient: RummikubWebSocketClient = RummikubWebSocketClient(scope)
) {
    private val backoff = ExponentialBackoff()
    private val localActionQueue = ConcurrentLinkedQueue<NetworkPacket>()

    /** How long we keep trying the very first connection (free-tier servers can take ~1 min to wake up). */
    private val initialConnectBudgetMs = 100_000L

    // ==========================================
    // EXPOSED STATE FLOWS
    // ==========================================

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _roomCode = MutableStateFlow<String?>(null)
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    private val _myPlayerId = MutableStateFlow<String?>(null)
    val myPlayerId: StateFlow<String?> = _myPlayerId.asStateFlow()

    private val _sessionToken = MutableStateFlow<String?>(null)
    val sessionToken: StateFlow<String?> = _sessionToken.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _boardState = MutableStateFlow(BoardState())
    val boardState: StateFlow<BoardState> = _boardState.asStateFlow()

    private val _playerRack = MutableStateFlow(PlayerRack(playerId = ""))
    val playerRack: StateFlow<PlayerRack> = _playerRack.asStateFlow()

    private val _players = MutableStateFlow<List<PlayerSummary>>(emptyList())
    val players: StateFlow<List<PlayerSummary>> = _players.asStateFlow()

    private val _gameConfig = MutableStateFlow(GameConfig.STANDARD)
    val gameConfig: StateFlow<GameConfig> = _gameConfig.asStateFlow()

    private val _gracePeriodCountdown = MutableStateFlow<Int?>(null)
    val gracePeriodCountdown: StateFlow<Int?> = _gracePeriodCountdown.asStateFlow()

    val isMyTurn: StateFlow<Boolean> = combine(_boardState, _myPlayerId) { board, myId ->
        board.activePlayerId.isNotEmpty() && board.activePlayerId == myId && !board.isGracePeriod
    }.stateIn(scope, SharingStarted.Eagerly, false)

    // Events
    private val _gameStartedEvents = MutableSharedFlow<NetworkPacket.GameStartedEvent>(extraBufferCapacity = 4)
    val gameStartedEvents: SharedFlow<NetworkPacket.GameStartedEvent> = _gameStartedEvents.asSharedFlow()

    private val _gameOverEvents = MutableSharedFlow<NetworkPacket.GameOverEvent>(extraBufferCapacity = 4)
    val gameOverEvents: SharedFlow<NetworkPacket.GameOverEvent> = _gameOverEvents.asSharedFlow()

    private val _turnRollbackEvents = MutableSharedFlow<NetworkPacket.TurnRollbackEvent>(extraBufferCapacity = 8)
    val turnRollbackEvents: SharedFlow<NetworkPacket.TurnRollbackEvent> = _turnRollbackEvents.asSharedFlow()

    private val _voiceSignals = MutableSharedFlow<NetworkPacket.VoiceSignalPacket>(extraBufferCapacity = 64)
    val voiceSignals: SharedFlow<NetworkPacket.VoiceSignalPacket> = _voiceSignals.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    // Heartbeat Manager
    private val heartbeatManager = HeartbeatManager(
        scope = scope,
        sendPacket = { ping -> webSocketClient.send(ping) },
        onMissedHeartbeatTimeout = { handleConnectionLost("Heartbeat timeout") },
        getStateVersionId = { _boardState.value.versionId }
    )
    val latencyMs: StateFlow<Long> = heartbeatManager.latencyMs

    // Throttling for drag events (50ms)
    private var lastDragSendTime = 0L

    private var reconnectJob: Job? = null
    private var connectJob: Job? = null

    init {
        // Collect incoming packets from WebSocket
        scope.launch {
            webSocketClient.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }
        // React quickly when the server drops the socket (no need to wait for the heartbeat timeout)
        scope.launch {
            webSocketClient.connectionClosed.collect { reason ->
                if (_connectionStatus.value is ConnectionStatus.Connected) {
                    handleConnectionLost(reason)
                }
            }
        }
    }

    // ==========================================
    // ROOM OPERATIONS
    // ==========================================

    /** Pings the server over HTTP so a sleeping instance starts waking up before the user acts. */
    fun warmUpServer() {
        val httpUrl = serverUrl
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
        scope.launch { webSocketClient.warmUp(httpUrl) }
    }

    /**
     * Fetches the STUN/TURN servers configured on the game server (used by live voice).
     * Falls back to public Google STUN if the server cannot be reached.
     */
    suspend fun fetchIceServers(): List<IceServerConfig> = withContext(Dispatchers.IO) {
        val fallback = listOf(IceServerConfig(urls = listOf("stun:stun.l.google.com:19302")))
        try {
            val httpUrl = serverUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            val conn = java.net.URL(httpUrl.trimEnd('/') + "/ice-servers").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<IceServersResponse>(body)
            parsed.iceServers.ifEmpty { fallback }
        } catch (_: Exception) {
            fallback
        }
    }

    suspend fun createRoom(hostName: String, config: GameConfig = GameConfig.STANDARD) {
        connectAndExecute {
            webSocketClient.send(NetworkPacket.CreateRoomRequest(hostName = hostName, config = config))
        }
    }

    suspend fun joinRoom(roomCode: String, playerName: String) {
        connectAndExecute {
            webSocketClient.send(NetworkPacket.JoinRoomRequest(roomCode = roomCode.trim().uppercase(), playerName = playerName))
        }
    }

    /** Aborts a first connection still in progress. */
    fun cancelConnecting() {
        connectJob?.cancel()
        connectJob = null
        if (_connectionStatus.value is ConnectionStatus.Connecting) {
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    suspend fun startGame() {
        val code = _roomCode.value ?: return
        sendOrQueue(NetworkPacket.StartGameRequest(roomCode = code))
    }

    suspend fun updateConfig(config: GameConfig) {
        val code = _roomCode.value ?: return
        sendOrQueue(NetworkPacket.UpdateGameConfigRequest(roomCode = code, config = config))
    }

    /**
     * Leaves the current room for good: tells the server, closes the socket and resets local state.
     */
    suspend fun leaveRoom() {
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        heartbeatManager.stop()

        val code = _roomCode.value
        if (code != null && webSocketClient.isConnected) {
            try {
                webSocketClient.send(NetworkPacket.LeaveRoomRequest(roomCode = code))
            } catch (_: Exception) {}
        }
        try {
            webSocketClient.disconnect()
        } catch (_: Exception) {}

        localActionQueue.clear()
        _roomCode.value = null
        _myPlayerId.value = null
        _sessionToken.value = null
        _isHost.value = false
        _boardState.value = BoardState()
        _playerRack.value = PlayerRack(playerId = "")
        _players.value = emptyList()
        _gracePeriodCountdown.value = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }

    // ==========================================
    // IN-GAME ACTIONS
    // ==========================================

    suspend fun sendTileDragged(tileId: String, posX: Float, posY: Float) {
        val now = System.currentTimeMillis()
        if (now - lastDragSendTime < 50L) return // Throttled to 50ms
        lastDragSendTime = now

        val myId = _myPlayerId.value ?: return
        val packet = NetworkPacket.TileDraggedEvent(playerId = myId, tileId = tileId, posX = posX, posY = posY)
        sendOrQueue(packet)
    }

    suspend fun sendSetModified(intermediateMelds: List<MeldSet>) {
        val myId = _myPlayerId.value ?: return
        val packet = NetworkPacket.SetModifiedEvent(playerId = myId, intermediateMelds = intermediateMelds)
        sendOrQueue(packet)
    }

    suspend fun commitTurn(proposedMelds: List<MeldSet>, remainingRackTileIds: List<String>) {
        val packet = NetworkPacket.EndTurnRequest(
            proposedMelds = proposedMelds,
            remainingRackTileIds = remainingRackTileIds
        )
        sendOrQueue(packet)
    }

    suspend fun drawTile() {
        val myId = _myPlayerId.value ?: return
        val packet = NetworkPacket.DrawTileRequest(playerId = myId)
        sendOrQueue(packet)
    }

    suspend fun sendVoiceSignal(signal: NetworkPacket.VoiceSignalPacket) {
        if (webSocketClient.isConnected) {
            try {
                webSocketClient.send(signal)
            } catch (_: Exception) {}
        }
    }

    private suspend fun sendOrQueue(packet: NetworkPacket) {
        if (webSocketClient.isConnected) {
            try {
                webSocketClient.send(packet)
            } catch (e: Exception) {
                localActionQueue.offer(packet)
                handleConnectionLost("Envoi impossible: ${e.message}")
            }
        } else {
            localActionQueue.offer(packet)
        }
    }

    // ==========================================
    // FIRST CONNECTION (with server wake-up tolerance)
    // ==========================================

    private suspend fun connectAndExecute(block: suspend () -> Unit) {
        connectJob?.cancel()
        val job = scope.launch {
            val started = System.currentTimeMillis()
            var attempt = 1
            _connectionStatus.value = ConnectionStatus.Connecting(attempt, "Connexion au serveur...")
            while (isActive) {
                try {
                    webSocketClient.connect(serverUrl)
                    _connectionStatus.value = ConnectionStatus.Connected
                    heartbeatManager.start()
                    block()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed > initialConnectBudgetMs) {
                        _connectionStatus.value = ConnectionStatus.Failed(e)
                        _errors.emit("Impossible de joindre le serveur de jeu. Vérifiez votre connexion et réessayez.")
                        _connectionStatus.value = ConnectionStatus.Disconnected
                        return@launch
                    }
                    attempt++
                    val seconds = (elapsed / 1000).toInt()
                    _connectionStatus.value = ConnectionStatus.Connecting(
                        attempt = attempt,
                        message = if (seconds < 10) "Connexion au serveur..."
                        else "Réveil du serveur en cours ($seconds s)... Cela peut prendre jusqu'à une minute."
                    )
                    delay(2_000L)
                }
            }
        }
        connectJob = job
        job.join()
    }

    // ==========================================
    // TRANSPARENT RECONNECTION ("ZÉRO RUPTURE")
    // ==========================================

    private fun handleConnectionLost(reason: String) {
        if (_connectionStatus.value is ConnectionStatus.Reconnecting) return
        heartbeatManager.stop()
        triggerReconnection(reason)
    }

    private fun triggerReconnection(reason: String) {
        val room = _roomCode.value
        val playerId = _myPlayerId.value
        val token = _sessionToken.value

        // If not in a room yet, simply fail
        if (room == null || playerId == null || token == null) {
            _connectionStatus.value = ConnectionStatus.Disconnected
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 1
            while (isActive) {
                val delayMs = backoff.getDelay(attempt)
                _connectionStatus.value = ConnectionStatus.Reconnecting(
                    attempt = attempt,
                    nextRetryDelayMs = delayMs,
                    reason = reason
                )
                delay(delayMs)

                try {
                    webSocketClient.connect(serverUrl)
                    _connectionStatus.value = ConnectionStatus.Connected

                    // Send Reconnect packet with last known state version ID
                    val lastKnownVersion = _boardState.value.versionId
                    webSocketClient.send(
                        NetworkPacket.ReconnectRequest(
                            roomCode = room,
                            playerId = playerId,
                            sessionToken = token,
                            lastKnownVersionId = lastKnownVersion
                        )
                    )

                    heartbeatManager.start()

                    // Flush queued actions
                    while (localActionQueue.isNotEmpty()) {
                        val queued = localActionQueue.poll() ?: break
                        webSocketClient.send(queued)
                    }
                    break // Reconnection successful!
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                }
            }
        }
    }

    // ==========================================
    // INCOMING PACKET DISPATCHER
    // ==========================================

    private fun fullTurnMillis(): Long = _gameConfig.value.turnDurationSeconds * 1000L

    private fun updateTileCounts(counts: Map<String, Int>) {
        _players.value = _players.value.map { p ->
            val count = counts[p.id] ?: p.tileCount
            p.copy(tileCount = count)
        }
    }

    private suspend fun handleIncomingPacket(packet: NetworkPacket) {
        when (packet) {
            is NetworkPacket.Pong -> {
                heartbeatManager.handlePong(packet)
            }

            is NetworkPacket.RoomJoinedResponse -> {
                _roomCode.value = packet.roomCode
                _myPlayerId.value = packet.playerId
                _sessionToken.value = packet.sessionToken
                _isHost.value = packet.isHost
                _gameConfig.value = packet.config
                _players.value = packet.players
                _playerRack.value = PlayerRack(playerId = packet.playerId)
                _boardState.value = BoardState()
            }

            is NetworkPacket.PlayerJoinedEvent -> {
                _players.value = _players.value.filterNot { it.id == packet.player.id } + packet.player
            }

            is NetworkPacket.PlayerLeftEvent -> {
                _players.value = _players.value
                    .filterNot { it.id == packet.playerId }
                    .map { p -> if (packet.newHostId != null && p.id == packet.newHostId) p.copy(isHost = true) else p }
                if (packet.newHostId != null && packet.newHostId == _myPlayerId.value) {
                    _isHost.value = true
                }
            }

            is NetworkPacket.GameConfigUpdatedEvent -> {
                _gameConfig.value = packet.config
            }

            is NetworkPacket.GameStartedEvent -> {
                _gracePeriodCountdown.value = null
                _boardState.value = packet.boardState
                _playerRack.value = packet.initialRack
                _players.value = packet.players
                _gameStartedEvents.emit(packet)
            }

            is NetworkPacket.GameOverEvent -> {
                _gracePeriodCountdown.value = null
                _boardState.value = _boardState.value.copy(activePlayerId = "", isGracePeriod = false)
                _gameOverEvents.emit(packet)
            }

            is NetworkPacket.TurnCommittedEvent -> {
                // The server broadcasts the board before switching turns: apply nextPlayerId ourselves.
                _boardState.value = packet.boardState.copy(
                    activePlayerId = packet.nextPlayerId,
                    turnRemainingMillis = fullTurnMillis()
                )
                packet.updatedRack?.let { rack ->
                    _playerRack.value = rack
                }
                updateTileCounts(packet.playerTilesCount)
            }

            is NetworkPacket.TurnRollbackEvent -> {
                _gracePeriodCountdown.value = null
                _boardState.value = packet.restoredBoardState.copy(
                    activePlayerId = packet.nextPlayerId,
                    turnRemainingMillis = fullTurnMillis(),
                    isGracePeriod = false,
                    disconnectedPlayerId = null
                )
                packet.restoredRack?.let { rack ->
                    _playerRack.value = rack
                }
                updateTileCounts(packet.playerTilesCount)
                _turnRollbackEvents.emit(packet)
            }

            is NetworkPacket.TileDrawnEvent -> {
                _boardState.value = _boardState.value.copy(
                    drawPileCount = packet.newDrawPileCount,
                    activePlayerId = packet.nextPlayerId,
                    turnRemainingMillis = fullTurnMillis()
                )
                val drawn = packet.drawnTile
                if (drawn != null && packet.playerId == _myPlayerId.value) {
                    _playerRack.value = _playerRack.value.withTileAdded(drawn)
                }
                updateTileCounts(packet.playerTilesCount)
            }

            is NetworkPacket.SyncDeltaResponse -> {
                val currentBoard = _boardState.value
                _boardState.value = currentBoard.copy(
                    versionId = packet.targetVersionId,
                    melds = packet.meldModifications,
                    activePlayerId = packet.activePlayerId,
                    turnRemainingMillis = packet.turnRemainingMillis
                )
            }

            is NetworkPacket.FullSnapshotResponse -> {
                _gracePeriodCountdown.value = null
                _boardState.value = packet.boardState
                _playerRack.value = packet.playerRack
                _players.value = packet.players
                _gameConfig.value = packet.config
                _isHost.value = packet.players.any { it.id == _myPlayerId.value && it.isHost }
            }

            is NetworkPacket.PlayerDisconnectedEvent -> {
                _players.value = _players.value.map {
                    if (it.id == packet.playerId) it.copy(isConnected = false) else it
                }
                if (packet.gracePeriodSeconds > 0) {
                    _gracePeriodCountdown.value = packet.gracePeriodSeconds
                    _boardState.value = _boardState.value.copy(
                        isGracePeriod = true,
                        disconnectedPlayerId = packet.playerId
                    )
                }
            }

            is NetworkPacket.PlayerReconnectedEvent -> {
                _players.value = _players.value.map {
                    if (it.id == packet.playerId) it.copy(isConnected = true) else it
                }
                if (_boardState.value.disconnectedPlayerId == packet.playerId) {
                    _boardState.value = _boardState.value.copy(isGracePeriod = false, disconnectedPlayerId = null)
                }
                _gracePeriodCountdown.value = null
            }

            is NetworkPacket.GracePeriodExpiredEvent -> {
                _gracePeriodCountdown.value = null
                _boardState.value = _boardState.value.copy(isGracePeriod = false, disconnectedPlayerId = null)
            }

            is NetworkPacket.VoiceSignalPacket -> {
                _voiceSignals.emit(packet)
            }

            is NetworkPacket.ErrorResponse -> {
                _errors.emit(packet.message)
            }

            else -> {
                // Other events handled by UI observers
            }
        }
    }
}
