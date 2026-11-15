package com.rummikub.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rummikub.app.account.AccountController
import com.rummikub.app.update.InstallResult
import com.rummikub.app.update.UpdateManager
import com.rummikub.app.ui.account.UpdateDialog
import com.rummikub.core.network.ReleaseInfo
import com.rummikub.app.account.AuthState
import com.rummikub.app.audio.AndroidAudioBridge
import com.rummikub.app.ui.account.FriendsDialog
import com.rummikub.app.ui.account.HistoryDialog
import com.rummikub.app.ui.account.InviteDialog
import com.rummikub.app.ui.account.LoadingScreen
import com.rummikub.app.ui.account.LoginScreen
import com.rummikub.app.ui.account.PseudoScreen
import com.rummikub.core.network.FriendStatus
import com.rummikub.core.network.SupabaseApi
import kotlinx.coroutines.delay
import com.rummikub.app.game.TurnController
import com.rummikub.app.ui.GameScreen
import com.rummikub.app.ui.lobby.LobbyScreen
import com.rummikub.app.ui.lobby.WaitingRoomScreen
import com.rummikub.app.ui.theme.RummikubTheme
import com.rummikub.app.ui.victory.PlayerEndScore
import com.rummikub.app.ui.victory.VictoryScreen
import com.rummikub.core.audio.AudioController
import com.rummikub.core.audio.FocusMusicTrack
import com.rummikub.core.network.GameNetworkRepository
import kotlinx.coroutines.launch

enum class AppScreen {
    LOBBY,
    WAITING_ROOM,
    IN_GAME,
    VICTORY
}

class MainActivity : ComponentActivity() {

    private lateinit var networkRepository: GameNetworkRepository
    private lateinit var turnController: TurnController
    private lateinit var audioBridge: AndroidAudioBridge
    private lateinit var audioController: AudioController
    private lateinit var accounts: AccountController
    private lateinit var updates: UpdateManager

