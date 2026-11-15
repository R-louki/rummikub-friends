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
        val smallColumns = (usableWidth / (SmallW + 3.dp)).toInt()
        // After a sort: combinations are detected and spaced from the other tiles. Spaces are given up
        // one by one (between combinations, then around them) before the rack needs a second row.
        val layoutChoice: SortedLayout? = if (sortMode != 0) {
            val levels = (0..2).map { sortedLayout(rack.tiles, sortMode, it) }
            levels.firstOrNull { it.needed <= bigColumns }
                ?: levels.firstOrNull { it.needed <= smallColumns }
                ?: levels[0]
        } else null
        val needed = layoutChoice?.needed ?: rack.tiles.size
        // Keep ONE row and shrink the tiles a little when the spaces need more slots
        val shrink = layoutChoice != null && needed > bigColumns && needed <= smallColumns
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
        val placement = resolvePlacement(rack.tiles, slots, columns, rows, layoutChoice?.positions ?: emptyMap())
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
        isGhost = com.rummikub.app.ui.dnd.DragState.tileId == tile.id,
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
                // no clip here: a selected tile rises and grows beyond its slot
                .background(if (hovered) Color(0x5500E5FF) else Color.Transparent, RoundedCornerShape(4.dp))
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
private class SortedLayout(val positions: Map<String, Int>, val needed: Int)

/**
 * Finds the combinations ("plis") in the hand: runs (same colour, numbers in a row, 3 or more) and
 * groups (same number, different colours, 3 or 4). The preferred kind is taken first when a tile
 * could belong to both.
 */
private fun detectCombos(tiles: List<Tile>, runsFirst: Boolean): List<List<Tile>> {
    val pool = tiles.filter { !it.isJoker }.toMutableList()
    val result = ArrayList<List<Tile>>()

    fun takeRuns() {
        for (color in com.rummikub.core.model.TileColor.values()) {
            while (true) {
                val byNumber = pool.filter { it.color == color }.groupBy { it.number }
                var best: List<Int> = emptyList()
                var current = ArrayList<Int>()
                for (n in 1..13) {
                    if (byNumber.containsKey(n)) current.add(n) else {
                        if (current.size > best.size) best = current
                        current = ArrayList()
                    }
                }
                if (current.size > best.size) best = current
                if (best.size < 3) break
                val run = best.map { n -> byNumber.getValue(n).first() }
                pool.removeAll(run.toSet())
                result += run
            }
        }
    }

    fun takeGroups() {
        for (n in 1..13) {
            while (true) {
                val distinct = pool.filter { it.number == n }.distinctBy { it.color }
                if (distinct.size < 3) break
                val group = distinct.sortedBy { it.color?.ordinal ?: 99 }.take(4)
                pool.removeAll(group.toSet())
                result += group
            }
        }
    }

    if (runsFirst) { takeRuns(); takeGroups() } else { takeGroups(); takeRuns() }
    return result
}

/**
 * Layout after a sort: every combination is a block, spaced from the others and from the loose tiles.
 * [level] 0 = a space between all blocks, 1 = a space only between combinations and loose tiles,
 * 2 = no space at all (when the rack is too full).
 */
private fun sortedLayout(tiles: List<Tile>, mode: Int, level: Int): SortedLayout {
    val combos = detectCombos(tiles, runsFirst = mode == 1)
    val inCombo = combos.flatten().map { it.id }.toSet()
    val loose = tiles.filter { it.id !in inCombo && !it.isJoker }.let { l ->
        if (mode == 1) l.sortedWith(compareBy<Tile>({ it.color?.ordinal ?: 99 }, { it.number }))
        else l.sortedWith(compareBy<Tile>({ it.number }, { it.color?.ordinal ?: 99 }))
    }
    val jokers = tiles.filter { it.isJoker }

    val positions = LinkedHashMap<String, Int>()
    var pos = 0
    combos.forEachIndexed { i, combo ->
        if (i > 0 && level == 0) pos++
        combo.forEach { positions[it.id] = pos++ }
    }
    if (loose.isNotEmpty()) {
        if (combos.isNotEmpty() && level <= 1) pos++
        loose.forEach { positions[it.id] = pos++ }
    }
    if (jokers.isNotEmpty()) {
        if (positions.isNotEmpty() && level <= 1) pos++
        jokers.forEach { positions[it.id] = pos++ }
    }
    return SortedLayout(positions, pos)
}

private fun resolvePlacement(tiles: List<Tile>, remembered: Map<String, Int>, columns: Int, rows: Int, sortedPos: Map<String, Int>): Map<String, Int> {
    val all = (0 until rows).flatMap { r -> (0 until columns).map { c -> r * ROW_STRIDE + c } }
    val valid = all.toHashSet()
    val result = LinkedHashMap<String, Int>()
    val used = HashSet<Int>()
    val fresh = tiles.none { remembered.containsKey(it.id) }
    val half = (tiles.size + 1) / 2


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
