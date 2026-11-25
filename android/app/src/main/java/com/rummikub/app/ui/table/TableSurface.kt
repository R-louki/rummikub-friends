package com.rummikub.app.ui.table

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rummikub.app.game.DropTarget
import com.rummikub.app.ui.components.TileFace
import com.rummikub.app.ui.components.TileMark
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.engine.BoardValidator
import com.rummikub.core.engine.layout.LayoutTarget
import com.rummikub.core.engine.layout.RackLayouter
import com.rummikub.core.engine.layout.TableLayouter
import com.rummikub.core.engine.layout.TablePreview
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.Tile
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Tuile tenue par un doigt : tuile principale, éventuel lot sélectionné, position de départ. */
private class DragInfo(
    val ids: List<String>,
    val primary: String,
    val fromRack: Boolean,
    val startCenter: Offset,
    val downPos: Offset,
    val tileW: Float,
    val tileH: Float,
)

private enum class MeldKindStatus { VALID, INCOMPLETE, INVALID }

private val ValidGreen = Color(0xFF69F0AE)
private val WarnAmber = Color(0xFFFFC107)
private val InvalidRed = Color(0xFFFF5252)
private val DropCyan = Color(0xFF00E5FF)

/**
 * La table et le chevalet sur une seule surface.
 *
 * Toutes les tuiles sont de vrais éléments animés, positionnés par le moteur de disposition :
 * elles glissent à leur place quand quelque chose change, volent du chevalet à la table, et se
 * poussent pour ouvrir un trou là où la tuile tenue va tomber. Le glisser est géré ici, sans le
 * glisser-déposer du système, pour un rendu net et un aperçu en direct.
 */
