package com.rummikub.app.ui.rack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.components.TileView
import com.rummikub.app.ui.dnd.DropZone
import com.rummikub.app.ui.dnd.tileDragSource
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile

private const val ROW_STRIDE = 100 // slot = row * ROW_STRIDE + column (stable when the column count changes)
private val BigW = 40.dp
private val BigH = 54.dp
private val SmallW = 30.dp
private val SmallH = 40.dp
private val ToolsWidth = 104.dp

/**
 * Player Rack (Chevalet).
 * - Few tiles: ONE row of big tiles. Many tiles: two rows of smaller ones (foldable to one row).
 * - Free arrangement: tiles can be dropped on ANY slot (gaps allowed); dropping on an occupied slot swaps.
 * - The two sort buttons re-pack the rack. The layout is purely local (never part of the turn).
 * - The whole rack is also a drop zone so a tile played this turn can come back (drag, or tap when selected).
 */
@Composable
fun PlayerRackView(
    rack: PlayerRack,
    layoutEpoch: Int,
    collapsed: Boolean,
    drawnTileId: String? = null,
    sortMode: Int = 0,
    onSelectTiles: (List<String>) -> Unit = {},
    onToggleCollapsed: () -> Unit,
    onSortRuns: () -> Unit,
    onSortGroups: () -> Unit,
    isMyTurn: Boolean,
    selectedTileIds: Set<String>,
    onTileTapped: (Tile) -> Unit,
    onTileDroppedOnRack: (tileId: String) -> Unit,
    onRackTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val usableWidth = maxWidth - 16.dp - ToolsWidth
        val bigColumns = (usableWidth / (BigW + 3.dp)).toInt().coerceAtLeast(8)
        val needed = if (sortMode != 0) {
            (sortedLayout(rack.tiles, sortMode).maxOfOrNull { it.second } ?: -1) + 1
        } else rack.tiles.size
        val smallColumns = (usableWidth / (SmallW + 3.dp)).toInt()
        // After a sort the gaps may need a few more slots: keep ONE row and shrink the tiles a little
        val shrink = sortMode != 0 && needed > bigColumns && needed <= smallColumns
        val single = needed <= bigColumns || shrink
        val rows = if (single) 1 else 2
        val tileW = when {
            shrink -> ((usableWidth - 12.dp) / needed - 3.dp).coerceIn(SmallW, BigW)
            single -> BigW
            else -> SmallW
        }
        val tileH = when {
            shrink -> tileW * 1.35f
            single -> BigH
            else -> SmallH
        }
        val columns = when {
            shrink -> needed
            single -> bigColumns
            else -> maxOf(smallColumns, (rack.tiles.size + 1) / 2 + 1)
        }
        val folded = collapsed && !single
        val rackHeight = when {
            single -> tileH + 16.dp
            folded -> SmallH + 16.dp
            else -> SmallH * 2 + 3.dp + 16.dp
        }

        val rackIds = remember(rack.tiles) { rack.tiles.map { it.id }.toSet() }
        val selectedOnBoard = selectedTileIds.any { it !in rackIds }

        // tile id -> slot index. Reset when the rack is re-sorted.
        val slots: SnapshotStateMap<String, Int> = remember(layoutEpoch) { mutableStateMapOf() }
        val placement = resolvePlacement(rack.tiles, slots, columns, rows, sortMode)
        SideEffect {
            placement.forEach { (id, slot) -> if (slots[id] != slot) slots[id] = slot }
        }
        val tileAtSlot = remember(placement, rack.tiles) {
            val byId = rack.tiles.associateBy { it.id }
            placement.entries.associate { (id, slot) -> slot to byId.getValue(id) }
        }

        /** A tile dropped on a slot: move/swap inside the rack, or bring a board tile back there. */
        fun dropOnSlot(tileId: String, slot: Int) {
            val batch = if (tileId in selectedTileIds && selectedTileIds.size > 1) {
                selectedTileIds.filter { it in rackIds }
            } else emptyList()
            if (tileId in rackIds && batch.size > 1) {
                // Several selected tiles: lay them out from the target slot to the right (then the next row)
                val moving = batch.toSet()
                val used = placement.filterKeys { it !in moving }.values.toHashSet()
                val startRow = slot / ROW_STRIDE
                val startCol = slot % ROW_STRIDE
                val candidates = (startRow until rows).flatMap { r ->
                    ((if (r == startRow) startCol else 0) until columns).map { c -> r * ROW_STRIDE + c }
                } + (0..startRow).flatMap { r ->
                    (0 until (if (r == startRow) startCol else columns)).map { c -> r * ROW_STRIDE + c }
                }
                val free = candidates.filter { it !in used }.iterator()
                batch.forEach { id -> if (free.hasNext()) slots[id] = free.next() }
            } else if (tileId in rackIds) {
                val occupant = tileAtSlot[slot]
                val oldSlot = placement[tileId]
                if (occupant != null && occupant.id != tileId && oldSlot != null) slots[occupant.id] = oldSlot
                slots[tileId] = slot
            } else if (isMyTurn) {
                if (tileAtSlot[slot] == null) slots[tileId] = slot // kept if the tile is accepted
                onTileDroppedOnRack(tileId)
            }
        }

        DropZone(
            enabled = true,
            onDropTile = { id -> if (id !in rackIds && isMyTurn) onTileDroppedOnRack(id) },
            onTap = { if (isMyTurn && selectedOnBoard) onRackTapped() },
            modifier = Modifier
                .fillMaxWidth()
                .height(rackHeight)
                .shadow(8.dp, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(Brush.verticalGradient(colors = listOf(WoodBorder, WoodDarkBrown, Color(0xFF190C06))))
                .border(2.dp, Color(0xFF5D4037), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
        ) { hovered ->
            val highlight = (hovered && isMyTurn) || selectedOnBoard
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .then(
                        if (highlight) Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(6.dp))
                        else Modifier
                    )
            ) {
                if (rack.tiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Chevalet vide", color = Color(0x88FFFFFF), fontSize = 11.sp)
                    }
                } else if (folded) {
                    // Folded rack: every tile on one row, in the order of the slots
                    val ordered = rack.tiles.sortedBy { placement[it.id] ?: Int.MAX_VALUE }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = ToolsWidth)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Transparent)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ordered.forEachIndexed { index, tile ->
                            key(tile.id) {
                                RackTile(
                                    tile, tileW, tileH, tile.id in selectedTileIds, isMyTurn, onTileTapped, tile.id == drawnTileId,
                                    onLongPress = { onSelectTiles(chainOf(tile, ordered.drop(index + 1))) }
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = ToolsWidth)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Transparent)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        for (row in 0 until rows) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (col in 0 until columns) {
                                    val slot = row * ROW_STRIDE + col
                                    val tile = tileAtSlot[slot]
                                    RackSlot(
                                        tile = tile,
                                        tileW = tileW,
                                        tileH = tileH,
                                        isMyTurn = isMyTurn,
                                        drawn = tile != null && tile.id == drawnTileId,
                                        selected = tile != null && tile.id in selectedTileIds,
                                        onTileTapped = onTileTapped,
                                        onLongPress = {
                                            if (tile != null) {
                                                // the tiles lying right next to it, up to the first empty slot
                                                val followers = ArrayList<Tile>()
                                                var c = col + 1
                                                while (c < columns) {
                                                    followers += tileAtSlot[row * ROW_STRIDE + c] ?: break
                                                    c++
                                                }
                                                onSelectTiles(chainOf(tile, followers))
                                            }
                                        },
                                        onDrop = { id -> dropOnSlot(id, slot) },
                                        onTapEmpty = { if (isMyTurn && selectedOnBoard) onRackTapped() }
                                    )
                                }
                            }
                        }
                    }
                }

                // Tools: sort + fold, at the end of the rack
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RackTool("789", onSortRuns)
                    RackTool("999", onSortGroups)
                    if (!single) RackTool(if (collapsed) "▴" else "▾", onToggleCollapsed)
                }
            }
        }
    }
}

