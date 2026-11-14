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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (feltHovered) Modifier.background(Color(0x1A00E5FF)) else Modifier)
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (boardState.melds.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
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
            }

            // Melds laid out in wrapping rows
            val rows = remember(boardState.melds) { packMeldsIntoRows(boardState.melds, maxTilesPerRow = 12) }
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                                onTileTapped = onTileTapped,
                                onTileDropped = onTileDropped,
                                onTargetTapped = onTargetTapped
                            )
                        }
                    }
                }
            }

            // Hint strip for new melds, only when the table already has melds
            if (isMyTurn && boardState.melds.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (hasSelection) Color(0x3300E5FF) else Color(0x14FFFFFF))
                        .border(
                            width = 1.dp,
                            color = if (hasSelection) Color(0xFF00E5FF) else Color(0x44FFFFFF),
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (hasSelection) "➕ Touchez ici (ou la table) pour créer une nouvelle combinaison"
                        else "➕ Déposez une tuile sur la table libre pour créer une combinaison",
                        color = if (hasSelection) Color.White else Color(0x99FFFFFF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Extra room at the bottom so the last row is never hidden behind the rack shadow
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Greedy packing of melds into rows so that a row never exceeds [maxTilesPerRow] tiles.
 */
private fun packMeldsIntoRows(melds: List<MeldSet>, maxTilesPerRow: Int): List<List<MeldSet>> {
    val rows = ArrayList<MutableList<MeldSet>>()
    var current = ArrayList<MeldSet>()
    var currentTiles = 0
    for (meld in melds) {
        val cost = meld.tiles.size + 1 // +1 for edge drop zones
        if (current.isNotEmpty() && currentTiles + cost > maxTilesPerRow) {
            rows += current
            current = ArrayList()
            currentTiles = 0
        }
        current += meld
        currentTiles += cost
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

@Composable
fun MeldGroupContainer(
    meld: MeldSet,
    isMyTurn: Boolean,
    hasSelection: Boolean,
    newTileIds: Set<String>,
    selectedTileIds: Set<String>,
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
                        width = 38.dp,
                        height = 52.dp,
                        isSelected = isSelected,
                        isNewThisTurn = tile.id in newTileIds,
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
            onDropTile = { id -> onTileDropped(id, DropTarget.MeldPosition(meld.id, Int.MAX_VALUE)) },
            onTap = { onTargetTapped(DropTarget.MeldPosition(meld.id, Int.MAX_VALUE)) }
        )
    }
}

@Composable
private fun EdgeDropZone(
    enabled: Boolean,
    highlight: Boolean,
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
            .width(18.dp)
            .height(52.dp)
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
