package com.rummikub.app.ui.rack

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.components.TileView
import com.rummikub.app.ui.theme.WoodBorder
import com.rummikub.app.ui.theme.WoodDarkBrown
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile

/**
 * Player Rack (Chevalet) with 2 wooden rows and ergonomic sort & turn buttons.
 */
@Composable
fun PlayerRackView(
    rack: PlayerRack,
    isMyTurn: Boolean,
    onSortByRun: () -> Unit,
    onSortByGroup: () -> Unit,
    onResetTurn: () -> Unit,
    onDrawTile: () -> Unit,
    onCommitTurn: () -> Unit,
    onTileDragStart: (Tile) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .shadow(10.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(WoodBorder, WoodDarkBrown, Color(0xFF190C06))
                )
            )
            .border(2.dp, Color(0xFF5D4037), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Action Buttons: Sorting & Reset Turn
        Column(
            modifier = Modifier.width(135.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onSortByRun()
                    },
                    modifier = Modifier.weight(1f).height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E2723)),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Text("🌈 Séries", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Button(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onSortByGroup()
                    },
                    modifier = Modifier.weight(1f).height(34.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E2723)),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Text("🔢 Groupes", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }

            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onResetTurn()
                },
                modifier = Modifier.fillMaxWidth().height(34.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5D4037)),
                contentPadding = PaddingValues(2.dp)
            ) {
                Text("↺ Annuler Coup", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFCC80))
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Center: 2 Wooden Racks holding tiles
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val half = (rack.tiles.size + 1) / 2
            val topRow = rack.tiles.take(half)
            val bottomRow = rack.tiles.drop(half)

            RackRow(
                tiles = topRow,
                isMyTurn = isMyTurn,
                onTileDragStart = onTileDragStart,
                onHaptic = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                modifier = Modifier.weight(1f)
            )

            RackRow(
                tiles = bottomRow,
                isMyTurn = isMyTurn,
                onTileDragStart = onTileDragStart,
                onHaptic = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right Action Buttons: Draw Tile & Commit Turn
        Column(
            modifier = Modifier.width(135.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onDrawTile()
                },
                enabled = isMyTurn,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
            ) {
                Text("+1 Piocher", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onCommitTurn()
                },
                enabled = isMyTurn,
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Text("✓ Valider Tour", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
    }
}

@Composable
private fun RackRow(
    tiles: List<Tile>,
    isMyTurn: Boolean,
    onTileDragStart: (Tile) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x33000000))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tiles.forEach { tile ->
            TileView(
                tile = tile,
                width = 36.dp,
                height = 50.dp,
                modifier = Modifier.pointerInput(tile.id) {
                    if (isMyTurn) {
                        detectDragGestures(
                            onDragStart = {
                                onHaptic()
                                onTileDragStart(tile)
                            },
                            onDrag = { change, _ -> change.consume() }
                        )
                    }
                }
            )
        }
    }
}
