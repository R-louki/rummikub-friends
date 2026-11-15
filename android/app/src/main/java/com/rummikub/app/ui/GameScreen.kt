package com.rummikub.app.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.audio.AndroidAudioBridge
import com.rummikub.app.game.DropTarget
import com.rummikub.app.game.TurnController
import com.rummikub.app.ui.board.BoardGrid
import com.rummikub.app.ui.components.SynchronizedTimerView
import com.rummikub.app.ui.dialogs.AudioSettingsHost
import com.rummikub.app.ui.rack.PlayerRackView
import com.rummikub.app.ui.theme.*
import com.rummikub.core.audio.AudioController
import com.rummikub.core.audio.VoiceChatMode
import com.rummikub.core.model.PlayerSummary
import com.rummikub.core.network.ConnectionStatus
import com.rummikub.core.network.GameNetworkRepository
import kotlinx.coroutines.delay

/**
 * Game screen, landscape, built for phones:
 * - left ribbon: turn timer, players (active one highlighted, voice halo), connection & room info, settings
 * - centre: the table (as large as possible) and the rack below it
 * - right ribbon: Valider, Piocher, Annuler, Séries, Groupes, push-to-talk
 */
@Composable
fun GameScreen(
    networkRepository: GameNetworkRepository,
    turnController: TurnController,
    audioController: AudioController,
    audioBridge: AndroidAudioBridge,
    voiceAvailable: Boolean,
    onAddMusic: () -> Unit
) {
    val view = LocalView.current

    val connectionStatus by networkRepository.connectionStatus.collectAsState()
    val roomCode by networkRepository.roomCode.collectAsState()
    val myPlayerId by networkRepository.myPlayerId.collectAsState()
    val serverBoard by networkRepository.boardState.collectAsState()
    val players by networkRepository.players.collectAsState()
    val gameConfig by networkRepository.gameConfig.collectAsState()
    val isMyTurn by networkRepository.isMyTurn.collectAsState()
    val latencyMs by networkRepository.latencyMs.collectAsState()
    val graceCountdown by networkRepository.gracePeriodCountdown.collectAsState()
    val lastMove by networkRepository.lastMove.collectAsState()

    // Working (local, in-progress) state of the turn
    val workingBoard by turnController.board.collectAsState()
    val workingRack by turnController.rack.collectAsState()
    val selectedList by turnController.selectedTileIds.collectAsState()
    val selectedTileIds = remember(selectedList) { selectedList.toSet() }
    val newTileIds by turnController.newBoardTileIds.collectAsState()
    val summary by turnController.summary.collectAsState()

    // Audio states
    val musicVolume by audioController.musicVolume.collectAsState()
    val voiceVolume by audioController.voiceVolume.collectAsState()
    val sfxVolume by audioController.sfxVolume.collectAsState()
    val speakingPlayers by audioController.speakingPlayers.collectAsState()
    val mutedPlayers by audioController.voiceChat.mutedPlayers.collectAsState()
    val voiceMode by audioController.voiceChat.mode.collectAsState()
    val isMicMuted by audioController.voiceChat.isMicMuted.collectAsState()
    val isLocalSpeaking by audioController.voiceChat.isLocalSpeaking.collectAsState()
    val currentTrack by audioController.musicPlayer.currentTrack.collectAsState()
    val isMusicPlaying by audioController.musicPlayer.isPlaying.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }

    // ==========================================
    // LOCAL COUNTDOWNS (server only sends the value at turn start)
    // ==========================================
    val isGracePeriod = serverBoard.isGracePeriod || graceCountdown != null

    var remainingMillis by remember { mutableLongStateOf(serverBoard.turnRemainingMillis) }
    LaunchedEffect(serverBoard.versionId, serverBoard.activePlayerId, serverBoard.turnRemainingMillis, isGracePeriod) {
        val base = serverBoard.turnRemainingMillis
        remainingMillis = base
        if (isGracePeriod || gameConfig.turnDurationSeconds <= 0) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        var lastTickSecond = -1
        while (true) {
            val remaining = (base - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(0L)
            remainingMillis = remaining
            val sec = (remaining / 1000).toInt()
            if (isMyTurn && sec in 1..5 && sec != lastTickSecond) {
                lastTickSecond = sec
                audioController.onCountdownTick()
            }
            delay(250)
        }
    }

    var graceSeconds by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(graceCountdown) {
        graceSeconds = graceCountdown
        var s = graceCountdown ?: return@LaunchedEffect
        while (s > 0) {
            delay(1000)
            s -= 1
            graceSeconds = s
        }
    }

    val activePlayerName = players.firstOrNull { it.id == serverBoard.activePlayerId }?.name ?: "..."
    val hasMadeInitialMeld = workingRack.hasMadeInitialMeld

    val activeIsBot = players.firstOrNull { it.id == serverBoard.activePlayerId }?.isBot == true
    val statusText = when {
        isGracePeriod -> "Attente de reconnexion de $activePlayerName..."
        !isMyTurn && activeIsBot -> "$activePlayerName réfléchit..."
        !isMyTurn -> "Tour de $activePlayerName"
        summary.invalidMeldCount > 0 -> "À vous · ${summary.invalidMeldCount} combinaison(s) invalide(s)"
        !hasMadeInitialMeld && gameConfig.initialMeldThreshold > 0 ->
            "À vous · 1ère pose ${summary.newMeldPoints}/${gameConfig.initialMeldThreshold} pts · ${summary.tilesPlaced} posée(s)"
        selectedTileIds.isNotEmpty() -> "À vous · ${selectedTileIds.size} sélectionnée(s) : touchez une zone de dépôt"
        else -> "À vous · ${summary.tilesPlaced} tuile(s) posée(s)"
    }
    val statusColor = when {
        isGracePeriod -> StatusReconnecting
        !isMyTurn -> Color(0x99FFFFFF)
        summary.invalidMeldCount > 0 -> Color(0xFFFF8A80)
        !hasMadeInitialMeld && gameConfig.initialMeldThreshold > 0 && summary.newMeldPoints < gameConfig.initialMeldThreshold -> Color(0xFFFFD54F)
        else -> Color(0xFF81C784)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FeltDarkGreen)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ==========================================
            // LEFT RIBBON: timer, players, connection, settings
            // ==========================================
            Column(
                modifier = Modifier
                    .width(84.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF07170D))
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SynchronizedTimerView(
                    remainingMillis = remainingMillis,
                    totalDurationSeconds = gameConfig.turnDurationSeconds,
                    isGracePeriod = isGracePeriod,
                    gracePeriodCountdownSeconds = graceSeconds,
                    compact = true
                )

                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    players.forEach { player ->
                        RibbonPlayer(
                            player = player,
                            isMe = player.id == myPlayerId,
                            isActiveTurn = serverBoard.activePlayerId == player.id,
                            isSpeaking = speakingPlayers.contains(player.id) || (player.id == myPlayerId && isLocalSpeaking),
                            isMutedByMe = mutedPlayers.contains(player.id),
                            onToggleMute = { if (player.id != myPlayerId) audioController.togglePlayerMute(player.id) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Connection, draw pile, room code
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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
                            is ConnectionStatus.Reconnecting -> "Reconnexion"
                            else -> "Hors-ligne"
                        },
                        color = Color(0xBBFFFFFF),
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
                Text("Pioche ${serverBoard.drawPileCount}", color = Color(0xBBFFFFFF), fontSize = 9.sp)
                Text(
                    text = roomCode ?: "----",
                    color = Color(0xFFFFD54F),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF26140B))
                ) {
                    Text("⚙️", fontSize = 15.sp)
                }
            }

            // ==========================================
            // CENTRE: status line, table, rack
            // ==========================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (lastMove != null) 0.dp else 3.dp)
                )
                // What the opponent just did: orange = laid down from the rack, violet = moved on the table
                lastMove?.let { move ->
                    Text(
                        text = "Dernier coup : ${move.description}" +
                            (if (move.placed.isNotEmpty()) "  ■ orange = posées" else "") +
                            (if (move.moved.isNotEmpty()) "  ■ violet = déplacées" else ""),
                        color = Color(0xFFFFB74D),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 3.dp)
                    )
                }

                BoardGrid(
                    boardState = workingBoard,
                    isMyTurn = isMyTurn,
                    newTileIds = newTileIds,
                    selectedTileIds = selectedTileIds,
                    placedByOthers = lastMove?.placed ?: emptySet(),
                    movedByOthers = lastMove?.moved ?: emptySet(),
                    initialMeldThreshold = if (hasMadeInitialMeld) 0 else gameConfig.initialMeldThreshold,
                    onTileTapped = { tile -> turnController.toggleSelection(tile.id) },
                    onTileDropped = { tileId, target ->
                        turnController.moveTile(tileId, target)
                        audioController.onTileSnapped()
                    },
                    onTargetTapped = { target ->
                        if (turnController.sendSelectedTo(target)) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            audioController.onTileSnapped()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                PlayerRackView(
                    rack = workingRack,
                    isMyTurn = isMyTurn,
                    selectedTileIds = selectedTileIds,
                    onTileTapped = { tile ->
                        audioController.onTileGrabbed()
                        turnController.toggleSelection(tile.id)
                    },
                    onTileDroppedOnRack = { tileId -> turnController.moveTile(tileId, DropTarget.Rack) },
                    onRackTapped = {
                        if (turnController.sendSelectedTo(DropTarget.Rack)) audioController.onTileSnapped()
                    },
                    modifier = Modifier.height(92.dp)
                )
            }

            // ==========================================
            // RIGHT RIBBON: actions
            // ==========================================
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF1B0E09))
                    .padding(horizontal = 5.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RibbonButton(
                    label = "✓ Valider",
                    enabled = isMyTurn,
                    container = Color(0xFF2E7D32),
                    height = 54.dp,
                    fontSize = 13.sp
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    if (turnController.commitTurn()) audioController.onTurnSuccess()
                }
                RibbonButton(
                    label = "+1 Piocher",
                    enabled = isMyTurn,
                    container = Color(0xFFE65100),
                    height = 44.dp
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    turnController.drawTile()
                }
                RibbonButton(
                    label = "↺ Annuler",
                    enabled = isMyTurn && summary.isDirty,
                    container = Color(0xFF5D4037),
                    height = 40.dp,
                    textColor = Color(0xFFFFCC80)
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    turnController.resetTurn()
                }

                if (selectedTileIds.isNotEmpty()) {
                    RibbonButton(
                        label = "✕ Désélect.",
                        enabled = true,
                        container = Color(0xFF37474F),
                        height = 34.dp,
                        fontSize = 10.sp
                    ) { turnController.clearSelection() }
                }

                Spacer(modifier = Modifier.height(2.dp))

                RibbonButton(label = "🌈 Séries", enabled = true, container = Color(0xFF3E2723), height = 36.dp, fontSize = 10.sp) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    audioController.sfxPlayer.playSound(com.rummikub.core.audio.GameSoundEffect.TILE_SORT)
                    turnController.sortByRun()
                }
                RibbonButton(label = "🔢 Groupes", enabled = true, container = Color(0xFF3E2723), height = 36.dp, fontSize = 10.sp) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    audioController.sfxPlayer.playSound(com.rummikub.core.audio.GameSoundEffect.TILE_SORT)
                    turnController.sortByGroup()
                }

                Spacer(modifier = Modifier.weight(1f))

                // Microphone: push-to-talk button, or mute toggle in open-mic mode
                if (voiceAvailable) {
                    if (voiceMode == VoiceChatMode.PUSH_TO_TALK) {
                        val pressed by audioController.voiceChat.isPushToTalkPressed.collectAsState()
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(if (pressed) Color(0xFF00E5FF) else Color(0xFF006064))
                                .border(2.dp, Color(0xFF00E5FF), CircleShape)
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
                        Text("Maintenir", color = Color(0x99FFFFFF), fontSize = 9.sp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isMicMuted) Color(0xFF7F1D1D) else if (isLocalSpeaking) Color(0xFF00838F) else Color(0xFF263238))
                                .border(2.dp, if (isMicMuted) StatusDisconnected else VadSpeakingHalo, CircleShape)
                                .clickable { audioController.toggleMicrophoneMute() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (isMicMuted) "🔇" else "🎙️", fontSize = 20.sp)
                        }
                        Text(if (isMicMuted) "Micro coupé" else "Micro ouvert", color = Color(0x99FFFFFF), fontSize = 9.sp)
                    }
                } else {
                    Text("🎙️ Micro\nnon autorisé", color = Color(0x66FFFFFF), fontSize = 9.sp, textAlign = TextAlign.Center)
                }
            }
        }

        // Settings Dialog
        if (showSettingsDialog) {
            AudioSettingsHost(
                audioBridge = audioBridge,
                gameConfig = gameConfig,
                onAddMusic = onAddMusic,
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}

@Composable
private fun RibbonButton(
    label: String,
    enabled: Boolean,
    container: Color,
    height: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 11.sp,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            disabledContainerColor = container.copy(alpha = 0.35f),
            disabledContentColor = Color(0x88FFFFFF)
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            color = if (enabled) textColor else Color(0x88FFFFFF),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * Compact player entry for the left ribbon: avatar with turn/voice halo, name, tile count.
 */
@Composable
private fun RibbonPlayer(
    player: PlayerSummary,
    isMe: Boolean,
    isActiveTurn: Boolean,
    isSpeaking: Boolean,
    isMutedByMe: Boolean,
    onToggleMute: () -> Unit
) {
    val borderColor = when {
        isSpeaking -> VadSpeakingHalo
        isActiveTurn -> Color(0xFFFFD54F)
        !player.isConnected -> StatusReconnecting
        else -> Color(0xFF4E342E)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActiveTurn) Color(0x33FFD54F) else Color.Transparent)
            .clickable { onToggleMute() }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isSpeaking) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(VadSpeakingHalo.copy(alpha = 0.35f))
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF5D4037), Color(0xFF3E2723))))
                    .border(if (isActiveTurn || isSpeaking) 2.5.dp else 1.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = player.name.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (player.isHost) {
                Text("👑", fontSize = 10.sp, modifier = Modifier.align(Alignment.TopStart))
            }
            if (isMutedByMe) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(StatusDisconnected),
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black) }
            }
            if (!player.isConnected) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(StatusReconnecting)
                )
            }
        }
        Text(
            text = if (isMe) "Moi" else player.name,
            color = if (isActiveTurn) Color(0xFFFFD54F) else Color.White,
            fontSize = 9.sp,
            fontWeight = if (isActiveTurn) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${player.tileCount} tuiles",
            color = if (player.isConnected) Color(0xFFD7CCC8) else StatusReconnecting,
            fontSize = 8.sp
        )
        if (player.totalScore != 0) {
            Text(
                text = "Σ ${player.totalScore} pts",
                color = if (player.totalScore > 0) Color(0xFF69F0AE) else Color(0xFFFF8A80),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
