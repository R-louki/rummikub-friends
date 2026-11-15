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
import android.widget.Toast
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
import com.rummikub.app.ui.victory.FinalStanding
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

    // Photo to frame (set once the picked image is decoded); GetContent works on every device,
    // with or without the system photo picker.
    private val cropBitmap = mutableStateOf<android.graphics.Bitmap?>(null)

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.rummikub.app.account.ImageUtils.loadForCrop(this@MainActivity, uri)
            }
            if (bmp != null) cropBitmap.value = bmp
            else Toast.makeText(this@MainActivity, "Cette image ne peut pas être lue.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Ask for the microphone; when Android will not ask again, open the app settings instead. */
    private fun requestMicOrOpenSettings() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micPermissionGranted.value = true
            audioBridge.onMicPermissionResult(true)
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) || !askedMicBefore) {
            askedMicBefore = true
            micPermissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", packageName, null))
            )
            Toast.makeText(this, "Autorisez le micro dans « Autorisations »", Toast.LENGTH_LONG).show()
        }
    }
    private var askedMicBefore = false

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
        val serverWsUrl = BuildConfig.SERVER_URL

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

        // No ambient music by default: the player picks a track in "Musique & audio".
        // The volume is preset so the chosen track is audible but soft.
        audioController.setMusicVolume(0.30f)
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        com.rummikub.app.ui.theme.ThemeState.select(settings.getInt("theme_v2", 0))
        com.rummikub.app.ui.dnd.DragSettings.level = settings.getInt("drag", 1)
        audioController.voiceChat.setMicSensitivity(settings.getInt("mic", 1))
        audioBridge.micGain = settings.getFloat("micgain", 1f)

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
                val lastSeen by accounts.lastSeen.collectAsState()
                val history by accounts.history.collectAsState()
                val historyLoading by accounts.historyLoading.collectAsState()
                var showFriends by remember { mutableStateOf(false) }
                var showHistory by remember { mutableStateOf(false) }
                var showAvatarPicker by remember { mutableStateOf(false) }
                // Friends already invited to the current table (reset for each new table)
                var invitedIds by remember { mutableStateOf(setOf<String>()) }
                LaunchedEffect(roomCode) { invitedIds = emptySet() }

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
                var showTheme by remember { mutableStateOf(false) }
                var victoryScores by remember { mutableStateOf<List<PlayerEndScore>>(emptyList()) }
                var winnerName by remember { mutableStateOf("") }
                var isMeWinner by remember { mutableStateOf(false) }
                var roundNumber by remember { mutableStateOf(1) }
                var finalStandings by remember { mutableStateOf<List<FinalStanding>?>(null) }
                var roundsPlayed by remember { mutableStateOf(1) }

                val snackbarHostState = remember { SnackbarHostState() }
                var showQuitDialog by remember { mutableStateOf(false) }
                var showExitAppDialog by remember { mutableStateOf(false) }
                // "Création de la table..." while the server answers (the lobby must never look frozen)
                var busyMessage by remember { mutableStateOf<String?>(null) }
                val latencyMs by networkRepository.latencyMs.collectAsState()

                // Back button: never drops the player out of the app by accident.
                //  - in game: ask before leaving the table
                //  - waiting room / victory: leave the room and go back to the lobby
                //  - lobby: cancel a connection in progress, otherwise the system closes the app
                BackHandler(enabled = true) {
                    when {
                        showLobbyAudio -> showLobbyAudio = false
                        busyMessage != null -> { busyMessage = null; networkRepository.cancelConnecting() }
                        currentScreen == AppScreen.IN_GAME -> showQuitDialog = true
                        currentScreen == AppScreen.LOBBY ->
                            if (connectionStatus is com.rummikub.core.network.ConnectionStatus.Connecting) networkRepository.cancelConnecting()
                            else showExitAppDialog = true
                        else -> lifecycleScope.launch { networkRepository.leaveRoom() }
                    }
                }
                if (showExitAppDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitAppDialog = false },
                        title = { Text("Quitter le jeu ?") },
                        text = { Text("Voulez-vous vraiment quitter Rummikub Friends ?") },
                        confirmButton = { TextButton(onClick = { showExitAppDialog = false; finish() }) { Text("Quitter") } },
                        dismissButton = { TextButton(onClick = { showExitAppDialog = false }) { Text("Rester") } }
                    )
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
                    if (roomCode != null) busyMessage = null
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
                        finalStandings = null
                        val avatars = networkRepository.players.value.associate { it.id to it.avatar }
                        victoryScores = evt.scores.map { s ->
                            PlayerEndScore(
                                avatar = avatars[s.playerId],
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

                // The host ended the whole game: final ranking of all the cumulated rounds
                LaunchedEffect(Unit) {
                    networkRepository.sessionOverEvents.collect { evt ->
                        winnerName = evt.winnerName
                        roundsPlayed = evt.roundsPlayed
                        finalStandings = evt.standings.map { FinalStanding(it.playerName, it.totalScore, it.roundsWon, it.avatar) }
                        currentScreen = AppScreen.VICTORY
                    }
                }

                // Server errors (room not found, unauthorized, reconnect failed...)
                LaunchedEffect(Unit) {
                    networkRepository.errors.collect { message ->
                        busyMessage = null
                        snackbarHostState.showSnackbar(
                            message = message,
                            withDismissAction = false,
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                // Account messages (sign-in problems, friend requests...)
                LaunchedEffect(Unit) {
                    accounts.messages.collect { message ->
                        snackbarHostState.showSnackbar(message = message, withDismissAction = false, duration = SnackbarDuration.Short)
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
                            withDismissAction = false,
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
                            withDismissAction = false,
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
                                        busyMessage = if (config.botCount > 0) "Préparation de la partie..." else "Création de la table..."
                                        lifecycleScope.launch {
                                            networkRepository.createRoom(hostName, config)
                                        }
                                    },
                                    onJoinRoom = { code, playerName ->
                                        busyMessage = "Connexion à la table..."
                                        lifecycleScope.launch {
                                            networkRepository.joinRoom(code, playerName)
                                        }
                                    },
                                    latencyMs = latencyMs,
                                    onRetryConnection = { lifecycleScope.launch { networkRepository.ensureLobbyConnection() } },
                                    onCancelConnecting = { networkRepository.cancelConnecting() },
                                    startingMessage = busyMessage ?: if (roomCode != null && gameConfig.botCount > 0)
                                        "Préparation de la partie contre l'ordinateur..." else null,
                                    onOpenAudio = { showLobbyAudio = true },
                                    onOpenTheme = { showTheme = true },
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
                                    connectionStatus = connectionStatus,
                                    latencyMs = latencyMs,
                                    inviteFriends = friends,
                                    onlineFriendIds = onlineFriends,
                                    lastSeen = lastSeen,
                                    invitedIds = invitedIds,
                                    signedIn = a is AuthState.SignedIn,
                                    onInviteFriend = { f ->
                                        invitedIds = invitedIds + f.userId
                                        lifecycleScope.launch { networkRepository.inviteFriend(f.userId) }
                                    }
                                )
                            }

                            AppScreen.IN_GAME -> {
                                GameScreen(
                                    networkRepository = networkRepository,
                                    turnController = turnController,
                                    audioController = audioController,
                                    audioBridge = audioBridge,
                                    voiceAvailable = voiceAvailable,
                                    onAddMusic = { pickMusic.launch(arrayOf("audio/*")) },
                                    onOpenTheme = { showTheme = true },
                                    onRequestMic = { requestMicOrOpenSettings() }
                                )
                            }

                            AppScreen.VICTORY -> {
                                VictoryScreen(
                                    scores = victoryScores,
                                    winnerName = winnerName,
                                    isMeWinner = isMeWinner,
                                    roundNumber = roundNumber,
                                    isHost = isHost,
                                    finalStandings = finalStandings,
                                    roundsPlayed = roundsPlayed,
                                    onNextRound = { lifecycleScope.launch { networkRepository.startGame() } },
                                    onRematch = { lifecycleScope.launch { networkRepository.startGame(newSession = true) } },
                                    onEndGame = { lifecycleScope.launch { networkRepository.endSession() } },
                                    onLeave = {
                                        finalStandings = null
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

                    val toCrop by cropBitmap
                    toCrop?.let { bmp ->
                        com.rummikub.app.ui.account.AvatarCropDialog(
                            bitmap = bmp,
                            onCancel = { cropBitmap.value = null },
                            onConfirm = { l, t, side ->
                                cropBitmap.value = null
                                lifecycleScope.launch {
                                    val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        com.rummikub.app.account.ImageUtils.cropToJpeg(bmp, l, t, side)
                                    }
                                    accounts.setPhotoAvatarBytes(jpeg)
                                }
                            }
                        )
                    }

                    if (showAvatarPicker) {
                        val me = auth as? AuthState.SignedIn
                        com.rummikub.app.ui.account.AvatarPickerDialog(
                            currentAvatar = me?.avatar,
                            name = me?.pseudo ?: "",
                            onPickEmoji = { e -> lifecycleScope.launch { accounts.setEmojiAvatar(e) } },
                            onPickPhoto = { pickPhoto.launch("image/*") },
                            onRemove = { lifecycleScope.launch { accounts.removeAvatar() } },
                            onDismiss = { showAvatarPicker = false }
                        )
                    }

                    if (showFriends) {
                        FriendsDialog(
                            friends = friends,
                            onlineIds = onlineFriends,
                            lastSeen = lastSeen,
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

                    if (showTheme) {
                        com.rummikub.app.ui.dialogs.ThemePickerDialog(
                            onPick = { i ->
                                com.rummikub.app.ui.theme.ThemeState.select(i)
                                getSharedPreferences("settings", MODE_PRIVATE).edit().putInt("theme_v2", i).apply()
                            },
                            onDismiss = { showTheme = false }
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
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 108.dp)
                    ) { data -> com.rummikub.app.ui.components.ToastBanner(data.visuals.message) { data.dismiss() } }
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

    private var resumeMusicOnStart = false
    private var unmuteMicOnStart = false

    /** App in the background: stop the music and cut the microphone (restored in onStart). */
    override fun onStop() {
        super.onStop()
        if (!::audioController.isInitialized) return
        resumeMusicOnStart = audioController.musicPlayer.isPlaying.value
        if (resumeMusicOnStart) audioController.stopMusic()
        unmuteMicOnStart = !audioController.voiceChat.isMicMuted.value
        if (unmuteMicOnStart) audioController.voiceChat.setMicrophoneMuted(true)
    }

    override fun onStart() {
        super.onStart()
        if (!::audioController.isInitialized) return
        if (resumeMusicOnStart) audioController.musicPlayer.resume()
        if (unmuteMicOnStart) audioController.voiceChat.setMicrophoneMuted(false)
        resumeMusicOnStart = false
        unmuteMicOnStart = false
    }

    override fun onDestroy() {
        super.onDestroy()
        audioBridge.release()
    }
}
