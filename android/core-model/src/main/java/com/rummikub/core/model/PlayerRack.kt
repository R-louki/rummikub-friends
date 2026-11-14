package com.rummikub.core.model

import kotlinx.serialization.Serializable

/**
 * Represents a player's private rack of tiles.
 *
 * @param playerId Unique player ID.
 * @param playerName Player display name.
 * @param tiles Tiles currently held on the rack.
 * @param hasMadeInitialMeld True if this player has fulfilled the 30-pt (or configured) first meld.
 * @param initialMeldScore Cumulative score achieved during the initial meld turn.
 * @param isConnected Connection status of the player.
 */
@Serializable
data class PlayerRack(
    val playerId: String,
    val playerName: String = "",
    val tiles: List<Tile> = emptyList(),
    val hasMadeInitialMeld: Boolean = false,
    val initialMeldScore: Int = 0,
    val isConnected: Boolean = true
) {
    val tileCount: Int get() = tiles.size
    val isEmpty: Boolean get() = tiles.isEmpty()

    fun containsTile(tileId: String): Boolean = tiles.any { it.id == tileId }

    fun withTileAdded(tile: Tile): PlayerRack = copy(tiles = tiles + tile)

    fun withTilesAdded(newTiles: Collection<Tile>): PlayerRack = copy(tiles = tiles + newTiles)

    fun withTileRemoved(tileId: String): PlayerRack =
        copy(tiles = tiles.filterNot { it.id == tileId })

    fun withTilesRemoved(tileIds: Set<String>): PlayerRack =
        copy(tiles = tiles.filterNot { it.id in tileIds })

    /**
     * Sorts rack by Color, then Number (e.g. 7-8-9 Bleu, 3-4-5 Rouge).
     * Jokers are placed at the very end.
     */
    fun sortedByRun(): PlayerRack {
        val (jokers, nonJokers) = tiles.partition { it.isJoker }
        val sorted = nonJokers.sortedWith(
            compareBy<Tile> { it.color?.ordinal ?: Int.MAX_VALUE }
                .thenBy { it.number }
        ) + jokers
        return copy(tiles = sorted)
    }

    /**
     * Sorts rack by Number, then Color (e.g. 7 Bleu - 7 Rouge - 7 Noir).
     * Jokers are placed at the very end.
     */
    fun sortedByGroup(): PlayerRack {
        val (jokers, nonJokers) = tiles.partition { it.isJoker }
        val sorted = nonJokers.sortedWith(
            compareBy<Tile> { it.number }
                .thenBy { it.color?.ordinal ?: Int.MAX_VALUE }
        ) + jokers
        return copy(tiles = sorted)
    }
}