@Composable
private fun RackTool(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .widthIn(min = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC1B0E09))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(glyph, color = Color(0xFFFFCC80), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun RackTile(
    tile: Tile, w: Dp, h: Dp, selected: Boolean, isMyTurn: Boolean, onTileTapped: (Tile) -> Unit,
    drawn: Boolean = false, onLongPress: (() -> Unit)? = null
) {
    Box(modifier = if (drawn) Modifier.drawnEffect() else Modifier) {
    TileView(
        tile = tile,
        width = w,
        height = h,
        isSelected = selected,
        modifier = Modifier.tileDragSource(
            tileId = tile.id,
            enabled = true, // the rack can always be rearranged, even outside my turn
            immediateDrag = true,
            onLongPress = onLongPress,
            onTap = { if (isMyTurn) onTileTapped(tile) }
        )
    )
    }
}

/**
 * Hold a tile: it and the tiles that follow it form the selection, as long as they continue a
 * combination (same colour and numbers in a row, or same number in different colours).
 * 5 held in "5 6 7" selects 5, 6 and 7; the whole lot can then be dragged.
 */
private fun chainOf(start: Tile, followers: List<Tile>): List<String> {
    val out = arrayListOf(start.id)
    var prev = start
    var mode = 0 // 0 not known yet, 1 run, 2 group
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

/** The tile just drawn: it flies in (springy zoom) and pulses with a golden ring for a few seconds. */
@Composable
private fun Modifier.drawnEffect(): Modifier {
    val zoom = remember { Animatable(1.8f) }
    val flip = remember { Animatable(180f) }
    LaunchedEffect(Unit) {
        launch { flip.animateTo(0f, tween(650)) }
        zoom.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 260f))
    }
    val pulse = rememberInfiniteTransition(label = "drawn")
    val alpha by pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "drawnAlpha"
    )
    return this
        .graphicsLayer {
            scaleX = zoom.value
            scaleY = zoom.value
            rotationY = flip.value          // the tile turns over in 3D as it arrives
            cameraDistance = 12f * density
        }
        .border(3.dp, Color(0xFFFFD54F).copy(alpha = alpha), RoundedCornerShape(8.dp))
}

