package com.rummikub.app.ui.rack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

/**
 * Player Rack (Chevalet): two wooden rows of tiles.
 * The whole rack is a drop zone so a tile played this turn can come back (drag, or tap when selected).
 * Action buttons live in the side ribbons of the game screen.
 */
@Composable
fun PlayerRackView(
    rack: PlayerRack,
    isMyTurn: Boolean,
    selectedTileIds: Set<String>,
    onTileTapped: (Tile) -> Unit,
    onTileDroppedOnRack: (tileId: String) -> Unit,
    onRackTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rackIds = remember(rack.tiles) { rack.tiles.map { it.id }.toSet() }
    val selectedOnBoard = selectedTileIds.any { it !in rackIds }

    DropZone(
        enabled = isMyTurn,
        onDropTile = onTileDroppedOnRack,
        onTap = { if (selectedOnBoard) onRackTapped() },
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
        val highlight = hovered || selectedOnBoard
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .then(
                    if (highlight) Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(6.dp))
                    else Modifier
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (rack.tiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chevalet vide", color = Color(0x88FFFFFF), fontSize = 11.sp)
                }
            } else {
                val half = (rack.tiles.size + 1) / 2
                val topRow = rack.tiles.take(half)
                val bottomRow = rack.tiles.drop(half)

                RackRow(
                    tiles = topRow,
                    isMyTurn = isMyTurn,
                    selectedTileIds = selectedTileIds,
                    onTileTapped = onTileTapped,
                    modifier = Modifier.weight(1f)
                )

                RackRow(
                    tiles = bottomRow,
                    isMyTurn = isMyTurn,
                    selectedTileIds = selectedTileIds,
                    onTileTapped = onTileTapped,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RackRow(
    tiles: List<Tile>,
    isMyTurn: Boolean,
    selectedTileIds: Set<String>,
    onTileTapped: (Tile) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x33000000))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tiles.forEach { tile ->
            key(tile.id) {
                TileView(
                    tile = tile,
                    width = 36.dp,
                    height = 48.dp,
                    isSelected = tile.id in selectedTileIds,
                    modifier = Modifier.tileDragSource(
                        tileId = tile.id,
                        enabled = isMyTurn,
                        onTap = { onTileTapped(tile) }
                    )
                )
            }
        }
    }
}