    private val micPermissionGranted = mutableStateOf(false)

    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) audioBridge.library.add(uris)
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch { accounts.setPhotoAvatar(this@MainActivity, uri) }
    }

    private val micPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micPermissionGranted.value = granted
        audioBridge.onMicPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force Landscape mode, immersive full screen, and keep the screen awake during play
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // Cloud Authoritative Server on Render (accessible worldwide by any phone)
        val serverWsUrl = "wss://rummikub-friends.onrender.com"

        networkRepository = GameNetworkRepository(serverUrl = serverWsUrl)
        updates = UpdateManager(this)
        accounts = AccountController(
            context = this,
            api = SupabaseApi(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_PUBLISHABLE_KEY),
            repo = networkRepository,
            scope = lifecycleScope
        )
        turnController = TurnController(networkRepository, lifecycleScope)
        audioBridge = AndroidAudioBridge(this, networkRepository, lifecycleScope)
        audioController = audioBridge.controller

        // Wake the (free-tier) server up right away so creating a room is fast
        networkRepository.warmUpServer()

        // Soft ambience by default
        audioController.setMusicVolume(0.30f)
        audioController.playTrack(FocusMusicTrack.DEFAULT_PLAYLIST.first())

        val alreadyGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micPermissionGranted.value = alreadyGranted
        if (alreadyGranted) audioBridge.onMicPermissionResult(true)

        setContent {
            RummikubTheme {
                val connectionStatus by networkRepository.connectionStatus.collectAsState()
                val roomCode by networkRepository.roomCode.collectAsState()
                val isHost by networkRepository.isHost.collectAsState()
                val players by networkRepository.players.collectAsState()
                val gameConfig by networkRepository.gameConfig.collectAsState()
                val voiceAvailable by micPermissionGranted

                // Account (Google sign-in, pseudo, friends, history)
                val auth by accounts.auth.collectAsState()
                val friends by accounts.friends.collectAsState()
                val onlineFriends by networkRepository.onlineFriends.collectAsState()
                val history by accounts.history.collectAsState()
                val historyLoading by accounts.historyLoading.collectAsState()
                var showFriends by remember { mutableStateOf(false) }
                var showHistory by remember { mutableStateOf(false) }
                var showAvatarPicker by remember { mutableStateOf(false) }

                // Built-in update (direct APK only): checked once, a few seconds after the start
                var update by remember { mutableStateOf<ReleaseInfo?>(null) }
                var updateProgress by remember { mutableStateOf<Float?>(null) }
                var updateMessage by remember { mutableStateOf<String?>(null) }
                var updateFile by remember { mutableStateOf<java.io.File?>(null) }
                LaunchedEffect(Unit) {
                    delay(4_000)
                    update = updates.checkForUpdate()
                }
                var pendingInvite by remember { mutableStateOf<com.rummikub.core.model.NetworkPacket.RoomInviteEvent?>(null) }

                var currentScreen by remember { mutableStateOf(AppScreen.LOBBY) }
                var showLobbyAudio by remember { mutableStateOf(false) }
                var victoryScores by remember { mutableStateOf<List<PlayerEndScore>>(emptyList()) }
                var winnerName by remember { mutableStateOf("") }
                var isMeWinner by remember { mutableStateOf(false) }
                var roundNumber by remember { mutableStateOf(1) }

                val snackbarHostState = remember { SnackbarHostState() }
                var showQuitDialog by remember { mutableStateOf(false) }

                // Back button: never drops the player out of the app by accident.
                //  - in game: ask before leaving the table
                //  - waiting room / victory: leave the room and go back to the lobby
                //  - lobby: cancel a connection in progress, otherwise the system closes the app
                BackHandler(enabled = currentScreen != AppScreen.LOBBY || connectionStatus is com.rummikub.core.network.ConnectionStatus.Connecting || showLobbyAudio) {
                    when {
                        showLobbyAudio -> showLobbyAudio = false
                        currentScreen == AppScreen.IN_GAME -> showQuitDialog = true
                        currentScreen == AppScreen.LOBBY -> networkRepository.cancelConnecting()
                        else -> lifecycleScope.launch { networkRepository.leaveRoom() }
                    }
                }
                if (showQuitDialog) {
                    AlertDialog(
                        onDismissRequest = { showQuitDialog = false },
                        title = { Text("Quitter la partie ?") },
                        text = { Text("Vous quittez la table : la partie continue sans vous ou s'arrête s'il ne reste personne.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showQuitDialog = false
                                lifecycleScope.launch { networkRepository.leaveRoom() }
                            }) { Text("Quitter") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showQuitDialog = false }) { Text("Rester") }
                        }
                    )
                }

                // Room joined -> waiting room; room left (or lost) -> lobby
                LaunchedEffect(roomCode) {
                    if (roomCode == null) {
                        if (currentScreen != AppScreen.LOBBY) currentScreen = AppScreen.LOBBY
                    } else {
                        // Against the computer the table starts by itself: skip the waiting room.
                        val vsComputer = networkRepository.gameConfig.value.botCount > 0
                        if (currentScreen == AppScreen.LOBBY && !vsComputer) currentScreen = AppScreen.WAITING_ROOM
                        // Ask for the microphone once we are in a room (voice chat with friends)
                        if (!micPermissionGranted.value && networkRepository.gameConfig.value.botCount == 0) {
                            micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }

                // Game (re)started by the host -> table, from the waiting room or the victory screen
                LaunchedEffect(Unit) {
                    networkRepository.gameStartedEvents.collect {
                        currentScreen = AppScreen.IN_GAME
                    }
                }

                // Listen for GameOverEvent from server
                LaunchedEffect(Unit) {
                    networkRepository.gameOverEvents.collect { evt ->
                        winnerName = evt.winnerName
                        roundNumber = evt.roundNumber
                        isMeWinner = evt.winnerId == networkRepository.myPlayerId.value
                        victoryScores = evt.scores.map { s ->
                            PlayerEndScore(
                                playerId = s.playerId,
                                playerName = s.playerName,
                                isWinner = s.isWinner,
                                remainingTileCount = s.remainingTileCount,
                                scoreDelta = s.scoreDelta,
                                totalScore = s.totalScore,
                                hadJokerPenalty = s.hadJokerPenalty
                            )
                        }
                        currentScreen = AppScreen.VICTORY
                    }
                }

                // Server errors (room not found, unauthorized, reconnect failed...)
                LaunchedEffect(Unit) {
                    networkRepository.errors.collect { message ->
                        snackbarHostState.showSnackbar(
                            message = message,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long
                        )
                    }
                }

                // Account messages (sign-in problems, friend requests...)
                LaunchedEffect(Unit) {
                    accounts.messages.collect { message ->
                        snackbarHostState.showSnackbar(message = message, withDismissAction = true, duration = SnackbarDuration.Long)
                    }
                }

                // A friend invites me to his table
                LaunchedEffect(Unit) {
                    networkRepository.invites.collect { invite ->
                        if (currentScreen == AppScreen.LOBBY) pendingInvite = invite
                        else snackbarHostState.showSnackbar("${invite.fromPseudo} vous invite à jouer (terminez d'abord cette partie).")
                    }
                }

                // Keep friends' presence fresh while waiting for players
                LaunchedEffect(currentScreen, auth) {
                    if (currentScreen == AppScreen.WAITING_ROOM && auth is AuthState.SignedIn) {
                        while (true) {
                            networkRepository.requestFriendsPresence()
                            delay(6_000)
                        }
                    }
                }

                // Signed out while on a table (or kicked back to the login): go back to the lobby state
                LaunchedEffect(auth) {
                    if (auth is AuthState.SignedOut && currentScreen != AppScreen.LOBBY) currentScreen = AppScreen.LOBBY
                }

                // Local turn messages (invalid board, first meld too low...)
                LaunchedEffect(Unit) {
                    turnController.messages.collect { message ->
                        snackbarHostState.showSnackbar(
                            message = message,
                            withDismissAction = true,
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                // Turn rollbacks decided by the server (timeout, invalid board, grace period expired)
                LaunchedEffect(Unit) {
                    networkRepository.turnRollbackEvents.collect { evt ->
                        val isMine = evt.playerId == networkRepository.myPlayerId.value
                        val playerName = networkRepository.players.value
                            .firstOrNull { it.id == evt.playerId }?.name ?: "Un joueur"
                        val message = if (isMine) {
                            val penalty = if (evt.penaltyTiles.isNotEmpty()) " (+${evt.penaltyTiles.size} tuile(s) de pénalité)" else ""
                            "Votre tour a été annulé : ${evt.reason}$penalty"
                        } else {
                            "Tour de $playerName annulé : ${evt.reason}"
                        }
                        if (isMine) audioController.onTurnRollback()
                        snackbarHostState.showSnackbar(
                            message = message,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                  when (val a = auth) {
                    AuthState.Loading -> LoadingScreen("Chargement de votre compte...")
                    AuthState.SignedOut, AuthState.SigningIn -> LoginScreen(
                        isSigningIn = a == AuthState.SigningIn,
                        onSignInWithGoogle = { lifecycleScope.launch { accounts.signInWithGoogle(this@MainActivity) } }
                    )
                    is AuthState.NeedsPseudo -> PseudoScreen(
                        check = { accounts.checkPseudo(it) },
                        confirm = { accounts.choosePseudo(it) },
                        onSignOut = { lifecycleScope.launch { accounts.signOut() } }
                    )
                    else -> AnimatedContent(
                        targetState = currentScreen,
                        label = "screen_transition"
                    ) { screen ->
                        when (screen) {
                            AppScreen.LOBBY -> {
                                LobbyScreen(
                                    connectionStatus = connectionStatus,
                                    onCreateRoom = { hostName, config ->
                                        lifecycleScope.launch {
                                            networkRepository.createRoom(hostName, config)
                                        }
                                    },
                                    onJoinRoom = { code, playerName ->
                                        lifecycleScope.launch {
                                            networkRepository.joinRoom(code, playerName)
                                        }
                                    },
                                    onCancelConnecting = { networkRepository.cancelConnecting() },
                                    startingMessage = if (roomCode != null && gameConfig.botCount > 0)
                                        "Préparation de la partie contre l'ordinateur..." else null,
                                    onOpenAudio = { showLobbyAudio = true },
                                    accountPseudo = (a as? AuthState.SignedIn)?.pseudo,
                                    accountAvatar = (a as? AuthState.SignedIn)?.avatar,
                                    onEditAvatar = { showAvatarPicker = true },
                                    pendingFriendRequests = friends.count { it.status == FriendStatus.INCOMING_REQUEST },
                                    onOpenFriends = { showFriends = true },
                                    onOpenHistory = { showHistory = true },
                                    onSignOut = { lifecycleScope.launch { accounts.signOut() } }
                                )
                            }

                            AppScreen.WAITING_ROOM -> {
                                WaitingRoomScreen(
                                    roomCode = roomCode ?: "------",
                                    players = players,
                                    isHost = isHost,
                                    gameConfig = gameConfig,
                                    onStartGame = {
                                        lifecycleScope.launch {
                                            networkRepository.startGame()
                                        }
                                    },
                                    onLeaveRoom = {
                                        lifecycleScope.launch { networkRepository.leaveRoom() }
                                    },
                                    inviteFriends = friends,
                                    onlineFriendIds = onlineFriends,
                                    onInviteFriend = { f -> lifecycleScope.launch { networkRepository.inviteFriend(f.userId) } }
                                )
                            }

                            AppScreen.IN_GAME -> {
                                GameScreen(
                                    networkRepository = networkRepository,
                                    turnController = turnController,
                                    audioController = audioController,
                                    audioBridge = audioBridge,
                                    voiceAvailable = voiceAvailable,
                                    onAddMusic = { pickMusic.launch(arrayOf("audio/*")) }
                                )
                            }

                            AppScreen.VICTORY -> {
                                VictoryScreen(
                                    scores = victoryScores,
                                    winnerName = winnerName,
                                    isMeWinner = isMeWinner,
                                    roundNumber = roundNumber,
                                    onPlayAgain = {
                                        if (gameConfig.botCount > 0) {
                                            // Computer table: deal again right away, no waiting room
                                            lifecycleScope.launch { networkRepository.startGame() }
                                        } else {
                                            currentScreen = AppScreen.WAITING_ROOM
                                        }
                                    },
                                    onBackToLobby = {
                                        lifecycleScope.launch { networkRepository.leaveRoom() }
                                    }
                                )
                            }
                        }
                    }
                  }

                    val pendingUpdate = update
                    if (pendingUpdate != null && currentScreen == AppScreen.LOBBY && (auth is AuthState.SignedIn || auth is AuthState.Guest)) {
                        UpdateDialog(
                            versionName = pendingUpdate.versionName,
                            notes = pendingUpdate.notes,
                            sizeMb = (pendingUpdate.sizeBytes / 1_000_000L).toInt(),
                            progress = updateProgress,
                            message = updateMessage,
                            onUpdate = {
                                updateMessage = null
                                lifecycleScope.launch {
                                    try {
                                        val file = updateFile ?: run {
                                            updateProgress = 0f
                                            updates.download(pendingUpdate) { p -> updateProgress = p }
                                        }
                                        updateFile = file
                                        updateProgress = null
                                        when (updates.install(this@MainActivity, file)) {
                                            InstallResult.STARTED -> Unit
                                            InstallResult.NEEDS_PERMISSION -> updateMessage =
                                                "Autorisez l'installation depuis cette application dans les réglages qui viennent de s'ouvrir, puis revenez et touchez « Mettre à jour »."
                                            InstallResult.FAILED -> updateMessage = "Installation impossible. Réessayez."
                                        }
                                    } catch (e: Exception) {
                                        updateProgress = null
                                        updateMessage = "Téléchargement impossible. Vérifiez votre connexion."
                                    }
                                }
                            },
                            onLater = { update = null }
                        )
                    }

                    if (showAvatarPicker) {
                        val me = auth as? AuthState.SignedIn
                        com.rummikub.app.ui.account.AvatarPickerDialog(
                            currentAvatar = me?.avatar,
                            name = me?.pseudo ?: "",
                            onPickEmoji = { e -> lifecycleScope.launch { accounts.setEmojiAvatar(e) } },
                            onPickPhoto = {
                                pickPhoto.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onRemove = { lifecycleScope.launch { accounts.removeAvatar() } },
                            onDismiss = { showAvatarPicker = false }
                        )
                    }

                    if (showFriends) {
                        FriendsDialog(
                            friends = friends,
                            onlineIds = onlineFriends,
                            onAdd = { accounts.addFriend(it) },
                            onAccept = { accounts.acceptFriend(it) },
                            onRemove = { accounts.removeFriend(it) },
                            onRefresh = { accounts.refreshFriends() },
                            onDismiss = { showFriends = false }
                        )
                    }

                    if (showHistory) {
                        LaunchedEffect(Unit) { accounts.loadHistory() }
                        HistoryDialog(
                            history = history,
                            loading = historyLoading,
                            myUserId = accounts.myUserId,
                            onDismiss = { showHistory = false }
                        )
                    }

                    pendingInvite?.let { invite ->
                        InviteDialog(
                            fromPseudo = invite.fromPseudo,
                            onAccept = {
                                pendingInvite = null
                                lifecycleScope.launch { networkRepository.joinRoom(invite.roomCode, accounts.myPseudo ?: "Ami") }
                            },
                            onDecline = { pendingInvite = null }
                        )
                    }

                    if (showLobbyAudio) {
                        com.rummikub.app.ui.dialogs.AudioSettingsHost(
                            audioBridge = audioBridge,
                            gameConfig = gameConfig,
                            onAddMusic = { pickMusic.launch(arrayOf("audio/*")) },
                            onDismiss = { showLobbyAudio = false }
                        )
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                    )
                }
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        super.onDestroy()
        audioBridge.release()
    }
}
