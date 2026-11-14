package com.rummikub.core.model

import kotlinx.serialization.Serializable

/**
 * Immutable state of the game board.
 *
 * @param versionId Monotonically increasing ID tracking every modification.
 * @param melds List of melds currently placed on the board.
 * @param drawPileCount Number of tiles remaining in the draw pile (hidden from clients).
 * @param activePlayerId The player currently allowed to make moves.
 * @param turnRemainingMillis Milliseconds remaining in the active player's turn.
 * @param isGracePeriod True if active player disconnected and grace period is active.
 * @param disconnectedPlayerId ID of the player currently in grace period reconnect.
 */
@Serializable
data class BoardState(
    val versionId: Long = 0L,
    val melds: List<MeldSet> = emptyList(),
    val drawPileCount: Int = 0,
    val activePlayerId: String = "",
    val turnRemainingMillis: Long = 0L,
    val isGracePeriod: Boolean = false,
    val disconnectedPlayerId: String? = null
) {
    /**
     * All tiles currently on the board, flattened.
     */
    val allTiles: List<Tile>
        get() = melds.flatMap { it.tiles }

    /**
     * Set of all tile IDs currently on the board.
     */
    val allTileIds: Set<String>
        get() = melds.flatMap { it.tiles }.map { it.id }.toSet()

    /**
     * Finds a meld by its unique ID.
     */
    fun findMeld(meldId: String): MeldSet? = melds.find { it.id == meldId }

    /**
     * Finds which meld contains a specific tile.
     */
    fun findMeldContainingTile(tileId: String): MeldSet? =
        melds.find { it.containsTile(tileId) }

    /**
     * Creates a new BoardState with an updated meld.
     */
    fun withUpdatedMeld(updatedMeld: MeldSet): BoardState {
        val newMelds = melds.map { if (it.id == updatedMeld.id) updatedMeld else it }
        return copy(versionId = versionId + 1, melds = newMelds)
    }

    /**
     * Creates a new BoardState with an added meld.
     */
    fun withAddedMeld(newMeld: MeldSet): BoardState {
        return copy(versionId = versionId + 1, melds = melds + newMeld)
    }

    /**
     * Creates a new BoardState with a meld removed (e.g. if all its tiles were absorbed).
     */
    fun withRemovedMeld(meldId: String): BoardState {
        return copy(versionId = versionId + 1, melds = melds.filterNot { it.id == meldId })
    }

    /**
     * Prunes empty melds from the board.
     */
    fun withoutEmptyMelds(): BoardState {
        val filtered = melds.filter { it.isNotEmpty }
        return if (filtered.size != melds.size) copy(melds = filtered) else this
    }
}
