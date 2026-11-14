package com.rummikub.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rummikub.app.ui.GameScreen
import com.rummikub.app.ui.lobby.LobbyScreen
import com.rummikub.app.ui.lobby.WaitingRoomScreen
import com.rummikub.app.ui.theme.RummikubTheme
import com.rummikub.app.ui.victory.PlayerEndScore
import com.rummikub.app.ui.victory.VictoryScreen
import com.rummikub.core.audio.AudioController
import com.rummikub.core.audio.FocusMusicTrack
import com.rummikub.core.model.EndGamePlayerScore
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
    private lateinit var audioController: AudioController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force Landscape mode & immersive full screen for maximum board space
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()

        val serverWsUrl = "ws://10.0.2.2:8080"
        val myPlayerId = "P_" + System.currentTimeMillis().toString().takeLast(6)

        networkRepository = GameNetworkRepository(serverUrl = serverWsUrl)
        audioController = AudioController(
            myPlayerId = myPlayerId,
            sendSignalPacket = { signal -> networkRepository.sendVoiceSignal(signal) }
        )

        // Start initial focus ambient music track
        audioController.playTrack(FocusMusicTrack.DEFAULT_PLAYLIST.first())

        setContent {
            RummikubTheme {
                val roomCode by networkRepository.roomCode.collectAsState()
                val isHost by networkRepository.isHost.collectAsState()
                val players by networkRepository.players.collectAsState()
                val gameConfig by networkRepository.gameConfig.collectAsState()
                val boardState by networkRepository.boardState.collectAsState()

                var currentScreen by remember { mutableStateOf(AppScreen.LOBBY) }
                var victoryScores by remember { mutableStateOf<List<PlayerEndScore>>(emptyList()) }
                var winnerName by remember { mutableStateOf("") }
                var isMeWinner by remember { mutableStateOf(false) }

                // Automatically navigate to WAITING_ROOM when room is joined
                LaunchedEffect(roomCode) {
                    if (roomCode != null && currentScreen == AppScreen.LOBBY) {
                        currentScreen = AppScreen.WAITING_ROOM
                    }
                }

                // Automatically navigate to IN_GAME when game starts
                LaunchedEffect(boardState.versionId) {
                    if (boardState.versionId > 0 && currentScreen == AppScreen.WAITING_ROOM) {
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

                AnimatedContent(
                    targetState = currentScreen,
                    label = "screen_transition"
                ) { screen ->
                    when (screen) {
                        AppScreen.LOBBY -> {
                            LobbyScreen(
                                onCreateRoom = { hostName, config ->
                                    lifecycleScope.launch {
                                        networkRepository.createRoom(hostName, config)
                                    }
                                },
                                onJoinRoom = { code, playerName ->
                                    lifecycleScope.launch {
                                        networkRepository.joinRoom(code, playerName)
                                    }
                                }
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
                                    currentScreen = AppScreen.LOBBY
                                }
                            )
                        }

                        AppScreen.IN_GAME -> {
                            GameScreen(
                                networkRepository = networkRepository,
                                audioController = audioController
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
                                    currentScreen = AppScreen.LOBBY
                                }
                            )
                        }
                    }
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
        audioController.release()
    }
}
