package com.rummikub.app.ui.board

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.ui.components.TileView
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.FeltMediumGreen
import com.rummikub.core.engine.BoardValidator
import com.rummikub.core.model.BoardState
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.Tile

/**
 * Landscape Board Grid displaying all melds on the table with magnetic snapping.
 */
@Composable
fun BoardGrid(
    boardState: BoardState,
    isMyTurn: Boolean,
    onTileMoved: (tileId: String, targetMeldId: String?, newPositionIndex: Int) -> Unit,
    onTileDraggedThrottled: (tileId: String, posX: Float, posY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FeltDarkGreen)
            .border(2.dp, Color(0xFF2E1A11), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        if (boardState.melds.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isMyTurn) "Déposez vos premières combinaisons ici (30 pts min)" else "En attente des coups des joueurs...",
                    color = Color(0x66FFFFFF),
                    fontSize = 14.sp
                )
            }
        } else {
            // Flow/Grid of meld sets on the board
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Render rows of melds
                boardState.melds.chunked(3).forEach { meldRow ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        meldRow.forEach { meld ->
                            MeldGroupContainer(
                                meld = meld,
                                isMyTurn = isMyTurn,
                                onTileSnapped = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                },
                                onDrag = onTileDraggedThrottled
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MeldGroupContainer(
    meld: MeldSet,
    isMyTurn: Boolean,
    onTileSnapped: () -> Unit,
    onDrag: (String, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val validation = remember(meld.tiles) { BoardValidator.validateMeld(meld) }
    val borderColor = when {
        meld.tiles.size < 3 -> Color(0xFFFFA000).copy(alpha = 0.6f) // Incomplete (<3 tiles)
        validation.isValid -> Color(0xFF81C784).copy(alpha = 0.5f)  // Valid set
        else -> Color(0xFFE57373).copy(alpha = 0.7f)                // Illegal set
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(FeltMediumGreen.copy(alpha = 0.60f))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        meld.tiles.forEach { tile ->
            TileView(
                tile = tile,
                width = 38.dp,
                height = 52.dp,
                modifier = Modifier.pointerInput(tile.id) {
                    if (isMyTurn) {
                        detectDragGestures(
                            onDragStart = { onTileSnapped() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(tile.id, dragAmount.x, dragAmount.y)
                            }
                        )
                    }
                }
            )
        }
    }
}
