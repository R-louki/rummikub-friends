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
        board.activePlayerId.isNotEmpty() && board.activePlayerId == myId
    }.stateIn(scope, SharingStarted.Eagerly, false)

    // Events
    private val _gameOverEvents = MutableSharedFlow<NetworkPacket.GameOverEvent>(extraBufferCapacity = 4)
    val gameOverEvents: SharedFlow<NetworkPacket.GameOverEvent> = _gameOverEvents.asSharedFlow()

    private val _turnRollbackEvents = MutableSharedFlow<NetworkPacket.TurnRollbackEvent>(extraBufferCapacity = 8)
    val turnRollbackEvents: SharedFlow<NetworkPacket.TurnRollbackEvent> = _turnRollbackEvents.asSharedFlow()

    private val _voiceSignals = MutableSharedFlow<NetworkPacket.VoiceSignalPacket>(extraBufferCapacity = 32)
    val voiceSignals: SharedFlow<NetworkPacket.VoiceSignalPacket> = _voiceSignals.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    // Heartbeat Manager
    private val heartbeatManager = HeartbeatManager(
        scope = scope,
        sendPacket = { ping -> webSocketClient.send(ping) },
        onMissedHeartbeatTimeout = { handleConnectionLost("Heartbeat timeout (missed 2 pongs)") },
        getStateVersionId = { _boardState.value.versionId }
    )
    val latencyMs: StateFlow<Long> = heartbeatManager.latencyMs

    // Throttling for drag events (50ms)
    private var lastDragSendTime = 0L

    init {
        // Collect incoming packets from WebSocket
        scope.launch {
            webSocketClient.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }
    }

    // ==========================================
    // ROOM OPERATIONS
    // ==========================================

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

    suspend fun startGame() {
        val code = _roomCode.value ?: return
        webSocketClient.send(NetworkPacket.StartGameRequest(roomCode = code))
    }

    suspend fun updateConfig(config: GameConfig) {
        val code = _roomCode.value ?: return
        webSocketClient.send(NetworkPacket.UpdateGameConfigRequest(roomCode = code, config = config))
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
            webSocketClient.send(signal)
        }
    }

    private suspend fun sendOrQueue(packet: NetworkPacket) {
        if (webSocketClient.isConnected) {
            webSocketClient.send(packet)
        } else {
            localActionQueue.offer(packet)
        }
    }

    // ==========================================
    // TRANSPARENT RECONNECTION ("ZÉRO RUPTURE")
    // ==========================================

    private suspend fun connectAndExecute(block: suspend () -> Unit) {
        _connectionStatus.value = ConnectionStatus.Connecting
        try {
            webSocketClient.connect(serverUrl)
            _connectionStatus.value = ConnectionStatus.Connected
            heartbeatManager.start()
            block()
        } catch (e: Exception) {
            _connectionStatus.value = ConnectionStatus.Failed(e)
            triggerReconnection("Initial connection failed: ${e.message}")
        }
    }

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

        scope.launch {
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
                } catch (e: Exception) {
                    attempt++
                }
            }
        }
    }

    // ==========================================
    // INCOMING PACKET DISPATCHER
    // ==========================================

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
            }

            is NetworkPacket.PlayerJoinedEvent -> {
                _players.value = _players.value.filterNot { it.id == packet.player.id } + packet.player
            }

            is NetworkPacket.PlayerLeftEvent -> {
                _players.value = _players.value.filterNot { it.id == packet.playerId }
                if (packet.newHostId != null && packet.newHostId == _myPlayerId.value) {
                    _isHost.value = true
                }
            }

            is NetworkPacket.GameConfigUpdatedEvent -> {
                _gameConfig.value = packet.config
            }

            is NetworkPacket.GameStartedEvent -> {
                _boardState.value = packet.boardState
                _playerRack.value = packet.initialRack
                _players.value = packet.players
            }

            is NetworkPacket.GameOverEvent -> {
                _gameOverEvents.emit(packet)
            }

            is NetworkPacket.TurnCommittedEvent -> {
                _boardState.value = packet.boardState
                if (packet.updatedRack != null) {
                    _playerRack.value = packet.updatedRack
                }
                // Update player tile counts
                _players.value = _players.value.map { p ->
                    val count = packet.playerTilesCount[p.id] ?: p.tileCount
                    p.copy(tileCount = count)
                }
            }

            is NetworkPacket.TurnRollbackEvent -> {
                _boardState.value = packet.restoredBoardState
                if (packet.restoredRack != null) {
                    _playerRack.value = packet.restoredRack
                }
                _players.value = _players.value.map { p ->
                    val count = packet.playerTilesCount[p.id] ?: p.tileCount
                    p.copy(tileCount = count)
                }
                _turnRollbackEvents.emit(packet)
            }

            is NetworkPacket.TileDrawnEvent -> {
                _boardState.value = _boardState.value.copy(
                    drawPileCount = packet.newDrawPileCount,
                    activePlayerId = packet.nextPlayerId
                )
                if (packet.drawnTile != null && packet.playerId == _myPlayerId.value) {
                    _playerRack.value = _playerRack.value.withTileAdded(packet.drawnTile)
                }
                _players.value = _players.value.map { p ->
                    val count = packet.playerTilesCount[p.id] ?: p.tileCount
                    p.copy(tileCount = count)
                }
            }

            is NetworkPacket.SyncDeltaResponse -> {
                // Apply delta updates
                val currentBoard = _boardState.value
                _boardState.value = currentBoard.copy(
                    versionId = packet.targetVersionId,
                    melds = packet.meldModifications,
                    activePlayerId = packet.activePlayerId,
                    turnRemainingMillis = packet.turnRemainingMillis
                )
            }

            is NetworkPacket.FullSnapshotResponse -> {
                _boardState.value = packet.boardState
                _playerRack.value = packet.playerRack
                _players.value = packet.players
                _gameConfig.value = packet.config
            }

            is NetworkPacket.PlayerDisconnectedEvent -> {
                _players.value = _players.value.map {
                    if (it.id == packet.playerId) it.copy(isConnected = false) else it
                }
                if (packet.gracePeriodSeconds > 0) {
                    _gracePeriodCountdown.value = packet.gracePeriodSeconds
                }
            }

            is NetworkPacket.PlayerReconnectedEvent -> {
                _players.value = _players.value.map {
                    if (it.id == packet.playerId) it.copy(isConnected = true) else it
                }
                _gracePeriodCountdown.value = null
            }

            is NetworkPacket.GracePeriodExpiredEvent -> {
                _gracePeriodCountdown.value = null
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