@Composable
fun TableSurface(
    melds: List<MeldSet>,
    rackTiles: List<Tile>,
    isMyTurn: Boolean,
    hasMadeInitialMeld: Boolean,
    initialThreshold: Int,
    newMeldPoints: Int,
    newTileIds: Set<String>,
    selectedIds: List<String>,
    placedByOthers: Set<String>,
    movedByOthers: Set<String>,
    drawnTileId: String?,
    emptyText: String,
    onToggleSelect: (Tile) -> Unit,
    onTapTarget: (DropTarget) -> Unit,
    onDoubleTap: (Tile) -> Unit,
    onSelectChain: (List<String>) -> Unit,
    onDrop: (ids: List<String>, primary: String, target: DropTarget, rackIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    rackTools: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val view = LocalView.current
        val dp = density.density
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val measurer = rememberTextMeasurer(cacheSize = 64)

        // ------------------------------------------------------------------ état du glisser
        var drag by remember { mutableStateOf<DragInfo?>(null) }
        var dragPos by remember { mutableStateOf(Offset.Zero) }
        var scrollY by remember { mutableFloatStateOf(0f) }
        val blend by animateFloatAsState(if (drag != null) 1f else 0f, tween(150), label = "drag_blend")
        val dragIds = drag?.ids?.toSet() ?: emptySet()

        // ------------------------------------------------------------------ chevalet
        val toolsW = 88f * dp
        val rackX0 = 6f * dp
        val rackW = w - toolsW - rackX0
        val rackBudget = min(h * 0.42f, 210f * dp)
        val rackIds = rackTiles.map { it.id }
        val rackTileW = remember(rackIds.size, rackW, rackBudget) {
            RackLayouter.fitTileWidth(max(rackIds.size, 1), rackW, rackBudget, 24f * dp, 62f * dp)
        }
        val rackSizing = RackLayouter.layout(rackIds, rackW, rackBudget, rackTileW)
        val rackTop = 4f * dp
        val rackH = (rackSizing.contentHeight + rackTop + 6f * dp).coerceIn(66f * dp, rackBudget + 12f * dp)
        val boardH = h - rackH
        val rackY0 = boardH

        // ------------------------------------------------------------------ plateau
        val pad = 6f * dp
        val boardW = w - 2 * pad
        val boardAreaH = boardH - 2 * pad
        val meldSizes = melds.map { it.tiles.size }
        val boardTileW = remember(meldSizes, boardW, boardAreaH) {
            TableLayouter.fitTileWidth(meldSizes.map { n -> List(n) { "$it" } }, boardW, boardAreaH, 22f * dp, 66f * dp)
        }
        val shownMelds = melds.map { m -> m.tiles.filter { it.id !in dragIds }.map { it.id } }
        val boardBase = TableLayouter.layout(shownMelds, boardW, boardAreaH, boardTileW)
        val maxScroll = max(0f, boardBase.contentHeight - boardAreaH)
        if (scrollY > maxScroll) scrollY = maxScroll

        val shownRackIds = rackIds.filter { it !in dragIds }
        val rackBase = RackLayouter.layout(shownRackIds, rackW, rackBudget, rackTileW)

        // ------------------------------------------------------------------ cible du dépôt (calculée à la volée)
        val geo = rememberUpdatedState(
            Geo(boardBase, rackBase, pad, scrollY, boardAreaH, rackX0, rackY0 + rackTop, rackY0, boardTileW, newTileIds, isMyTurn),
        )
        val dragLift = (drag?.tileH ?: 0f) * 0.8f
        val currentDragLift = rememberUpdatedState(dragLift)
        val target by remember {
            derivedStateOf {
                val d = drag
                if (d == null) null else {
                    val g = geo.value
                    val c = Offset(dragPos.x, dragPos.y - currentDragLift.value)
                    if (c.y >= g.rackY - 4f * dp) {
                        LayoutTarget.Rack(RackLayouter.hitIndex(g.rack, c.x - g.rackX, c.y - g.rackOriginY))
                    } else if (g.isMyTurn) {
                        TableLayouter.hitTest(g.board, c.x - g.pad, c.y - g.pad + g.scrollY, g.boardAreaH)
                    } else LayoutTarget.None
                }
            }
        }
        val canReturnToRack = drag?.let { d -> d.fromRack || d.ids.all { it in newTileIds } } ?: true
        val rawTarget: LayoutTarget? = target
        val effectiveTarget: LayoutTarget =
            if (rawTarget is LayoutTarget.Rack && !canReturnToRack) LayoutTarget.None else (rawTarget ?: LayoutTarget.None)

        var lastHaptic by remember { mutableStateOf<LayoutTarget?>(null) }
        LaunchedEffect(effectiveTarget) {
            if (drag != null && effectiveTarget != lastHaptic && effectiveTarget != LayoutTarget.None) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            lastHaptic = effectiveTarget
        }

        // ------------------------------------------------------------------ dispositions affichées (avec trou d'insertion)
        val count = drag?.ids?.size ?: 1
        val boardShown = when (val t = effectiveTarget) {
            is LayoutTarget.Meld -> TableLayouter.layout(shownMelds, boardW, boardAreaH, boardTileW, TablePreview(t.index, t.at, count))
            LayoutTarget.NewMeld -> TableLayouter.layout(shownMelds, boardW, boardAreaH, boardTileW, TablePreview(null, 0, count))
            else -> boardBase
        }
        val rackShown = (effectiveTarget as? LayoutTarget.Rack)?.let {
            RackLayouter.layout(shownRackIds, rackW, rackBudget, rackTileW, it.index.coerceIn(0, shownRackIds.size), count)
        } ?: rackBase

        // ------------------------------------------------------------------ rectangles cibles de chaque tuile
        val boardOffset = Offset(pad, pad - scrollY)
        val rackOffset = Offset(rackX0, rackY0 + rackTop)
        val targets = HashMap<String, Rect>()
        val onBoardIds = HashSet<String>()
        for (frame in boardShown.melds) for ((id, b) in frame.tiles) {
            targets[id] = Rect(b.x + boardOffset.x, b.y + boardOffset.y, b.right + boardOffset.x, b.bottom + boardOffset.y)
            onBoardIds += id
        }
        for ((id, b) in rackShown.slots) {
            targets[id] = Rect(b.x + rackOffset.x, b.y + rackOffset.y, b.right + rackOffset.x, b.bottom + rackOffset.y)
        }

        val allTiles = HashMap<String, Tile>()
        melds.forEach { m -> m.tiles.forEach { allTiles[it.id] = it } }
        rackTiles.forEach { allTiles[it.id] = it }

        // tuiles apparues après le premier affichage : elles arrivent en volant
        val known = remember { HashSet<String>().apply { addAll(allTiles.keys) } }
        val spawnOrder = HashMap<String, Int>()
        var spawnIdx = 0
        for (id in allTiles.keys.sorted()) if (id !in known) spawnOrder[id] = spawnIdx++
        val spawnRackFrom = Rect(w - toolsW * 0.6f, -boardTileW, w - toolsW * 0.6f + rackTileW, -boardTileW + rackTileW * 1.36f)
        val spawnBoardFrom = Rect(-boardTileW, h * 0.25f, 0f, h * 0.25f + boardTileW * 1.36f)
        LaunchedEffect(allTiles.keys) { delay(50); known.addAll(allTiles.keys) }

        val selected = selectedIds.toSet()
        val hasSelection = selected.isNotEmpty()
        val tilesById = allTiles

        // ------------------------------------------------------------------ gestes
        val tapState = remember { LastTap() }
        val ctx = rememberUpdatedState(
            GestureCtx(
                boardBase, rackBase, pad, scrollY, rackX0, rackY0 + rackTop, rackY0, boardAreaH, maxScroll,
                toolsW, w, tilesById, isMyTurn, selectedIds, boardTileW, rackTileW,
            ),
        )
        val currentSelect = rememberUpdatedState(onToggleSelect)
        val currentTapTarget = rememberUpdatedState(onTapTarget)
        val currentDouble = rememberUpdatedState(onDoubleTap)
        val currentChain = rememberUpdatedState(onSelectChain)
        val currentDrop = rememberUpdatedState(onDrop)
        val currentMelds = rememberUpdatedState(melds)
        val currentTarget = rememberUpdatedState(effectiveTarget)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = ctx.value
                        // les boutons du chevalet gèrent leurs propres touchers
                        if (down.position.y >= g.rackY && down.position.x >= g.w - g.toolsW) return@awaitEachGesture
                        val hit = g.tileAt(down.position)
                        val canDrag = hit != null && (hit.fromRack || g.isMyTurn)
                        var dragging = false
                        var held = false
                        var scrolling = false
                        val deadline = System.currentTimeMillis() + viewConfiguration.longPressTimeoutMillis
                        while (true) {
                            val event = if (held || hit == null) {
                                awaitPointerEvent()
                            } else {
                                withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) { awaitPointerEvent() }
                            }
                            if (event == null) {
                                held = true
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                currentChain.value(g.chainFrom(hit!!, currentMelds.value))
                                continue
                            }
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                if (dragging) {
                                    val d = drag
                                    if (d != null) {
                                        val t = currentTarget.value
                                        drag = null
                                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                        dispatchDrop(d, t, currentMelds.value, currentDrop.value)
                                    }
                                } else if (!scrolling && !held) {
                                    if (hit != null) {
                                        val now = System.currentTimeMillis()
                                        if (tapState.id == hit.tile.id && now - tapState.time < 320) {
                                            tapState.id = null
                                            currentDouble.value(hit.tile)
                                        } else {
                                            tapState.id = hit.tile.id
                                            tapState.time = now
                                            tapForTile(hit, g, currentTapTarget.value, currentSelect.value, currentMelds.value)
                                        }
                                    } else {
                                        tapForBackground(down.position, g, currentTapTarget.value)
                                    }
                                }
                                break
                            }
                            val moved = ch.position - down.position
                            if (!dragging && !scrolling && moved.getDistance() > slop * com.rummikub.app.ui.dnd.DragSettings.slopFactor) {
                                if (hit != null && canDrag) {
                                    dragging = true
                                    val ids = if (hit.tile.id in g.selectedIds && g.selectedIds.size > 1) {
                                        listOf(hit.tile.id) + g.selectedIds.filter { it != hit.tile.id }
                                    } else listOf(hit.tile.id)
                                    val center = Offset(hit.box.cx, hit.box.cy)
                                    dragPos = ch.position
                                    drag = DragInfo(ids, hit.tile.id, hit.fromRack, center, down.position, hit.box.w, hit.box.h)
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                } else if (hit == null && g.maxScroll > 0f && down.position.y < g.rackY) {
                                    scrolling = true
                                }
                            }
                            if (dragging) {
                                ch.consume()
                                dragPos = ch.position
                            } else if (scrolling) {
                                ch.consume()
                                scrollY = (scrollY - ch.positionChange().y).coerceIn(0f, g.maxScroll)
                            }
                        }
                        // sécurité : un geste interrompu ne laisse jamais une tuile en l'air
                        if (drag != null && !dragging) drag = null
                        if (dragging && drag != null) drag = null
                    }
                },
        ) {
            val lift = currentDragLift

            // ---------------------------------------------------------------- fond de la table
            val highlightBoard = hasSelection || (drag != null && effectiveTarget !is LayoutTarget.Rack && effectiveTarget != LayoutTarget.None)
            Canvas(
                Modifier
                    .offset { IntOffset(pad.roundToInt(), pad.roundToInt()) }
                    .size(with(density) { boardW.toDp() }, with(density) { boardAreaH.toDp() }),
            ) {
                drawRoundRect(Color(0x26000000), cornerRadius = CornerRadius(14f * dp))
                drawRoundRect(
                    color = if (highlightBoard && isMyTurn) DropCyan.copy(alpha = 0.55f) else Color(0x30FFD54F),
                    cornerRadius = CornerRadius(14f * dp),
                    style = Stroke(width = 2f * dp),
                )
            }

            if (melds.isEmpty() && drag == null) {
                Text(
                    text = emptyText,
                    color = Color(0x99FFFFFF),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset { IntOffset(0, (boardH * 0.38f).roundToInt()) }
                        .padding(horizontal = 40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            // ---------------------------------------------------------------- cadres des combinaisons
            val hoverMeld = (effectiveTarget as? LayoutTarget.Meld)?.index
            for (frame in boardShown.melds) {
                val meld = melds.getOrNull(frame.index) ?: continue
                key(meld.id) {
                    val status = remember(meld.tiles) { meldStatus(meld) }
                    MeldFrameView(
                        box = Rect(
                            frame.box.x + boardOffset.x, frame.box.y + boardOffset.y,
                            frame.box.right + boardOffset.x, frame.box.bottom + boardOffset.y,
                        ),
                        status = status.first,
                        points = status.second,
                        size = meld.tiles.size,
                        hovered = hoverMeld == frame.index,
                        headerH = frame.header.h,
                    )
                }
            }

            // ---------------------------------------------------------------- emplacements fantômes
            for (gb in boardShown.ghosts) {
                GhostSlot(Rect(gb.x + boardOffset.x, gb.y + boardOffset.y, gb.right + boardOffset.x, gb.bottom + boardOffset.y), zi = 0.8f)
            }

            // ---------------------------------------------------------------- chevalet (bois)
            Box(
                Modifier
                    .zIndex(1.5f)
                    .offset { IntOffset(0, rackY0.roundToInt()) }
                    .size(with(density) { w.toDp() }, with(density) { rackH.toDp() })
                    .graphicsLayer {
                        shadowElevation = 14f * dp
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                        clip = true
                    }
                    .background(Brush.verticalGradient(listOf(WoodBorder, WoodDarkBrown, Color(0xFF190C06))))
                    .drawBehind {
                        drawRoundRect(
                            color = if (drag != null && effectiveTarget is LayoutTarget.Rack) DropCyan.copy(alpha = 0.55f) else Color(0x55FFFFFF),
                            topLeft = Offset(2f * dp, 2f * dp),
                            size = Size(size.width - 4f * dp, size.height),
                            cornerRadius = CornerRadius(16f * dp),
                            style = Stroke(width = 1.5f * dp),
                        )
                    },
            )
            for (gb in rackShown.ghosts) {
                GhostSlot(Rect(gb.x + rackOffset.x, gb.y + rackOffset.y, gb.right + rackOffset.x, gb.bottom + rackOffset.y), zi = 1.6f)
            }

            // ---------------------------------------------------------------- les tuiles
            for ((id, tile) in allTiles) {
                val dragged = id in dragIds
                val tr = targets[id]
                if (tr == null && !dragged) continue
                key(id) {
                    val mark = when {
                        id in selected -> TileMark.SELECTED
                        id == drawnTileId -> TileMark.SELECTED
                        id in newTileIds && id in onBoardIds -> TileMark.PLACED
                        id in placedByOthers -> TileMark.OPPONENT_PLACED
                        id in movedByOthers -> TileMark.OPPONENT_MOVED
                        else -> TileMark.NONE
                    }
                    val spawn = spawnOrder[id]
                    val inRack = id !in onBoardIds
                    FlyingTile(
                        tile = tile,
                        target = tr,
                        spawnFrom = if (spawn != null) (if (inRack) spawnRackFrom else spawnBoardFrom) else null,
                        spawnDelay = (spawn ?: 0) * 45L,
                        baseZ = if (inRack) 2f else 1f,
                        mark = mark,
                        measurer = measurer,
                        dragRect = if (dragged) {
                            {
                                val d = drag
                                if (d == null) null else {
                                    val k = d.ids.indexOf(id).coerceAtLeast(0)
                                    val scale = 1.16f
                                    val tw = d.tileW * scale
                                    val th = d.tileH * scale
                                    val finger = Offset(dragPos.x, dragPos.y - lift.value)
                                    val follow = d.startCenter + (dragPos - d.downPos)
                                    val c = Offset(
                                        follow.x + (finger.x - follow.x) * blend,
                                        follow.y + (finger.y - follow.y) * blend,
                                    )
                                    // les tuiles d'un lot sont empilées en éventail derrière la tuile principale
                                    val fan = if (id == d.primary) 0 else k
                                    val cx = c.x + fan * 0.16f * tw
                                    val cy = c.y + fan * 0.07f * th
                                    Rect(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2)
                                }
                            }
                        } else null,
                        stackIndex = if (dragged) drag?.ids?.indexOf(id) ?: 0 else 0,
                    )
                }
            }

            // ---------------------------------------------------------------- badge « ×N » d'un lot
            val d = drag
            if (d != null && d.ids.size > 1) {
                Text(
                    "×${d.ids.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .zIndex(12f)
                        .offset {
                            IntOffset(
                                (dragPos.x + d.tileW * 0.55f).roundToInt(),
                                (dragPos.y - lift.value - d.tileH * 0.75f).roundToInt(),
                            )
                        }
                        .background(Color(0xFF00838F), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }

            // ---------------------------------------------------------------- outils du chevalet
            Box(
                Modifier
                    .zIndex(3f)
                    .align(Alignment.BottomEnd)
                    .size(with(density) { toolsW.toDp() }, with(density) { rackH.toDp() }),
            ) { rackTools() }

            // ---------------------------------------------------------------- progression de la première pose
            if (isMyTurn && !hasMadeInitialMeld && initialThreshold > 0) {
                InitialMeldChip(
                    points = newMeldPoints,
                    threshold = initialThreshold,
                    modifier = Modifier
                        .zIndex(4f)
                        .align(Alignment.TopStart)
                        .offset { IntOffset((pad * 2).roundToInt(), (pad * 2).roundToInt()) },
                )
            }
        }
    }
}

// ====================================================================== géométrie et gestes

private class LastTap {
    var id: String? = null
    var time: Long = 0L
}

private class Geo(
    val board: com.rummikub.core.engine.layout.TableLayout,
    val rack: com.rummikub.core.engine.layout.RackLayout,
    val pad: Float,
    val scrollY: Float,
    val boardAreaH: Float,
    val rackX: Float,
    val rackOriginY: Float,
    val rackY: Float,
    val boardTileW: Float,
    val newTileIds: Set<String>,
    val isMyTurn: Boolean,
)

private class TileHit(val tile: Tile, val fromRack: Boolean, val box: com.rummikub.core.engine.layout.Box, val meldId: String?, val indexInMeld: Int)

private class GestureCtx(
    val board: com.rummikub.core.engine.layout.TableLayout,
    val rack: com.rummikub.core.engine.layout.RackLayout,
    val pad: Float,
    val scrollY: Float,
    val rackX: Float,
    val rackOriginY: Float,
    val rackY: Float,
    val boardAreaH: Float,
    val maxScroll: Float,
    val toolsW: Float,
    val w: Float,
    val tiles: Map<String, Tile>,
    val isMyTurn: Boolean,
    val selectedIds: List<String>,
    val boardTileW: Float,
    val rackTileW: Float,
) {
    fun tileAt(p: Offset): TileHit? {
        if (p.y >= rackY) {
            for ((id, b) in rack.slots) {
                val bb = com.rummikub.core.engine.layout.Box(b.x + rackX, b.y + rackOriginY, b.w, b.h)
                if (bb.inflate(2f, 2f).contains(p.x, p.y)) return tiles[id]?.let { TileHit(it, true, bb, null, -1) }
            }
            return null
        }
        for (frame in board.melds) {
            for ((i, pair) in frame.tiles.withIndex()) {
                val (id, b) = pair
                val bb = com.rummikub.core.engine.layout.Box(b.x + pad, b.y + pad - scrollY, b.w, b.h)
                if (bb.inflate(2f, 2f).contains(p.x, p.y)) {
                    return tiles[id]?.let { TileHit(it, false, bb, "${frame.index}", i) }
                }
            }
        }
        return null
    }

    /** Appui long : la tuile et celles qui la suivent tant qu'elles continuent une combinaison. */
    fun chainFrom(hit: TileHit, melds: List<MeldSet>): List<String> {
        val src: List<Tile> = if (hit.fromRack) {
            val order = rack.slots.map { it.first }
            val i = order.indexOf(hit.tile.id)
            // les tuiles voisines sur la même ligne
            val row = rack.rowStarts.indexOfLast { it <= i }
            val end = if (row + 1 < rack.rowStarts.size) rack.rowStarts[row + 1] else order.size
            order.subList(i, end).mapNotNull { tiles[it] }
        } else {
            val meld = melds.firstOrNull { m -> m.tiles.any { it.id == hit.tile.id } }
            val i = meld?.tiles?.indexOfFirst { it.id == hit.tile.id } ?: -1
            if (meld == null || i < 0) listOf(hit.tile) else meld.tiles.drop(i)
        }
        return chainOf(src.first(), src.drop(1))
    }
}

/** Même règle qu'avant : la tuile tenue et les suivantes tant qu'elles prolongent une combinaison. */
private fun chainOf(start: Tile, followers: List<Tile>): List<String> {
    val out = arrayListOf(start.id)
    var prev = start
    var mode = 0
    for (t in followers) {
        val run = !prev.isJoker && !t.isJoker && prev.color == t.color && t.number == prev.number + 1
        val group = !prev.isJoker && !t.isJoker && prev.number == t.number && prev.color != t.color
        val ok = when {
            prev.isJoker || t.isJoker -> true
            mode == 0 -> run || group
            mode == 1 -> run
            else -> group
        }
        if (!ok) break
        if (mode == 0 && !prev.isJoker && !t.isJoker) mode = if (run) 1 else 2
        out += t.id
        prev = t
    }
    return out
}

private fun tapForTile(
    hit: TileHit,
    g: GestureCtx,
    onTapTarget: (DropTarget) -> Unit,
    onToggle: (Tile) -> Unit,
    melds: List<MeldSet>,
) {
    val hasSelection = g.selectedIds.isNotEmpty()
    val isSelected = hit.tile.id in g.selectedIds
    if (!hit.fromRack && hasSelection && !isSelected) {
        // une tuile est choisie : toucher une tuile de la table l'insère juste avant
        val meld = melds.firstOrNull { m -> m.tiles.any { it.id == hit.tile.id } }
        if (meld != null) {
            onTapTarget(DropTarget.MeldPosition(meld.id, meld.tiles.indexOfFirst { it.id == hit.tile.id }))
            return
        }
    }
    onToggle(hit.tile)
}

private fun tapForBackground(p: Offset, g: GestureCtx, onTapTarget: (DropTarget) -> Unit) {
    if (g.selectedIds.isEmpty()) return
    if (p.y >= g.rackY) {
        onTapTarget(DropTarget.Rack)
        return
    }
    onTapTarget(DropTarget.NewMeld)
}

private fun dispatchDrop(
    d: DragInfo,
    target: LayoutTarget,
    melds: List<MeldSet>,
    onDrop: (List<String>, String, DropTarget, Int) -> Unit,
) {
    when (target) {
        is LayoutTarget.Rack -> onDrop(d.ids, d.primary, DropTarget.Rack, target.index)
        LayoutTarget.NewMeld -> onDrop(d.ids, d.primary, DropTarget.NewMeld, 0)
        is LayoutTarget.Meld -> {
            val meld = melds.getOrNull(target.index) ?: return
            // l'index affiché ignore la tuile tenue ; le contrôleur compte avec elle dans la même combinaison
            val sourceIndex = meld.tiles.indexOfFirst { it.id == d.primary }
            val at = if (sourceIndex >= 0 && target.at > sourceIndex) target.at + 1 else target.at
            onDrop(d.ids, d.primary, DropTarget.MeldPosition(meld.id, at), 0)
        }
        LayoutTarget.None -> Unit
    }
}

private fun meldStatus(meld: MeldSet): Pair<MeldKindStatus, Int> {
    if (meld.tiles.size < 3) return MeldKindStatus.INCOMPLETE to 0
    val r = BoardValidator.validateMeld(meld)
    return if (r.isValid) MeldKindStatus.VALID to r.points else MeldKindStatus.INVALID to 0
}

// ====================================================================== éléments animés

@Composable
private fun FlyingTile(
    tile: Tile,
    target: Rect?,
    spawnFrom: Rect?,
    spawnDelay: Long,
    baseZ: Float,
    mark: TileMark,
    measurer: androidx.compose.ui.text.TextMeasurer,
    dragRect: (() -> Rect?)?,
    stackIndex: Int,
) {
    val density = LocalDensity.current
    val start = spawnFrom ?: target ?: Rect(0f, 0f, 10f, 14f)
    val anim = remember { Animatable(start, Rect.VectorConverter, Rect.VisibilityThreshold) }
    val alpha = remember { Animatable(if (spawnFrom != null) 0f else 1f) }
    val dragging = dragRect != null
    // dernière position pendant le glisser : l'animation de dépôt repart de là (pas un état, écrit en mise en page)
    val lastDragged = remember { arrayOfNulls<Rect>(1) }

    LaunchedEffect(target, dragging) {
        if (dragging || target == null) return@LaunchedEffect
        lastDragged[0]?.let { anim.snapTo(it); lastDragged[0] = null }
        if (spawnFrom != null && anim.value == spawnFrom) delay(spawnDelay)
        anim.animateTo(target, spring(dampingRatio = 0.74f, stiffness = 360f, visibilityThreshold = Rect.VisibilityThreshold))
    }
    LaunchedEffect(Unit) {
        if (spawnFrom != null) {
            delay(spawnDelay)
            alpha.animateTo(1f, tween(160))
        }
    }

    val shown = target ?: anim.value
    val layoutW = with(density) { shown.width.toDp() }
    val layoutH = with(density) { shown.height.toDp() }
    val z = when {
        dragging -> 10f - stackIndex * 0.01f
        anim.isRunning -> 6f
        else -> baseZ
    }
    Box(
        Modifier
            .zIndex(z)
            .offset {
                val r = dragRect?.invoke()?.also { lastDragged[0] = it } ?: anim.value
                IntOffset(r.left.roundToInt(), r.top.roundToInt())
            }
            .size(layoutW, layoutH)
            .graphicsLayer {
                val r = dragRect?.invoke() ?: anim.value
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = if (shown.width > 0f) r.width / shown.width else 1f
                scaleY = if (shown.height > 0f) r.height / shown.height else 1f
                this.alpha = alpha.value
                if (dragRect != null) {
                    shadowElevation = 22f * density.density
                    shape = RoundedCornerShape(percent = 15)
                    clip = false
                    rotationZ = if (stackIndex == 0) -2.5f else (stackIndex * 2.5f)
                }
            },
    ) {
        TileFace(tile = tile, modifier = Modifier.fillMaxSize(), mark = mark, measurer = measurer)
    }
}

@Composable
private fun MeldFrameView(
    box: Rect,
    status: MeldKindStatus,
    points: Int,
    size: Int,
    hovered: Boolean,
    headerH: Float,
) {
    val density = LocalDensity.current
    val anim = remember { Animatable(box, Rect.VectorConverter, Rect.VisibilityThreshold) }
    LaunchedEffect(box) {
        anim.animateTo(box, spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = Rect.VisibilityThreshold))
    }
    val accent = when (status) {
        MeldKindStatus.VALID -> ValidGreen
        MeldKindStatus.INCOMPLETE -> WarnAmber
        MeldKindStatus.INVALID -> InvalidRed
    }
    val border by animateColorAsStateCompat(if (hovered) DropCyan else accent.copy(alpha = if (status == MeldKindStatus.VALID) 0.45f else 0.85f))
    Box(
        Modifier
            .zIndex(0.5f)
            .offset { IntOffset(anim.value.left.roundToInt(), anim.value.top.roundToInt()) }
            .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = if (box.width > 0f) anim.value.width / box.width else 1f
                scaleY = if (box.height > 0f) anim.value.height / box.height else 1f
            }
            .drawBehind {
                val cr = CornerRadius(10f * density.density)
                drawRoundRect(
                    if (hovered) DropCyan.copy(alpha = 0.18f) else Color(0x14FFFFFF),
                    cornerRadius = cr,
                )
                drawRoundRect(border, cornerRadius = cr, style = Stroke(width = 1.6f * density.density))
            },
    ) {
        val chip = when (status) {
            MeldKindStatus.VALID -> "✓ $points"
            MeldKindStatus.INCOMPLETE -> "$size/3"
            MeldKindStatus.INVALID -> "✕"
        }
        Text(
            text = chip,
            color = if (status == MeldKindStatus.INCOMPLETE) Color(0xFF3B2A00) else Color(0xFF062414),
            fontSize = (headerH / density.density * 0.62f).coerceIn(8f, 13f).sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 4.dp, y = 2.dp)
                .background(accent.copy(alpha = 0.95f), RoundedCornerShape(8.dp))
                .padding(horizontal = 5.dp, vertical = 0.dp),
        )
    }
}

