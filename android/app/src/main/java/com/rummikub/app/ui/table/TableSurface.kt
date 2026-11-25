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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
    /** Dispositions au moment de la prise : rien ne bouge autour de la tuile tenue. */
    val frozenBoard: com.rummikub.core.engine.layout.TableLayout,
    val frozenRack: com.rummikub.core.engine.layout.RackLayout,
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
        val rackBudget = min(h * 0.34f, 150f * dp)
        val rackIds = rackTiles.map { it.id }
        val rackTileW = remember(rackIds.size, rackW, rackBudget) {
            RackLayouter.fitTileWidth(max(rackIds.size, 1), rackW, rackBudget, 22f * dp, 42f * dp)
        }
        val rackSizing = RackLayouter.layout(rackIds, rackW, rackBudget, rackTileW)
        val rackTop = 0f
        val rackH = rackSizing.contentHeight + 12f * dp
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
        val shownMelds = melds.map { m -> m.tiles.map { it.id } }
        // pendant un geste, la disposition est celle de la prise : rien ne bouge, la cible ne se dérobe jamais
        val boardBase = drag?.frozenBoard ?: TableLayouter.layout(shownMelds, boardW, boardAreaH, boardTileW)
        val maxScroll = max(0f, boardBase.contentHeight - boardAreaH)
        if (scrollY > maxScroll) scrollY = maxScroll

        val rackBase = drag?.frozenRack ?: RackLayouter.layout(rackIds, rackW, rackBudget, rackTileW)

        // ------------------------------------------------------------------ cible du dépôt (calculée à la volée)
        val geo = rememberUpdatedState(
            Geo(boardBase, rackBase, pad, scrollY, boardAreaH, rackX0, rackY0 + rackTop, rackY0, boardTileW, newTileIds, isMyTurn),
        )
        val dragLift = (drag?.tileH ?: 0f) * 0.55f
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

        // ------------------------------------------------------------------ dispositions affichées : figées pendant un geste
        val boardShown = boardBase
        val rackShown = rackBase

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

        val rackTierOf = HashMap<String, Int>()
        rackShown.slots.forEachIndexed { i, (id, _) -> rackTierOf[id] = rackShown.rowStarts.indexOfLast { it <= i }.coerceAtLeast(0) }

        val allTiles = HashMap<String, Tile>()
        melds.forEach { m -> m.tiles.forEach { allTiles[it.id] = it } }
        rackTiles.forEach { allTiles[it.id] = it }

        LaunchedEffect(allTiles.keys) {
            val d = drag
            if (d != null && d.ids.any { it !in allTiles.keys }) drag = null
        }

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
                        val hit = g.tileAt(down.position) ?: g.edgeAt(down.position)
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
                                // Sur la table, rester appuyé ne choisit plus les tuiles suivantes : on emporterait plusieurs
                                // tuiles sans l'avoir voulu et le pli se casserait. Le chevalet garde ce raccourci.
                                if (hit!!.blockIds != null) currentChain.value(hit.blockIds)
                                else if (hit.fromRack) currentChain.value(g.chainFrom(hit, currentMelds.value))
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
                                    if (hit != null && hit.blockIds != null) {
                                        // un toucher sur le bord d'une combinaison la sélectionne en entier
                                        currentChain.value(hit.blockIds)
                                    } else if (hit != null) {
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
                                    val ids = hit.blockIds ?: if (hit.tile.id in g.selectedIds && g.selectedIds.size > 1) {
                                        listOf(hit.tile.id) + g.selectedIds.filter { it != hit.tile.id }
                                    } else listOf(hit.tile.id)
                                    val center = Offset(hit.box.cx, hit.box.cy)
                                    dragPos = ch.position
                                    drag = DragInfo(ids, hit.tile.id, hit.fromRack, center, down.position, hit.box.w, hit.box.h, g.board, g.rack)
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

            // ---------------------------------------------------------------- fond : feutre texturé (pas de cadre)
            val highlightBoard = hasSelection || (drag != null && effectiveTarget !is LayoutTarget.Rack && effectiveTarget != LayoutTarget.None)
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val grain = feltGrain(size.width, size.height)
                        onDrawBehind {
                            // légère vignette puis grain du feutre
                            drawRect(
                                Brush.radialGradient(
                                    listOf(Color.Transparent, Color(0x33000000)),
                                    center = Offset(size.width / 2f, size.height * 0.45f),
                                    radius = size.maxDimension * 0.75f,
                                ),
                            )
                            for (g in grain) drawCircle(g.color, radius = g.r * dp, center = Offset(g.x, g.y))
                        }
                    },
            )

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

            // ---------------------------------------------------------------- combinaisons : un simple soulignement si besoin
            val hoverMeld = (effectiveTarget as? LayoutTarget.Meld)?.index
            for (frame in boardShown.melds) {
                val meld = melds.getOrNull(frame.index) ?: continue
                key(meld.id) {
                    val status = remember(meld.tiles) { meldStatus(meld) }
                    val hovered = hoverMeld == frame.index
                    // une combinaison invalide se voit ; une combinaison juste reste discrète (pas de total, pas de cadre)
                    if (hovered || status.first == MeldKindStatus.INVALID) {
                        MeldUnderline(
                            box = Rect(
                                frame.box.x + boardOffset.x, frame.box.y + boardOffset.y,
                                frame.box.right + boardOffset.x, frame.box.bottom + boardOffset.y,
                            ),
                            color = if (hovered) DropCyan else InvalidRed,
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- poignées : on peut saisir une combinaison par ses bords
            if (isMyTurn && drag == null) {
                for (frame in boardShown.melds) {
                    val first = frame.tiles.firstOrNull()?.second ?: continue
                    val last = frame.tiles.last().second
                    key("grip${melds.getOrNull(frame.index)?.id}") {
                        MeldGrips(
                            left = first.x + boardOffset.x,
                            right = last.right + boardOffset.x,
                            top = first.y + boardOffset.y,
                            height = first.h,
                            tileW = first.w,
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- origine de la tuile tenue + repère d'insertion
            drag?.let { d ->
                for (id in d.ids) targets[id]?.let { GhostSlot(it, zi = 0.8f, faint = true) }
                when (val t = effectiveTarget) {
                    is LayoutTarget.Meld -> {
                        val frame = boardShown.melds.firstOrNull { it.index == t.index }
                        if (frame != null && frame.tiles.isNotEmpty()) {
                            val tl = frame.tiles
                            val gap = boardTileW * 0.03f
                            val x = when {
                                t.at <= 0 -> tl.first().second.x - gap - boardTileW * 0.02f
                                t.at >= tl.size -> tl.last().second.right + gap + boardTileW * 0.02f
                                else -> (tl[t.at - 1].second.right + tl[t.at].second.x) / 2f
                            }
                            val ref = tl.first().second
                            CaretBar(Rect(x + boardOffset.x, ref.y + boardOffset.y - ref.h * 0.28f, x + boardOffset.x, ref.bottom + boardOffset.y + ref.h * 0.28f))
                        }
                    }
                    is LayoutTarget.Rack -> {
                        val sl = rackShown.slots
                        if (sl.isNotEmpty()) {
                            val gap = rackTileW * 0.04f
                            val i = t.index.coerceIn(0, sl.size)
                            val ref = (if (i < sl.size) sl[i] else sl.last()).second
                            val x = when {
                                i >= sl.size -> sl.last().second.right + gap
                                i == 0 || sl[i - 1].second.y != ref.y -> ref.x - gap
                                else -> (sl[i - 1].second.right + ref.x) / 2f
                            }
                            CaretBar(Rect(x + rackOffset.x, ref.y + rackOffset.y - ref.h * 0.2f, x + rackOffset.x, ref.bottom + rackOffset.y + ref.h * 0.1f))
                        }
                    }
                    LayoutTarget.NewMeld -> NewMeldTag(
                        Modifier
                            .zIndex(12f)
                            .offset {
                                IntOffset(
                                    (dragPos.x - 70f * dp).roundToInt(),
                                    (dragPos.y + d.tileH * 0.30f).roundToInt(),
                                )
                            },
                    )
                    LayoutTarget.None -> Unit
                }
            }

            // ---------------------------------------------------------------- emplacements fantômes
            for (gb in boardShown.ghosts) {
                GhostSlot(Rect(gb.x + boardOffset.x, gb.y + boardOffset.y, gb.right + boardOffset.x, gb.bottom + boardOffset.y), zi = 0.8f)
            }

            // ---------------------------------------------------------------- socle du chevalet : deux niveaux, bois clair
            val socleX = rackOffset.x + (rackW - rackSizing.panelWidth) / 2f
            val rackDropActive = drag != null && effectiveTarget is LayoutTarget.Rack
            RackSocle(
                x = socleX,
                y = rackOffset.y,
                w = rackSizing.panelWidth,
                h = rackSizing.contentHeight,
                tileH = rackSizing.tileH,
                tierTops = rackSizing.rowTops,
                highlight = rackDropActive,
            )
            RackLip(
                x = socleX,
                y = rackOffset.y,
                w = rackSizing.panelWidth,
                h = rackSizing.contentHeight,
                frontTop = (rackSizing.rowTops.lastOrNull() ?: 0f) + rackSizing.tileH * 0.80f,
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
                        baseZ = if (inRack) 2f + 0.1f * (rackTierOf[id] ?: 0) else 1f,
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
                        .offset {
                            IntOffset(
                                (rackOffset.x + rackW / 2f - 100f * dp).roundToInt(),
                                (rackOffset.y + rackSizing.contentHeight - 25f * dp).roundToInt(),
                            )
                        },
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

private class TileHit(
    val tile: Tile,
    val fromRack: Boolean,
    val box: com.rummikub.core.engine.layout.Box,
    val meldId: String?,
    val indexInMeld: Int,
    /** Non nul quand on a saisi une combinaison entière par son bord. */
    val blockIds: List<String>? = null,
)

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
            for ((id, b) in rack.slots.asReversed()) {
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

    /** Le bord d'une combinaison (juste avant sa première tuile ou après sa dernière) la saisit en entier. */
    fun edgeAt(p: Offset): TileHit? {
        if (!isMyTurn || p.y >= rackY) return null
        val cx = p.x - pad
        val cy = p.y - pad + scrollY
        val zone = boardTileW * 0.34f
        for (frame in board.melds) {
            val first = frame.tiles.firstOrNull() ?: continue
            val last = frame.tiles.last().second
            val top = first.second.y - first.second.h * 0.12f
            val bottom = first.second.bottom + first.second.h * 0.12f
            if (cy < top || cy > bottom) continue
            val left = cx >= first.second.x - zone && cx < first.second.x
            val right = cx > last.right && cx <= last.right + zone
            if (left || right) {
                val b = first.second
                val box = com.rummikub.core.engine.layout.Box(b.x + pad, b.y + pad - scrollY, b.w, b.h)
                val tile = tiles[first.first] ?: continue
                return TileHit(tile, false, box, null, 0, blockIds = frame.tiles.map { it.first })
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
        is LayoutTarget.Rack -> {
            // l'index visé compte toutes les tuiles du chevalet, y compris celles qu'on tient : on les retire du compte
            val order = d.frozenRack.slots.map { it.first }
            val idx = if (d.fromRack) {
                target.index - d.ids.count { id -> order.indexOf(id).let { it in 0 until target.index } }
            } else target.index
            onDrop(d.ids, d.primary, DropTarget.Rack, idx.coerceAtLeast(0))
        }
        LayoutTarget.NewMeld -> onDrop(d.ids, d.primary, DropTarget.NewMeld, 0)
        is LayoutTarget.Meld -> {
            val meld = melds.getOrNull(target.index) ?: return
            // déposer une combinaison entière sur elle-même ne change rien
            if (d.ids.size > 1 && meld.tiles.map { it.id }.toSet() == d.ids.toSet()) return
            // l'index vient de la disposition figée (tuile tenue comprise) : c'est celui qu'attend le contrôleur
            onDrop(d.ids, d.primary, DropTarget.MeldPosition(meld.id, target.at), 0)
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
private fun MeldUnderline(box: Rect, color: Color) {
    val density = LocalDensity.current
    val d = density.density
    val target = Rect(box.left, box.bottom - 1f * d, box.right, box.bottom + 4f * d)
    val anim = remember { Animatable(target, Rect.VectorConverter, Rect.VisibilityThreshold) }
    LaunchedEffect(target) {
        anim.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 420f, visibilityThreshold = Rect.VisibilityThreshold))
    }
    Box(
        Modifier
            .zIndex(0.5f)
            .offset { IntOffset(anim.value.left.roundToInt(), anim.value.top.roundToInt()) }
            .size(with(density) { target.width.toDp() }, with(density) { target.height.toDp() })
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = if (target.width > 0f) anim.value.width / target.width else 1f
            }
            .drawBehind {
                drawRoundRect(color.copy(alpha = 0.95f), cornerRadius = CornerRadius(3f * d))
                // petite lueur sous le trait
                drawRoundRect(color.copy(alpha = 0.25f), topLeft = Offset(0f, 2f * d), size = Size(size.width, size.height + 3f * d), cornerRadius = CornerRadius(4f * d))
            },
    )
}

/** Barre verticale lumineuse : l'endroit exact où la tuile va s'insérer. */
@Composable
private fun CaretBar(rect: Rect) {
    val density = LocalDensity.current
    val d = density.density
    val anim = remember { Animatable(rect, Rect.VectorConverter, Rect.VisibilityThreshold) }
    LaunchedEffect(rect) {
        anim.animateTo(rect, spring(dampingRatio = 0.8f, stiffness = 900f, visibilityThreshold = Rect.VisibilityThreshold))
    }
    val barW = 5f * d
    Box(
        Modifier
            .zIndex(11f)
            .offset { IntOffset((anim.value.left - barW * 2).roundToInt(), anim.value.top.roundToInt()) }
            .size(with(density) { (barW * 4).toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer { scaleY = if (rect.height > 0f) anim.value.height / rect.height else 1f; transformOrigin = TransformOrigin(0.5f, 0f) }
            .drawBehind {
                drawRoundRect(DropCyan.copy(alpha = 0.22f), Offset(0f, -3f * d), Size(size.width, size.height + 6f * d), CornerRadius(barW * 2))
                drawRoundRect(DropCyan, Offset(barW * 1.5f, 0f), Size(barW, size.height), CornerRadius(barW / 2))
            },
    )
}

/** Étiquette près du doigt : la tuile va former une nouvelle combinaison. */
@Composable
private fun NewMeldTag(modifier: Modifier) {
    Text(
        "+ nouvelle combinaison",
        color = Color(0xFF062414),
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .background(DropCyan, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Deux poignées discrètes, de part et d'autre d'une combinaison : on la saisit en entier par là. */
@Composable
private fun MeldGrips(left: Float, right: Float, top: Float, height: Float, tileW: Float) {
    val density = LocalDensity.current
    val d = density.density
    val zone = tileW * 0.34f
    Box(
        Modifier
            .zIndex(0.6f)
            .offset { IntOffset((left - zone).roundToInt(), top.roundToInt()) }
            .size(with(density) { (right - left + 2 * zone).toDp() }, with(density) { height.toDp() })
            .drawBehind {
                val w = 3.2f * d
                val h = height * 0.42f
                val y = (size.height - h) / 2f
                drawRoundRect(Color(0x55FFFFFF), Offset(zone * 0.45f - w / 2, y), Size(w, h), CornerRadius(w / 2))
                drawRoundRect(Color(0x55FFFFFF), Offset(size.width - zone * 0.45f - w / 2, y), Size(w, h), CornerRadius(w / 2))
            },
    )
}

private class FeltDot(val x: Float, val y: Float, val r: Float, val color: Color)

/** Grain du feutre : milliers de points quasi invisibles, tirés une fois pour toutes. */
private fun feltGrain(w: Float, h: Float): List<FeltDot> {
    val rnd = java.util.Random(42)
    val n = ((w * h) / 2600f).toInt().coerceIn(300, 6000)
    return List(n) {
        val light = rnd.nextBoolean()
        FeltDot(
            rnd.nextFloat() * w,
            rnd.nextFloat() * h,
            0.4f + rnd.nextFloat() * 0.5f,
            if (light) Color(0x0AFFFFFF) else Color(0x14000000),
        )
    }
}

private class GrainLine(val y: Float, val amp: Float, val phase: Float, val alpha: Float, val width: Float)

private val OakTop = Color(0xFFEBCB9A)
private val OakMid = Color(0xFFD3A56A)
private val OakBottom = Color(0xFFB98650)
private val OakDark = Color(0xFF8A5B2E)
private val OakDeep = Color(0xFF6A4322)

/**
 * Le socle : un vrai chevalet en bois clair à deux niveaux. On voit l'épaisseur du bois, le creux où
 * reposent les tuiles, les deux étagères et le veinage. Dessiné en vectoriel.
 */
@Composable
private fun RackSocle(x: Float, y: Float, w: Float, h: Float, tileH: Float, tierTops: List<Float>, highlight: Boolean) {
    val density = LocalDensity.current
    val d = density.density
    val thick = 9f * d
    Box(
        Modifier
            .zIndex(1.5f)
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { (h + thick + 12f * d).toDp() })
            .drawWithCache {
                val rnd = java.util.Random(7)
                val grain = List(46) { GrainLine(rnd.nextFloat() * h, 1f + rnd.nextFloat() * 3f, rnd.nextFloat() * 6f, 0.04f + rnd.nextFloat() * 0.07f, 0.6f + rnd.nextFloat() * 1.4f) }
                onDrawBehind {
                    val r = 18f * d
                    // ombre portée sur le feutre
                    for (i in 1..5) {
                        drawRoundRect(
                            Color.Black.copy(alpha = 0.075f),
                            topLeft = Offset(-i * 2.2f * d, thick + i * 1.8f * d),
                            size = Size(w + i * 4.4f * d, h),
                            cornerRadius = CornerRadius(r + i * 2f * d),
                        )
                    }
                    // épaisseur du bois (face avant)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(OakDark, OakDeep), startY = thick, endY = h + thick),
                        topLeft = Offset(0f, thick),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r),
                    )
                    // face supérieure
                    drawRoundRect(
                        Brush.verticalGradient(listOf(OakTop, OakMid, OakBottom), startY = 0f, endY = h),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r),
                    )
                    // veinage, découpé aux bords arrondis du bois
                    val body = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(r)))
                    }
                    clipPath(body) {
                        for (g in grain) {
                            val path = androidx.compose.ui.graphics.Path().apply {
                                moveTo(0f, g.y)
                                var xx = 0f
                                while (xx < w) {
                                    xx = minOf(w, xx + 24f * d)
                                    lineTo(xx, g.y + kotlin.math.sin((xx / (60f * d)) + g.phase) * g.amp * d)
                                }
                            }
                            drawPath(path, Color(0xFF5B3716).copy(alpha = g.alpha), style = Stroke(width = g.width * d))
                        }
                    }
                    // arête supérieure éclairée
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xAAFFFFFF), Color(0x00FFFFFF)), endY = 12f * d),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(r),
                        style = Stroke(width = 2.2f * d),
                    )
                    // le creux où reposent les tuiles
                    val inL = 10f * d
                    val inT = 7f * d
                    val inR = w - 10f * d
                    val inB = h - 9f * d
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xFF7A4E26), Color(0xFFA9763F), Color(0xFFC08B52)), startY = inT, endY = inB),
                        topLeft = Offset(inL, inT),
                        size = Size(inR - inL, inB - inT),
                        cornerRadius = CornerRadius(12f * d),
                    )
                    // ombre intérieure en haut du creux
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0x66000000), Color(0x00000000)), startY = inT, endY = inT + 18f * d),
                        topLeft = Offset(inL, inT),
                        size = Size(inR - inL, 22f * d),
                        cornerRadius = CornerRadius(12f * d),
                    )
                    // chaque niveau a son étagère : un trait clair puis un trait sombre
                    for (top in tierTops) {
                        val ly = top + tileH * 0.985f
                        if (ly < inB) {
                            drawLine(Color(0x66FFFFFF), Offset(inL + 6f * d, ly), Offset(inR - 6f * d, ly), strokeWidth = 1.6f * d)
                            drawLine(Color(0x88000000), Offset(inL + 6f * d, ly + 2f * d), Offset(inR - 6f * d, ly + 2f * d), strokeWidth = 2.4f * d)
                        }
                    }
                    if (highlight) {
                        drawRoundRect(
                            DropCyan.copy(alpha = 0.9f),
                            topLeft = Offset(inL, inT),
                            size = Size(inR - inL, inB - inT),
                            cornerRadius = CornerRadius(12f * d),
                            style = Stroke(width = 3f * d),
                        )
                    }
                }
            },
    )
}

