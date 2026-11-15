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

    // ---------- accounts, friends presence, invitations ----------

    /** Gives a fresh Supabase access token (refreshing it when needed), or null for anonymous play. */
    @Volatile var accessTokenProvider: (suspend () -> String?)? = null

    /** While signed in, keep a connection open even outside a table: friends see us online and invites arrive. */
    @Volatile var lobbyMode: Boolean = false

    private val _serverAuth = MutableStateFlow<NetworkPacket.AuthResponse?>(null)
    /** What the game server knows about this phone's account (null until authenticated). */
    val serverAuth: StateFlow<NetworkPacket.AuthResponse?> = _serverAuth.asStateFlow()

    private val _onlineFriends = MutableStateFlow<Set<String>>(emptySet())
    /** User ids of the friends currently online. */
    val onlineFriends: StateFlow<Set<String>> = _onlineFriends.asStateFlow()

    private val _invites = MutableSharedFlow<NetworkPacket.RoomInviteEvent>(extraBufferCapacity = 8)
    /** Invitations to a friend's table. */
    val invites: SharedFlow<NetworkPacket.RoomInviteEvent> = _invites.asSharedFlow()

    private var pendingAuth: CompletableDeferred<NetworkPacket.AuthResponse>? = null

    /** The table as the player whose turn it is currently arranges it (live view for the others). */
    private val _liveMelds = MutableStateFlow<List<MeldSet>?>(null)
    val liveMelds: StateFlow<List<MeldSet>?> = _liveMelds.asStateFlow()

    /** The tile I just drew (highlighted in the rack for a few seconds). */
    private val _lastDrawnTileId = MutableStateFlow<String?>(null)
    val lastDrawnTileId: StateFlow<String?> = _lastDrawnTileId.asStateFlow()

    private val _lastMove = MutableStateFlow<LastMove?>(null)
    /** The last move of an opponent (null after my own action or a new game). */
    val lastMove: StateFlow<LastMove?> = _lastMove.asStateFlow()

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

    private val _sessionOverEvents = MutableSharedFlow<NetworkPacket.SessionOverEvent>(extraBufferCapacity = 2)
    /** The host ended the game: final standings of all the rounds. */
    val sessionOverEvents: SharedFlow<NetworkPacket.SessionOverEvent> = _sessionOverEvents.asSharedFlow()

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
    @Volatile private var pendingAction: NetworkPacket? = null

    /** True from the tap on Valider/Piocher until the server answers (drives the button feedback). */
    private val _actionInFlight = MutableStateFlow(false)
    val actionInFlight: StateFlow<Boolean> = _actionInFlight.asStateFlow()
    @Volatile private var pendingSentAt: Long = 0L
    private var pendingJob: Job? = null
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

    /** Host only: end the game (after a finished round) and show the final ranking to everybody. */
    suspend fun endSession() {
        sendOrQueue(NetworkPacket.EndSessionRequest)
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
        if (!lobbyMode) {
            try {
                webSocketClient.disconnect()
            } catch (_: Exception) {}
        }

        localActionQueue.clear()
        clearPendingAction()
        _roomCode.value = null
        _myPlayerId.value = null
        _sessionToken.value = null
        _isHost.value = false
        _boardState.value = BoardState()
        _playerRack.value = PlayerRack(playerId = "")
        _players.value = emptyList()
        _gracePeriodCountdown.value = null
        _connectionStatus.value = if (lobbyMode && webSocketClient.isConnected) ConnectionStatus.Connected else ConnectionStatus.Disconnected
        if (lobbyMode && webSocketClient.isConnected) {
            heartbeatManager.start()
            requestFriendsPresence()
        }
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
        sendTurnAction(packet)
    }

    suspend fun drawTile() {
        val myId = _myPlayerId.value ?: run {
            _errors.emit("Vous n'êtes pas encore dans une partie.")
            return
        }
        val packet = NetworkPacket.DrawTileRequest(playerId = myId)
        sendTurnAction(packet)
    }

    suspend fun sendVoiceSignal(signal: NetworkPacket.VoiceSignalPacket) {
        if (webSocketClient.isConnected) {
            try {
                webSocketClient.send(signal)
            } catch (_: Exception) {}
        }
    }

    /**
     * Sends "Valider" / "Piocher" and keeps it as pending until the server answers.
     * A phone that silently lost its network would otherwise swallow the action: if no answer
     * comes back within a few seconds the connection is considered dead, a reconnection starts,
     * and the pending action is sent again once the server has confirmed it is still our turn.
     */
    private suspend fun sendTurnAction(packet: NetworkPacket) {
        pendingAction = packet
        _actionInFlight.value = true
        pendingSentAt = System.currentTimeMillis()
        startPendingWatchdog(packet)
        if (!webSocketClient.isConnected) {
            // No pop-up: the network indicator shows "Reconnexion" and the action is sent again once back
            handleConnectionLost("Action sans connexion")
            return
        }
        try {
            webSocketClient.send(packet)
        } catch (e: Exception) {
            handleConnectionLost("Envoi impossible: ${e.message}")
        }
    }

    private fun startPendingWatchdog(packet: NetworkPacket) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(7_000L)
            if (pendingAction === packet) {
                // Silent: the indicator switches to "Reconnexion" and the action is resent after the snapshot
                handleConnectionLost("Pas de réponse du serveur")
            }
        }
    }

    private fun clearPendingAction() {
        if (pendingAction != null) {
            System.out.println("[net] commande ${pendingAction!!::class.simpleName} acquittée en ${System.currentTimeMillis() - pendingSentAt} ms")
        }
        pendingAction = null
        _actionInFlight.value = false
        pendingJob?.cancel()
        pendingJob = null
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

    /**
     * Identifies this phone to the server with the Supabase access token.
     * Returns true when the server accepted the account (or when playing anonymously).
     */
    suspend fun authenticate(): Boolean {
        val provider = accessTokenProvider ?: return true
        val token = try { provider() } catch (_: Exception) { null } ?: return true
        val waiter = CompletableDeferred<NetworkPacket.AuthResponse>()
        pendingAuth = waiter
        return try {
            webSocketClient.send(NetworkPacket.AuthRequest(token))
            withTimeout(8_000L) { waiter.await() }.ok
        } catch (_: Exception) {
            false
        } finally {
            if (pendingAuth === waiter) pendingAuth = null
        }
    }

    /** Opens (or keeps) the lobby connection of a signed-in player so that friends and invites work. */
    suspend fun ensureLobbyConnection() {
        lobbyMode = true
        if (webSocketClient.isConnected && _serverAuth.value?.ok == true) return
        connectAndExecute { requestFriendsPresence() }
    }

    /** Stops the lobby connection (sign-out). */
    suspend fun stopLobbyConnection() {
        lobbyMode = false
        _serverAuth.value = null
        _onlineFriends.value = emptySet()
        if (_roomCode.value == null) {
            heartbeatManager.stop()
            try { webSocketClient.disconnect() } catch (_: Exception) {}
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    suspend fun requestFriendsPresence() {
        if (webSocketClient.isConnected) {
            try { webSocketClient.send(NetworkPacket.FriendsPresenceRequest) } catch (_: Exception) {}
        }
    }

    suspend fun inviteFriend(friendUserId: String) {
        sendOrQueue(NetworkPacket.InviteFriendRequest(friendUserId))
    }

    private suspend fun connectAndExecute(block: suspend () -> Unit) {
        connectJob?.cancel()
        val job = scope.launch {
            val started = System.currentTimeMillis()
            var attempt = 1
            _connectionStatus.value = ConnectionStatus.Connecting(attempt, "Connexion au serveur...")
            while (isActive) {
                try {
                    if (!(webSocketClient.isConnected && (accessTokenProvider == null || _serverAuth.value?.ok == true))) {
                        webSocketClient.connect(serverUrl)
                        _serverAuth.value = null
                        _connectionStatus.value = ConnectionStatus.Connected
                        heartbeatManager.start()
                        authenticate()
                    } else {
                        _connectionStatus.value = ConnectionStatus.Connected
                    }
                    block()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed > initialConnectBudgetMs) {
                        // Stays "Failed": the lobby shows a calm banner with a Retry button (no pop-up)
                        _connectionStatus.value = ConnectionStatus.Failed(e)
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

        // Outside a table there is nothing to resume, unless the signed-in lobby connection must be kept alive
        val inRoom = room != null && playerId != null && token != null
        if (!inRoom && !lobbyMode) {
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
                    _serverAuth.value = null
                    _connectionStatus.value = ConnectionStatus.Connected
                    heartbeatManager.start()

                    // Who we are first (friends presence, invitations, history), then the table
                    authenticate()

                    if (inRoom) {
                        // Send Reconnect packet with last known state version ID
                        val lastKnownVersion = _boardState.value.versionId
                        webSocketClient.send(
                            NetworkPacket.ReconnectRequest(
                                roomCode = room!!,
                                playerId = playerId!!,
                                sessionToken = token!!,
                                lastKnownVersionId = lastKnownVersion
                            )
                        )
                    } else {
                        requestFriendsPresence()
                    }

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

    private fun tileLabel(t: Tile): String {
        if (t.isJoker) return "joker"
        val color = when (t.color) {
            TileColor.BLACK -> "noir"
            TileColor.BLUE -> "bleu"
            TileColor.RED -> "rouge"
            TileColor.YELLOW -> "jaune"
            null -> ""
        }
        return "${t.number} $color"
    }

    /**
     * Compares the table before and after an opponent's turn.
     * A tile is "placed" if it was not on the table, "moved" if it kept less than half of the
     * tiles of its former combination (so extending a run does not flag the whole run).
     */
    private fun describeMove(oldMelds: List<MeldSet>, newMelds: List<MeldSet>, actorId: String): LastMove {
        val who = _players.value.firstOrNull { it.id == actorId }?.name ?: "Un joueur"
        val oldSetOfTile = HashMap<String, Set<String>>()
        for (m in oldMelds) {
            val ids = m.tiles.map { it.id }.toSet()
            for (id in ids) oldSetOfTile[id] = ids
        }
        val placedTiles = ArrayList<Tile>()
        val moved = HashSet<String>()
        for (m in newMelds) {
            val newIds = m.tiles.map { it.id }.toSet()
            for (t in m.tiles) {
                val old = oldSetOfTile[t.id]
                if (old == null) {
                    placedTiles += t
                } else if ((old intersect newIds).size * 2 < old.size) {
                    moved += t.id
                }
            }
        }
        val parts = ArrayList<String>()
        if (placedTiles.isNotEmpty()) {
            val shown = placedTiles.take(5).joinToString(", ") { tileLabel(it) }
            parts += "a posé " + shown + if (placedTiles.size > 5) "…" else ""
        }
        if (moved.isNotEmpty()) {
            parts += (if (placedTiles.isEmpty()) "a déplacé " else "déplacé ") + moved.size + if (moved.size > 1) " tuiles" else " tuile"
        }
        val text = if (parts.isEmpty()) "$who a joué" else "$who " + parts.joinToString(" et ")
        return LastMove(actorId, who, placedTiles.map { it.id }.toSet(), moved, text)
    }

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
                _myPlayerId.value = packet.playerId
                _sessionToken.value = packet.sessionToken
                _isHost.value = packet.isHost
                _gameConfig.value = packet.config
                _players.value = packet.players
                _playerRack.value = PlayerRack(playerId = packet.playerId)
                _boardState.value = BoardState()
                _roomCode.value = packet.roomCode // last: observers see a complete state
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

            is NetworkPacket.SetModifiedEvent -> {
                if (packet.playerId != _myPlayerId.value &&
                    packet.playerId == _boardState.value.activePlayerId) {
                    _liveMelds.value = packet.intermediateMelds
                }
            }

            is NetworkPacket.GameStartedEvent -> {
                _liveMelds.value = null
                _lastMove.value = null
                _gracePeriodCountdown.value = null
                _boardState.value = packet.boardState
                _playerRack.value = packet.initialRack
                _players.value = packet.players
                _gameStartedEvents.emit(packet)
            }

            is NetworkPacket.SessionOverEvent -> {
                _sessionOverEvents.emit(packet)
            }

            is NetworkPacket.GameOverEvent -> {
                _gracePeriodCountdown.value = null
                _boardState.value = _boardState.value.copy(activePlayerId = "", isGracePeriod = false)
                _gameOverEvents.emit(packet)
            }

            is NetworkPacket.TurnCommittedEvent -> {
                _liveMelds.value = null
                if (packet.activePlayerId == _myPlayerId.value) {
                    clearPendingAction()
                    _lastMove.value = null
                } else {
                    _lastMove.value = describeMove(_boardState.value.melds, packet.boardState.melds, packet.activePlayerId)
                }
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
                _liveMelds.value = null
                if (packet.playerId == _myPlayerId.value) clearPendingAction()
                _lastMove.value = null
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
                _liveMelds.value = null
                if (packet.playerId == _myPlayerId.value) {
                    clearPendingAction()
                    _lastMove.value = null
                } else {
                    val who = _players.value.firstOrNull { it.id == packet.playerId }?.name ?: "Un joueur"
                    _lastMove.value = LastMove(packet.playerId, who, emptySet(), emptySet(), "$who a pioché une tuile")
                }
                _boardState.value = _boardState.value.copy(
                    drawPileCount = packet.newDrawPileCount,
                    activePlayerId = packet.nextPlayerId,
                    turnRemainingMillis = fullTurnMillis()
                )
                val drawn = packet.drawnTile
                if (drawn != null && packet.playerId == _myPlayerId.value) {
                    _playerRack.value = _playerRack.value.withTileAdded(drawn)
                    _lastDrawnTileId.value = drawn.id
                    scope.launch {
                        delay(3500L)
                        if (_lastDrawnTileId.value == drawn.id) _lastDrawnTileId.value = null
                    }
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
                _liveMelds.value = null
                _gracePeriodCountdown.value = null
                _boardState.value = packet.boardState
                _playerRack.value = packet.playerRack
                _players.value = packet.players
                _gameConfig.value = packet.config
                _isHost.value = packet.players.any { it.id == _myPlayerId.value && it.isHost }

                // Reconnected: if our action never reached the server and it is still our turn, send it again.
                val pending = pendingAction
                if (pending != null) {
                    val stillMyTurn = packet.boardState.activePlayerId == _myPlayerId.value && !packet.boardState.isGracePeriod
                    if (stillMyTurn && (pending is NetworkPacket.DrawTileRequest ||
                            (pending is NetworkPacket.EndTurnRequest))) {
                        try {
                            webSocketClient.send(pending)
                            startPendingWatchdog(pending)
                        } catch (_: Exception) {}
                    } else {
                        clearPendingAction()
                    }
                }
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

            is NetworkPacket.AuthResponse -> {
                _serverAuth.value = if (packet.ok) packet else null
                pendingAuth?.complete(packet)
            }

            is NetworkPacket.FriendsPresenceResponse -> {
                _onlineFriends.value = packet.online.toSet()
            }

            is NetworkPacket.RoomInviteEvent -> {
                _invites.emit(packet)
            }

            is NetworkPacket.InviteResponse -> {
                _errors.emit(
                    if (packet.delivered) "Invitation envoyée."
                    else "Cet ami n'est pas en ligne pour le moment."
                )
            }

            is NetworkPacket.ErrorResponse -> {
                // Refusals of a turn action (nothing played, invalid proposal) also answer it
                if (packet.code == "NO_TILES_PLAYED" || packet.code == "INVALID_PROPOSAL") clearPendingAction()
                _errors.emit(packet.message)
                if (packet.code == "RECONNECT_FAILED") {
                    // The server no longer knows this table (restart, timeout): stop retrying and go home.
                    leaveRoom()
                }
            }

            else -> {
                // Other events handled by UI observers
            }
        }
    }
}
