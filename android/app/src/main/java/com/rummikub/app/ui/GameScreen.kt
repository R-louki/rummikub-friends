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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    onAddMusic: () -> Unit,
    onOpenTheme: () -> Unit = {}
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
    val liveMelds by networkRepository.liveMelds.collectAsState()
    val actionInFlight by networkRepository.actionInFlight.collectAsState()
    val drawnTileId by networkRepository.lastDrawnTileId.collectAsState()
    val canUndo by turnController.canUndo.collectAsState()

    // Working (local, in-progress) state of the turn
    val workingBoard by turnController.board.collectAsState()
    val workingRack by turnController.rack.collectAsState()
    val rackEpoch by turnController.rackEpoch.collectAsState()
    var rackCollapsed by remember { mutableStateOf(false) }
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
            .background(Brush.radialGradient(listOf(FeltLight, FeltMediumGreen, FeltDarkGreen)))
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ==========================================
            // LEFT: players only (avatar, turn-timer ring, tile count)
            // ==========================================
            Column(
                modifier = Modifier
                    .width(66.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 3.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val totalMs = gameConfig.turnDurationSeconds * 1000f
                players.forEach { player ->
                    val active = serverBoard.activePlayerId == player.id
                    RibbonPlayer(
                        player = player,
                        isMe = player.id == myPlayerId,
                        isActiveTurn = active,
                        isSpeaking = speakingPlayers.contains(player.id) || (player.id == myPlayerId && isLocalSpeaking),
                        isMutedByMe = mutedPlayers.contains(player.id),
                        timerFraction = if (active && !isGracePeriod && totalMs > 0f) (remainingMillis / totalMs).coerceIn(0f, 1f) else null,
                        secondsLeft = if (active && !isGracePeriod && totalMs > 0f) ((remainingMillis + 999) / 1000).toInt() else null,
                        onToggleMute = { if (player.id != myPlayerId) audioController.togglePlayerMute(player.id) }
                    )
                }
            }

            // ==========================================
            // CENTRE: the table (as large as possible) and the rack
            // ==========================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp, vertical = 2.dp)
            ) {
                // While another player is arranging the table, show their moves live
                val shownBoard = if (!isMyTurn && liveMelds != null) workingBoard.copy(melds = liveMelds!!) else workingBoard
                // A hint only when it helps (no permanent status bar)
                val hint: String? = when {
                    isGracePeriod -> statusText + (graceSeconds?.let { " ($it s)" } ?: "")
                    !isMyTurn -> listOfNotNull(statusText, lastMove?.description).joinToString(" · ")
                    summary.invalidMeldCount > 0 ||
                        (!hasMadeInitialMeld && gameConfig.initialMeldThreshold > 0) ||
                        selectedTileIds.isNotEmpty() -> statusText
                    else -> null
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    BoardGrid(
                        boardState = shownBoard,
                        isMyTurn = isMyTurn,
                        newTileIds = newTileIds,
                        selectedTileIds = selectedTileIds,
                        placedByOthers = lastMove?.placed ?: emptySet(),
                        movedByOthers = lastMove?.moved ?: emptySet(),
                        initialMeldThreshold = if (hasMadeInitialMeld) 0 else gameConfig.initialMeldThreshold,
                        onTileTapped = { tile -> turnController.toggleSelection(tile.id) },
                        onTileDropped = { tileId, target ->
                            turnController.dropTile(tileId, target)
                            audioController.onTileSnapped()
                        },
                        onTargetTapped = { target ->
                            if (turnController.sendSelectedTo(target)) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                audioController.onTileSnapped()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (hint != null) {
                        Text(
                            text = hint,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 6.dp, start = 12.dp, end = 12.dp)
                                .background(Color(0x99000000), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                PlayerRackView(
                    rack = workingRack,
                    layoutEpoch = rackEpoch,
                    collapsed = rackCollapsed,
                    drawnTileId = drawnTileId,
                    onToggleCollapsed = { rackCollapsed = !rackCollapsed },
                    onSortRuns = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        audioController.sfxPlayer.playSound(com.rummikub.core.audio.GameSoundEffect.TILE_SORT)
                        turnController.sortByRun()
                    },
                    onSortGroups = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        audioController.sfxPlayer.playSound(com.rummikub.core.audio.GameSoundEffect.TILE_SORT)
                        turnController.sortByGroup()
                    },
                    isMyTurn = isMyTurn,
                    selectedTileIds = selectedTileIds,
                    onTileTapped = { tile ->
                        audioController.onTileGrabbed()
                        turnController.toggleSelection(tile.id)
                    },
                    onTileDroppedOnRack = { tileId -> turnController.dropTile(tileId, DropTarget.Rack) },
                    onRackTapped = {
                        if (turnController.sendSelectedTo(DropTarget.Rack)) audioController.onTileSnapped()
                    }
                )
            }

            // ==========================================
            // RIGHT: validate, draw (with the pile count), undo, microphone, menu
            // ==========================================
            Column(
                modifier = Modifier
                    .width(58.dp)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RoundAction(
                    glyph = if (actionInFlight) "⏳" else "✓",
                    enabled = isMyTurn && !actionInFlight,
                    container = Color(0xFF2E7D32),
                    size = 52.dp,
                    glyphSize = 26.sp
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    if (turnController.commitTurn()) audioController.onTurnSuccess()
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RoundAction(
                        glyph = if (actionInFlight) "⏳" else "+1",
                        enabled = isMyTurn && !actionInFlight,
                        container = Color(0xFFE65100),
                        size = 52.dp,
                        glyphSize = 19.sp
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        turnController.drawTile()
                    }
                    Text(
                        text = "pioche ${serverBoard.drawPileCount}",
                        color = Color(0xB3FFFFFF),
                        fontSize = 8.sp,
                        maxLines = 1
                    )
                }
                RoundAction(
                    glyph = "↶",
                    enabled = isMyTurn && canUndo,
                    container = Color(0xFF6D4C41),
                    size = 40.dp,
                    glyphSize = 20.sp,
                    textColor = Color(0xFFFFE0B2)
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    turnController.undoLast()
                }
                RoundAction(
                    glyph = "↺",
                    enabled = isMyTurn && summary.isDirty,
                    container = Color(0xFF5D4037),
                    size = 40.dp,
                    glyphSize = 20.sp,
                    textColor = Color(0xFFFFCC80)
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    turnController.resetTurn()
                }
                if (selectedTileIds.isNotEmpty()) {
                    RoundAction(glyph = "✕", enabled = true, container = Color(0xFF37474F), size = 34.dp, glyphSize = 15.sp) {
                        turnController.clearSelection()
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Microphone: push-to-talk button (hold), or mute toggle in open-mic mode
                if (voiceAvailable) {
                    if (voiceMode == VoiceChatMode.PUSH_TO_TALK) {
                        val pressed by audioController.voiceChat.isPushToTalkPressed.collectAsState()
                        Box(
                            modifier = Modifier
                                .size(46.dp)
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
                        ) { Text("🎙️", fontSize = 20.sp) }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (isMicMuted) Color(0xFF7F1D1D) else if (isLocalSpeaking) Color(0xFF00838F) else Color(0xFF263238))
                                .border(2.dp, if (isMicMuted) StatusDisconnected else VadSpeakingHalo, CircleShape)
                                .clickable { audioController.toggleMicrophoneMute() },
                            contentAlignment = Alignment.Center
                        ) { Text(if (isMicMuted) "🔇" else "🎙️", fontSize = 18.sp) }
                    }
                }

                // Menu: table code, connection quality, sound settings
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    RoundAction(glyph = "⋯", enabled = true, container = Color(0xFF26140B), size = 38.dp, glyphSize = 20.sp) {
                        menuOpen = true
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Table ${roomCode ?: "----"}") },
                            onClick = { menuOpen = false }
                        )
                        DropdownMenuItem(
                            text = { com.rummikub.app.ui.components.NetworkIndicator(connectionStatus, latencyMs) },
                            onClick = { menuOpen = false }
                        )
                        val prefs = androidx.compose.ui.platform.LocalContext.current
                            .getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                        val levelNames = listOf("Faible", "Normale", "Élevée")
                        val micLevel by audioController.voiceChat.micSensitivity.collectAsState()
                        DropdownMenuItem(
                            text = { Text("🎙️ Sensibilité micro : ${levelNames[micLevel]}") },
                            onClick = {
                                val next = (micLevel + 1) % 3
                                audioController.voiceChat.setMicSensitivity(next)
                                prefs.edit().putInt("mic", next).apply()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("✋ Sensibilité glissement : ${levelNames[com.rummikub.app.ui.dnd.DragSettings.level]}") },
                            onClick = {
                                val next = (com.rummikub.app.ui.dnd.DragSettings.level + 1) % 3
                                com.rummikub.app.ui.dnd.DragSettings.level = next
                                prefs.edit().putInt("drag", next).apply()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("🎨 Thème") },
                            onClick = { menuOpen = false; onOpenTheme() }
                        )
                        DropdownMenuItem(
                            text = { Text("🎵 Musique et audio") },
                            onClick = { menuOpen = false; showSettingsDialog = true }
                        )
                    }
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

/** Round icon-only action button for the right ribbon. */
@Composable
private fun RoundAction(
    glyph: String,
    enabled: Boolean,
    container: Color,
    size: androidx.compose.ui.unit.Dp,
    glyphSize: androidx.compose.ui.unit.TextUnit,
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            fontSize = glyphSize,
            fontWeight = FontWeight.Bold,
            color = if (enabled) textColor else Color(0x88FFFFFF)
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
    timerFraction: Float? = null,
    secondsLeft: Int? = null,
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
        Box(modifier = Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            if (timerFraction != null) {
                val ringColor = when {
                    timerFraction > 0.5f -> Color(0xFF69F0AE)
                    timerFraction > 0.2f -> Color(0xFFFFD54F)
                    else -> Color(0xFFFF5252)
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 3.dp.toPx()
                    val tl = Offset(stroke / 2, stroke / 2)
                    val sz = Size(size.width - stroke, size.height - stroke)
                    drawArc(Color(0x33FFFFFF), 0f, 360f, false, tl, sz, style = Stroke(stroke))
                    drawArc(ringColor, -90f, 360f * timerFraction, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            if (isSpeaking) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(VadSpeakingHalo.copy(alpha = 0.35f))
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF5D4037), Color(0xFF3E2723))))
                    .border(if (isActiveTurn || isSpeaking) 2.5.dp else 1.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                com.rummikub.app.ui.account.AvatarContent(player.avatar, player.name, 12.sp)
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
            text = if (secondsLeft != null) "${player.tileCount} tuiles · ${secondsLeft}s" else "${player.tileCount} tuiles",
            color = if (player.isConnected) Color(0xFFD7CCC8) else StatusReconnecting,
            fontSize = 8.sp,
            maxLines = 1
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