@Composable
private fun animateColorAsStateCompat(target: Color) =
    androidx.compose.animation.animateColorAsState(target, tween(200), label = "frame_color")

@Composable
private fun GhostSlot(box: Rect, zi: Float) {
    val density = LocalDensity.current
    val anim = remember { Animatable(box, Rect.VectorConverter, Rect.VisibilityThreshold) }
    LaunchedEffect(box) {
        anim.animateTo(box, spring(dampingRatio = 0.85f, stiffness = 600f, visibilityThreshold = Rect.VisibilityThreshold))
    }
    Box(
        Modifier
            .zIndex(zi)
            .offset { IntOffset(anim.value.left.roundToInt(), anim.value.top.roundToInt()) }
            .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
            .drawBehind {
                val d = density.density
                drawRoundRect(
                    DropCyan.copy(alpha = 0.16f),
                    cornerRadius = CornerRadius(8f * d),
                )
                drawRoundRect(
                    DropCyan.copy(alpha = 0.9f),
                    cornerRadius = CornerRadius(8f * d),
                    style = Stroke(width = 2f * d, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * d, 7f * d))),
                )
            },
    )
}

@Composable
private fun InitialMeldChip(points: Int, threshold: Int, modifier: Modifier = Modifier) {
    val ok = points >= threshold
    val fraction = (points.toFloat() / threshold).coerceIn(0f, 1f)
    val color = if (ok) ValidGreen else WarnAmber
    Box(
        modifier
            .background(Color(0xCC000000), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        androidx.compose.foundation.layout.Column {
            Text(
                "Première pose  $points / $threshold",
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .size(width = 120.dp, height = 4.dp)
                    .background(Color(0x44FFFFFF), RoundedCornerShape(2.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { transformOrigin = TransformOrigin(0f, 0.5f); scaleX = fraction }
                        .background(color, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}