/** Le rebord avant du socle : il passe devant le bas des tuiles du niveau avant, comme si elles étaient posées dedans. */
@Composable
private fun RackLip(x: Float, y: Float, w: Float, h: Float, frontTop: Float) {
    val density = LocalDensity.current
    val d = density.density
    val lipH = (h - frontTop).coerceAtLeast(10f * d)
    Box(
        Modifier
            .zIndex(2.5f)
            .offset { IntOffset(x.roundToInt(), (y + frontTop - 8f * d).roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { (lipH + 8f * d + 9f * d).toDp() })
            .drawBehind {
                val top = 8f * d
                // ombre portée du rebord sur les tuiles
                drawRect(
                    Brush.verticalGradient(listOf(Color(0x00000000), Color(0x55000000)), startY = 0f, endY = top),
                    size = Size(size.width, top),
                )
                // dessus du rebord (éclairé)
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFFF3DDB4), Color(0xFFE2BC86)), startY = top, endY = top + 7f * d),
                    topLeft = Offset(5f * d, top),
                    size = Size(size.width - 10f * d, 7f * d),
                    cornerRadius = CornerRadius(3f * d),
                )
                // face avant du rebord, jusqu'au bas du socle
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFFCB9A5E), Color(0xFFB07C45), Color(0xFF8A5B2E)), startY = top + 6f * d, endY = size.height),
                    topLeft = Offset(0f, top + 6f * d),
                    size = Size(size.width, size.height - top - 6f * d),
                    cornerRadius = CornerRadius(14f * d),
                )
                drawLine(Color(0x55FFFFFF), Offset(8f * d, top + 7.5f * d), Offset(size.width - 8f * d, top + 7.5f * d), strokeWidth = 1.4f * d)
            },
    )
}

@Composable
private fun animateColorAsStateCompat(target: Color) =
    androidx.compose.animation.animateColorAsState(target, tween(200), label = "frame_color")

@Composable
private fun GhostSlot(box: Rect, zi: Float, faint: Boolean = false) {
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
                    DropCyan.copy(alpha = if (faint) 0.05f else 0.16f),
                    cornerRadius = CornerRadius(8f * d),
                )
                drawRoundRect(
                    DropCyan.copy(alpha = if (faint) 0.35f else 0.9f),
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
    androidx.compose.foundation.layout.Row(
        modifier
            .size(width = 200.dp, height = 22.dp)
            .background(Color(0xCC2B1808), RoundedCornerShape(11.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("1ère pose  $points / $threshold", color = color, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(width = 54.dp, height = 5.dp)
                .background(Color(0x44FFFFFF), RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { transformOrigin = TransformOrigin(0f, 0.5f); scaleX = fraction }
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
    }
}
