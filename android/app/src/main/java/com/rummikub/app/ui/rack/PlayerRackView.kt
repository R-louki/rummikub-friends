package com.rummikub.app.ui.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.components.TileView
import com.rummikub.app.ui.dnd.DropZone
import com.rummikub.app.ui.dnd.tileDragSource
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile

private const val MIN_COLUMNS = 16
private const val ROW_STRIDE = 100 // slot = row * ROW_STRIDE + column (stable when the column count grows)
private val SlotWidth = 30.dp
private val SlotHeight = 40.dp

/**
 * Player Rack (Chevalet): two wooden rows of fixed slots.
 * Tiles can be dropped on ANY slot (free manual arrangement, gaps allowed); dropping on an occupied
 * slot swaps the two tiles. The sort buttons re-pack the rack ([layoutEpoch] changes).
 * The slot layout is purely local (never part of the turn). The whole rack is also a drop zone so a
 * tile played this turn can come back (drag, or tap when selected).
 */
@Composable
fun PlayerRackView(
    rack: PlayerRack,
    layoutEpoch: Int,
    isMyTurn: Boolean,
    selectedTileIds: Set<String>,
    onTileTapped: (Tile) -> Unit,
    onTileDroppedOnRack: (tileId: String) -> Unit,
    onRackTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rackIds = remember(rack.tiles) { rack.tiles.map { it.id }.toSet() }
    val selectedOnBoard = selectedTileIds.any { it !in rackIds }
    val columns = maxOf(MIN_COLUMNS, (rack.tiles.size + 1) / 2 + 2)

    // tile id -> slot index (row * columns + column). Reset when the rack is re-sorted.
    val slots: SnapshotStateMap<String, Int> = remember(layoutEpoch) { mutableStateMapOf() }
    val placement = resolvePlacement(rack.tiles, slots, columns)
    SideEffect {
        placement.forEach { (id, slot) -> if (slots[id] != slot) slots[id] = slot }
    }
    val tileAtSlot = remember(placement, rack.tiles) {
        val byId = rack.tiles.associateBy { it.id }
        placement.entries.associate { (id, slot) -> slot to byId.getValue(id) }
    }

    /** A tile dropped on a slot: move/swap inside the rack, or bring a board tile back there. */
    fun dropOnSlot(tileId: String, slot: Int) {
        if (tileId in rackIds) {
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
        onDropTile = { id -> if (id in rackIds || isMyTurn) { if (id !in rackIds) onTileDroppedOnRack(id) } },
        onTap = { if (isMyTurn && selectedOnBoard) onRackTapped() },
        modifier = modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(WoodBorder, WoodDarkBrown, Color(0xFF190C06))
                )
            )
            .border(2.dp, Color(0xFF5D4037), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
    ) { hovered ->
        val highlight = (hovered && isMyTurn) || selectedOnBoard
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .then(
                    if (highlight) Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(6.dp))
                    else Modifier
                ),
            verticalArrangement = Arrangement.Center
        ) {
            if (rack.tiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chevalet vide", color = Color(0x88FFFFFF), fontSize = 11.sp)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33000000))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    for (row in 0..1) {
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
                                    isMyTurn = isMyTurn,
                                    selected = tile != null && tile.id in selectedTileIds,
                                    selectedOnBoard = selectedOnBoard,
                                    onTileTapped = onTileTapped,
                                    onDrop = { id -> dropOnSlot(id, slot) },
                                    onTapEmpty = { if (isMyTurn && selectedOnBoard) onRackTapped() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RackSlot(
    tile: Tile?,
    isMyTurn: Boolean,
    selected: Boolean,
    selectedOnBoard: Boolean,
    onTileTapped: (Tile) -> Unit,
    onDrop: (String) -> Unit,
    onTapEmpty: () -> Unit
) {
    DropZone(
        enabled = true,
        onDropTile = onDrop,
        onTap = onTapEmpty,
        modifier = Modifier.size(SlotWidth, SlotHeight)
    ) { hovered ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered) Color(0x5500E5FF) else if (tile == null) Color(0x14FFFFFF) else Color.Transparent)
        ) {
            if (tile != null) {
                key(tile.id) {
                    TileView(
                        tile = tile,
                        width = SlotWidth,
                        height = SlotHeight,
                        isSelected = selected,
                        modifier = Modifier.tileDragSource(
                            tileId = tile.id,
                            enabled = true, // the rack can always be rearranged, even outside my turn
                            immediateDrag = true,
                            onTap = { if (isMyTurn) onTileTapped(tile) }
                        )
                    )
                }
            }
        }
    }
}

/**
 * Gives every rack tile a distinct slot: its remembered slot if still valid and free, otherwise the
 * first free one. With no remembered slots at all the tiles are split evenly over the two rows.
 */
private fun resolvePlacement(tiles: List<Tile>, remembered: Map<String, Int>, columns: Int): Map<String, Int> {
    val all = (0..1).flatMap { r -> (0 until columns).map { c -> r * ROW_STRIDE + c } }
    val valid = all.toHashSet()
    val result = LinkedHashMap<String, Int>()
    val used = HashSet<Int>()
    val fresh = tiles.none { remembered.containsKey(it.id) }
    val half = (tiles.size + 1) / 2

    tiles.forEachIndexed { i, tile ->
        val preferred = if (fresh) {
            if (i < half) i else ROW_STRIDE + (i - half)
        } else remembered[tile.id]
        val slot = if (preferred != null && preferred in valid && used.add(preferred)) preferred
        else all.first { it !in used }.also { used.add(it) }
        result[tile.id] = slot
    }
    return result
}
