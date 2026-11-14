package com.rummikub.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class MeldType {
    RUN,      // Série: consecutive numbers, same color
    GROUP,    // Groupe: same number, distinct colors
    INVALID,  // Not a valid run or group
    EMPTY     // No tiles
}

/**
 * Represents a group of tiles laid out together on the table (either a Run or a Group).
 *
 * @param id Unique ID of the meld on the board.
 * @param tiles Ordered list of tiles in this meld.
 * @param gridRow Row index on the board grid (0..N).
 * @param gridCol Column index on the board grid (0..M).
 */
@Serializable
data class MeldSet(
    val id: String = UUID.randomUUID().toString(),
    val tiles: List<Tile> = emptyList(),
    val gridRow: Int = 0,
    val gridCol: Int = 0
) {
    val size: Int get() = tiles.size
    val isEmpty: Boolean get() = tiles.isEmpty()
    val isNotEmpty: Boolean get() = tiles.isNotEmpty()

    /**
     * Check if a specific tile by ID is present in this meld.
     */
    fun containsTile(tileId: String): Boolean = tiles.any { it.id == tileId }

    /**
     * Returns a new MeldSet with the tile appended.
     */
    fun withTileAdded(tile: Tile): MeldSet = copy(tiles = tiles + tile)

    /**
     * Returns a new MeldSet with the tile inserted at a specific index.
     */
    fun withTileInserted(index: Int, tile: Tile): MeldSet {
        val newTiles = tiles.toMutableList()
        val clampedIndex = index.coerceIn(0, newTiles.size)
        newTiles.add(clampedIndex, tile)
        return copy(tiles = newTiles)
    }

    /**
     * Returns a new MeldSet with the tile removed.
     */
    fun withTileRemoved(tileId: String): MeldSet =
        copy(tiles = tiles.filterNot { it.id == tileId })

    /**
     * Replaces a tile in this meld.
     */
    fun withTileReplaced(oldTileId: String, newTile: Tile): MeldSet =
        copy(tiles = tiles.map { if (it.id == oldTileId) newTile else it })
}
