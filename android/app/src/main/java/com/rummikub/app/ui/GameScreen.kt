package com.rummikub.app.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.board.BoardGrid
import com.rummikub.app.ui.components.PlayerAvatarView
import com.rummikub.app.ui.components.SynchronizedTimerView
import com.rummikub.app.ui.dialogs.GameSettingsDialog
import com.rummikub.app.ui.rack.PlayerRackView
import com.rummikub.app.ui.theme.*
import com.rummikub.core.audio.AudioController
import com.rummikub.core.audio.VoiceChatMode
import com.rummikub.core.model.*
import com.rummikub.core.network.ConnectionStatus
import com.rummikub.core.network.GameNetworkRepository

/**
 * Master Game Screen in Landscape orientation.
 */
@Composable
fun GameScreen(
    networkRepository: GameNetworkRepository,
    audioController: AudioController
) {
    val connectionStatus by networkRepository.connectionStatus.collectAsState()
    val roomCode by networkRepository.roomCode.collectAsState()
    val myPlayerId by networkRepository.myPlayerId.collectAsState()
    val boardState by networkRepository.boardState.collectAsState()
    val playerRack by networkRepository.playerRack.collectAsState()
    val players by networkRepository.players.collectAsState()
    val gameConfig by networkRepository.gameConfig.collectAsState()
    val isMyTurn by networkRepository.isMyTurn.collectAsState()
    val latencyMs by networkRepository.latencyMs.collectAsState()
    val graceCountdown by networkRepository.gracePeriodCountdown.collectAsState()

    // Audio states
    val musicVolume by audioController.musicVolume.collectAsState()
    val voiceVolume by audioController.voiceVolume.collectAsState()
    val sfxVolume by audioController.sfxVolume.collectAsState()
    val speakingPlayers by audioController.speakingPlayers.collectAsState()
    val mutedPlayers by audioController.voiceChat.mutedPlayers.collectAsState()
    val voiceMode by audioController.voiceChat.mode.collectAsState()
    val currentTrack by audioController.musicPlayer.currentTrack.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeltDarkGreen)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            // ==========================================
            // TOP HUD: Room Code, Players, Timer, Settings
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Room Code & Ping Badge
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "Salon: ${roomCode ?: "----"}",
                        color = Color(0xFFFFD54F),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when (connectionStatus) {
                                        is ConnectionStatus.Connected -> StatusConnected
                                        is ConnectionStatus.Reconnecting -> StatusReconnecting
                                        else -> StatusDisconnected
                                    }
                                )
                        )
                        Text(
                            text = when (connectionStatus) {
                                is ConnectionStatus.Connected -> "${latencyMs}ms"
                                is ConnectionStatus.Reconnecting -> "Reconnexion..."
                                else -> "Hors-ligne"
                            },
                            color = Color(0xBBFFFFFF),
                            fontSize = 10.sp
                        )
                    }
                }

                // Center: Other Players Avatars with Speaking Halos
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    players.forEach { player ->
                        PlayerAvatarView(
                            player = player,
                            isActiveTurn = boardState.activePlayerId == player.id,
                            isSpeaking = speakingPlayers.contains(player.id),
                            isMutedByMe = mutedPlayers.contains(player.id),
                            onToggleMute = { audioController.togglePlayerMute(player.id) }
                        )
                    }
                }

                // Right: Synchronized Timer & Settings Button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SynchronizedTimerView(
                        remainingMillis = boardState.turnRemainingMillis,
                        totalDurationSeconds = gameConfig.turnDurationSeconds,
                        isGracePeriod = boardState.isGracePeriod,
                        gracePeriodCountdownSeconds = graceCountdown
                    )

                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF26140B))
                    ) {
                        Text("⚙️", fontSize = 16.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ==========================================
            // CENTER: Table Board Grid (Magnetic Snapping)
            // ==========================================
            BoardGrid(
                boardState = boardState,
                isMyTurn = isMyTurn,
                onTileMoved = { _, _, _ -> audioController.onTileSnapped() },
                onTileDraggedThrottled = { tileId, x, y ->
                    // Throttled drag synchronization to other players
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // ==========================================
            // BOTTOM: Player Rack & Quick Actions
            // ==========================================
            PlayerRackView(
                rack = playerRack,
                isMyTurn = isMyTurn,
                onSortByRun = {
                    // Sort rack by Run (Color then Number)
                },
                onSortByGroup = {
                    // Sort rack by Group (Number then Color)
                },
                onResetTurn = {
                    // Revert turn to start-of-turn snapshot
                },
                onDrawTile = {
                    // Draw tile from deck
                },
                onCommitTurn = {
                    audioController.onTurnSuccess()
                },
                onTileDragStart = {
                    audioController.onTileGrabbed()
                }
            )
        }

        // Floating Push-to-Talk Button (shown only when in PUSH_TO_TALK mode)
        if (voiceMode == VoiceChatMode.PUSH_TO_TALK) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 140.dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E5FF))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                audioController.voiceChat.setPushToTalkPressed(true)
                                tryAwaitRelease()
                                audioController.voiceChat.setPushToTalkPressed(false)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("🎙️", fontSize = 22.sp)
            }
        }

        // Settings Dialog
        if (showSettingsDialog) {
            GameSettingsDialog(
                musicVolume = musicVolume,
                voiceVolume = voiceVolume,
                sfxVolume = sfxVolume,
                voiceMode = voiceMode,
                currentTrack = currentTrack,
                gameConfig = gameConfig,
                onMusicVolumeChanged = { audioController.setMusicVolume(it) },
                onVoiceVolumeChanged = { audioController.setVoiceVolume(it) },
                onSfxVolumeChanged = { audioController.setSfxVolume(it) },
                onVoiceModeChanged = { audioController.voiceChat.setMode(it) },
                onTrackSelected = { audioController.playTrack(it) },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}
