package com.rummikub.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.rummikub.app.audio.AndroidAudioBridge
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

    private val micPermissionGranted = mutableStateOf(false)

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

                var currentScreen by remember { mutableStateOf(AppScreen.LOBBY) }
                var victoryScores by remember { mutableStateOf<List<PlayerEndScore>>(emptyList()) }
                var winnerName by remember { mutableStateOf("") }
                var isMeWinner by remember { mutableStateOf(false) }

                val snackbarHostState = remember { SnackbarHostState() }

                // Room joined -> waiting room; room left (or lost) -> lobby
                LaunchedEffect(roomCode) {
                    if (roomCode == null) {
                        if (currentScreen != AppScreen.LOBBY) currentScreen = AppScreen.LOBBY
                    } else {
                        if (currentScreen == AppScreen.LOBBY) currentScreen = AppScreen.WAITING_ROOM
                        // Ask for the microphone once we are in a room (voice chat with friends)
                        if (!micPermissionGranted.value) {
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
                        isMeWinner = evt.winnerId == networkRepository.myPlayerId.value
                        victoryScores = evt.scores.map { s ->
                            PlayerEndScore(
                                playerId = s.playerId,
                                playerName = s.playerName,
                                isWinner = s.isWinner,
                                remainingTileCount = s.remainingTileCount,
                                scoreDelta = s.scoreDelta,
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
                    AnimatedContent(
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
                                    onCancelConnecting = { networkRepository.cancelConnecting() }
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
                                    }
                                )
                            }

                            AppScreen.IN_GAME -> {
                                GameScreen(
                                    networkRepository = networkRepository,
                                    turnController = turnController,
                                    audioController = audioController,
                                    voiceAvailable = voiceAvailable
                                )
                            }

                            AppScreen.VICTORY -> {
                                VictoryScreen(
                                    scores = victoryScores,
                                    winnerName = winnerName,
                                    isMeWinner = isMeWinner,
                                    onPlayAgain = {
                                        currentScreen = AppScreen.WAITING_ROOM
                                    },
                                    onBackToLobby = {
                                        lifecycleScope.launch { networkRepository.leaveRoom() }
                                    }
                                )
                            }
                        }
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