@Composable
private fun RackSlot(
    tile: Tile?,
    tileW: Dp,
    tileH: Dp,
    isMyTurn: Boolean,
    drawn: Boolean = false,
    selected: Boolean,
    onTileTapped: (Tile) -> Unit,
    onLongPress: (() -> Unit)? = null,
    onDrop: (String) -> Unit,
    onTapEmpty: () -> Unit
) {
    DropZone(
        enabled = true,
        onDropTile = onDrop,
        onTap = onTapEmpty,
        modifier = Modifier.size(tileW, tileH)
    ) { hovered ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered) Color(0x5500E5FF) else Color.Transparent)
        ) {
            if (tile != null) {
                key(tile.id) { RackTile(tile, tileW, tileH, selected, isMyTurn, onTileTapped, drawn, onLongPress) }
            }
        }
    }
}

/**
 * Gives every rack tile a distinct slot: its remembered slot if still valid and free, otherwise the
 * first free one. With no remembered slots at all, one row is filled left to right and two rows are
 * split evenly.
 */
/**
 * After "789" (runs) tiles are ordered by colour then number, after "999" (groups) by number then
 * colour. A space is left around every real combination (3 or more tiles in a row of the same colour,
 * or of the same number) and between colours; loose tiles stay close together.
 * Returns each tile with its position on one virtual line (gaps included).
 */
private fun sortedLayout(tiles: List<Tile>, mode: Int): List<Pair<Tile, Int>> {
    val ordered = if (mode == 1) {
        tiles.sortedWith(compareBy<Tile> { it.isJoker }.thenBy { it.color?.ordinal ?: 99 }.thenBy { it.number })
    } else {
        tiles.sortedWith(compareBy<Tile> { it.isJoker }.thenBy { it.number }.thenBy { it.color?.ordinal ?: 99 })
    }
    // Segment = tiles that follow each other as one potential combination
    fun follows(p: Tile, t: Tile): Boolean = when {
        p.isJoker || t.isJoker -> p.isJoker && t.isJoker
        mode == 1 -> p.color == t.color && t.number - p.number == 1
        else -> p.number == t.number
    }
    val segment = IntArray(ordered.size)
    for (i in 1 until ordered.size) segment[i] = if (follows(ordered[i - 1], ordered[i])) segment[i - 1] else segment[i - 1] + 1
    val size = HashMap<Int, Int>()
    segment.forEach { size[it] = (size[it] ?: 0) + 1 }

    val out = ArrayList<Pair<Tile, Int>>()
    var pos = 0
    for (i in ordered.indices) {
        if (i > 0) {
            val p = ordered[i - 1]
            val t = ordered[i]
            val boundary = segment[i] != segment[i - 1]
            val combo = (size[segment[i - 1]] ?: 0) >= 3 || (size[segment[i]] ?: 0) >= 3
            val gap = when {
                p.isJoker != t.isJoker -> true
                !boundary -> false
                combo -> true
                mode == 1 -> p.color != t.color
                else -> false
            }
            if (gap) pos++
        }
        out += ordered[i] to pos
        pos++
    }
    return out
}

private fun resolvePlacement(tiles: List<Tile>, remembered: Map<String, Int>, columns: Int, rows: Int, sortMode: Int): Map<String, Int> {
    val all = (0 until rows).flatMap { r -> (0 until columns).map { c -> r * ROW_STRIDE + c } }
    val valid = all.toHashSet()
    val result = LinkedHashMap<String, Int>()
    val used = HashSet<Int>()
    val fresh = tiles.none { remembered.containsKey(it.id) }
    val half = (tiles.size + 1) / 2

    val sortedPos = if (sortMode != 0) sortedLayout(tiles, sortMode).associate { it.first.id to it.second } else emptyMap()

    tiles.forEachIndexed { i, tile ->
        val preferred = if (fresh) {
            val sp = sortedPos[tile.id]
            when {
                sp != null -> if (sp < columns) sp else if (rows > 1) ROW_STRIDE + (sp - columns) else null
                rows == 1 || i < half -> i
                else -> ROW_STRIDE + (i - half)
            }
        } else remembered[tile.id]
        val slot = if (preferred != null && preferred in valid && used.add(preferred)) preferred
        else all.first { it !in used }.also { used.add(it) }
        result[tile.id] = slot
    }
    return result
}
