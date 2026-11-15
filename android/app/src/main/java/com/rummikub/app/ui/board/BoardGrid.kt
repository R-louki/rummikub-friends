package com.rummikub.app.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rummikub.app.game.DropTarget
import com.rummikub.app.ui.components.TileView
import com.rummikub.app.ui.dnd.DropZone
import com.rummikub.app.ui.dnd.tileDragSource
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.FeltMediumGreen
import com.rummikub.core.engine.BoardValidator
import com.rummikub.core.model.BoardState
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.Tile

/**
 * The table: every meld currently laid down.
 *
 * Interaction (only on the active player's turn):
 * - Long press a tile to drag it; drop it on a meld edge, between two tiles, anywhere on the
 *   free felt (new meld), or back on the rack.
 * - Or tap one or several tiles to select them, then tap a meld edge / the free felt / the rack.
 *
 * @param newTileIds Tiles placed on the table during the current turn (highlighted).
 * @param selectedTileIds Tiles currently selected in tap mode.
 */
@Composable
fun BoardGrid(
    boardState: BoardState,
    isMyTurn: Boolean,
    newTileIds: Set<String>,
    selectedTileIds: Set<String>,
    placedByOthers: Set<String> = emptySet(),
    movedByOthers: Set<String> = emptySet(),
    initialMeldThreshold: Int,
    onTileTapped: (Tile) -> Unit,
    onTileDropped: (tileId: String, target: DropTarget) -> Unit,
    onTargetTapped: (target: DropTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasSelection = selectedTileIds.isNotEmpty()

    // The whole felt accepts drops / taps to create a new meld
    DropZone(
        enabled = isMyTurn,
        onDropTile = { id -> onTileDropped(id, DropTarget.NewMeld) },
        onTap = { if (hasSelection) onTargetTapped(DropTarget.NewMeld) },
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FeltDarkGreen)
            .border(
                width = 2.dp,
                color = if (hasSelection && isMyTurn) Color(0x8800E5FF) else Color(0xFF2E1A11),
                shape = RoundedCornerShape(12.dp)
            )
    ) { feltHovered ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (feltHovered) Modifier.background(Color(0x1A00E5FF)) else Modifier)
                .padding(6.dp)
        ) {
            val fit = remember(boardState.melds, maxWidth, maxHeight) {
                fitTiles(boardState.melds, maxWidth, maxHeight)
            }

            if (boardState.melds.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when {
                            !isMyTurn -> "En attente des coups des joueurs..."
                            initialMeldThreshold > 0 -> "Touchez vos tuiles pour les sélectionner, puis touchez la table.\nOu appui long pour glisser. Première pose : $initialMeldThreshold pts minimum."
                            else -> "Touchez vos tuiles pour les sélectionner, puis touchez la table.\nOu appui long pour glisser."
                        },
                        color = Color(0x88FFFFFF),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // The tile size is chosen so that every meld is visible at once (no scrolling);
                // scrolling only appears as a last resort when even the smallest size does not fit.
                val scrollModifier = if (fit.needsScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(scrollModifier),
                    verticalArrangement = Arrangement.spacedBy(ROW_GAP)
                ) {
                    fit.rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(MELD_GAP),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            row.forEach { meld ->
                                key(meld.id) {
                                    MeldGroupContainer(
                                        meld = meld,
                                        isMyTurn = isMyTurn,
                                        hasSelection = hasSelection,
                                        newTileIds = newTileIds,
                                        selectedTileIds = selectedTileIds,
                                        placedByOthers = placedByOthers,
                                        movedByOthers = movedByOthers,
                                        tileWidth = fit.tileWidth,
                                        tileHeight = fit.tileHeight,
                                        edgeWidth = fit.edgeWidth,
                                        onTileTapped = onTileTapped,
                                        onTileDropped = onTileDropped,
                                        onTargetTapped = onTargetTapped
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MELD_GAP = 7.dp
private val ROW_GAP = 6.dp
private const val MIN_TILE_DP = 27 // below this the table scrolls instead of shrinking the tiles
private const val MAX_TILE_DP = 40

/** Result of the auto-fit: tile size and the melds packed into rows. */
private class TileFit(
    val tileWidth: Dp,
    val tileHeight: Dp,
    val edgeWidth: Dp,
    val rows: List<List<MeldSet>>,
    val needsScroll: Boolean
)

private fun meldWidth(tileCount: Int, tile: Dp, edge: Dp): Dp =
    tile * tileCount + edge * 2 + 2.dp * (tileCount + 1) + 7.dp

private fun packRows(melds: List<MeldSet>, availableWidth: Dp, tile: Dp, edge: Dp): List<List<MeldSet>> {
    val rows = ArrayList<MutableList<MeldSet>>()
    var current = ArrayList<MeldSet>()
    var used = 0.dp
    for (meld in melds) {
        val w = meldWidth(meld.tiles.size, tile, edge)
        if (current.isEmpty()) {
            current += meld
            used = w
        } else if (used + MELD_GAP + w > availableWidth) {
            rows += current
            current = ArrayList()
            current += meld
            used = w
        } else {
            current += meld
            used = used + MELD_GAP + w
        }
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

/** Picks the biggest tile size for which every meld fits in [maxWidth] x [maxHeight]. */
private fun fitTiles(melds: List<MeldSet>, maxWidth: Dp, maxHeight: Dp): TileFit {
    var size = MAX_TILE_DP
    while (true) {
        val tile = size.dp
        val tileH = tile * 1.36f
        val edge = (tile * 0.3f).coerceIn(8.dp, 14.dp)
        val rows = packRows(melds, maxWidth, tile, edge)
        val rowHeight = tileH + 11.dp
        val totalHeight = rowHeight * rows.size + ROW_GAP * (rows.size - 1).coerceAtLeast(0)
        if (totalHeight <= maxHeight || size <= MIN_TILE_DP) {
            return TileFit(tile, tileH, edge, rows, needsScroll = totalHeight > maxHeight)
        }
        size -= 1
    }
}

@Composable
fun MeldGroupContainer(
    meld: MeldSet,
    isMyTurn: Boolean,
    hasSelection: Boolean,
    newTileIds: Set<String>,
    selectedTileIds: Set<String>,
    placedByOthers: Set<String>,
    movedByOthers: Set<String>,
    tileWidth: Dp,
    tileHeight: Dp,
    edgeWidth: Dp,
    onTileTapped: (Tile) -> Unit,
    onTileDropped: (tileId: String, target: DropTarget) -> Unit,
    onTargetTapped: (target: DropTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val validation = remember(meld.tiles) { BoardValidator.validateMeld(meld) }
    val borderColor = when {
        meld.tiles.size < 3 -> Color(0xFFFFA000).copy(alpha = 0.8f) // Incomplete (<3 tiles)
        validation.isValid -> Color(0xFF81C784).copy(alpha = 0.6f)  // Valid set
        else -> Color(0xFFE57373).copy(alpha = 0.9f)                // Illegal set
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(FeltMediumGreen.copy(alpha = 0.60f))
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left edge: insert at the start
        EdgeDropZone(
            enabled = isMyTurn,
            highlight = hasSelection,
            width = edgeWidth,
            height = tileHeight,
            onDropTile = { id -> onTileDropped(id, DropTarget.MeldPosition(meld.id, 0)) },
            onTap = { onTargetTapped(DropTarget.MeldPosition(meld.id, 0)) }
        )

        meld.tiles.forEachIndexed { index, tile ->
            key(tile.id) {
                val isSelected = tile.id in selectedTileIds
                val tapAction = {
                    if (hasSelection && !isSelected) {
                        onTargetTapped(DropTarget.MeldPosition(meld.id, index))
                    } else {
                        onTileTapped(tile)
                    }
                }
                // Dropping on a tile inserts before it
                DropZone(
                    enabled = isMyTurn,
                    onDropTile = { id -> onTileDropped(id, DropTarget.MeldPosition(meld.id, index)) },
                    onTap = tapAction
                ) { hovered ->
                    TileView(
                        tile = tile,
                        width = tileWidth,
                        height = tileHeight,
                        isSelected = isSelected,
                        isNewThisTurn = tile.id in newTileIds,
                        isPlacedByOpponent = tile.id in placedByOthers,
                        isMovedByOpponent = tile.id in movedByOthers,
                        isGhost = hovered,
                        modifier = Modifier.tileDragSource(
                            tileId = tile.id,
                            enabled = isMyTurn,
                            onTap = tapAction
                        )
                    )
                }
            }
        }

        // Right edge: append at the end
        EdgeDropZone(
            enabled = isMyTurn,
            highlight = hasSelection,
            width = edgeWidth,
            height = tileHeight,
            onDropTile = { id -> onTileDropped(id, DropTarget.MeldPosition(meld.id, Int.MAX_VALUE)) },
            onTap = { onTargetTapped(DropTarget.MeldPosition(meld.id, Int.MAX_VALUE)) }
        )
    }
}

@Composable
private fun EdgeDropZone(
    enabled: Boolean,
    highlight: Boolean,
    width: Dp,
    height: Dp,
    onDropTile: (String) -> Unit,
    onTap: () -> Unit
) {
    if (!enabled) {
        Spacer(modifier = Modifier.width(2.dp))
        return
    }
    DropZone(
        enabled = true,
        onDropTile = onDropTile,
        onTap = onTap,
        modifier = Modifier
            .width(width)
            .height(height)
    ) { hovered ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        hovered -> Color(0xAA00E5FF)
                        highlight -> Color(0x5500E5FF)
                        else -> Color(0x22FFFFFF)
                    }
                )
        )
    }
}
