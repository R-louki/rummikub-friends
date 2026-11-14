package com.rummikub.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a single physical tile in Rummikub.
 * Every physical tile has a unique [id] so that duplicate face-value tiles
 * (e.g. the two identical Red 7s in the deck) can be tracked accurately during snapshots,
 * drag-and-drops, and rollbacks.
 *
 * @param id Unique identifier (UUID or deterministic ID).
 * @param color Color of the tile, or null if it is an unassigned Joker.
 * @param number Numeric value 1 to 13 (0 for Joker).
 * @param isJoker True if this tile is a Joker.
 */
@Serializable
data class Tile(
    val id: String,
    val color: TileColor? = null,
    val number: Int = 0,
    val isJoker: Boolean = false
) : Comparable<Tile> {

    init {
        if (!isJoker) {
            require(number in 1..13) { "Tile number must be between 1 and 13, but was $number" }
            requireNotNull(color) { "Non-joker tile must have a color" }
        } else {
            require(number == 0) { "Joker tile must have number 0, but was $number" }
        }
    }

    /**
     * Display label for UI or logs.
     */
    val displayLabel: String
        get() = if (isJoker) "★ Joker" else "${color?.name?.first()} $number"

    override fun compareTo(other: Tile): Int {
        if (this.isJoker && other.isJoker) return this.id.compareTo(other.id)
        if (this.isJoker) return 1
        if (other.isJoker) return -1

        val colorCompare = (this.color?.ordinal ?: 0).compareTo(other.color?.ordinal ?: 0)
        if (colorCompare != 0) return colorCompare
        return this.number.compareTo(other.number)
    }

    companion object {
        /**
         * Factory for standard number tiles.
         */
        fun number(color: TileColor, number: Int, setIndex: Int = 1, id: String = "${color.name}_${number}_$setIndex"): Tile {
            return Tile(id = id, color = color, number = number, isJoker = false)
        }

        /**
         * Factory for Joker tiles.
         */
        fun joker(setIndex: Int = 1, id: String = "JOKER_$setIndex"): Tile {
            return Tile(id = id, color = null, number = 0, isJoker = true)
        }

        /**
         * Generates the complete, standard 106-tile Rummikub deck:
         * - 2 sets of numbers 1..13 for each of the 4 colors = 104 tiles
         * - 2 Jokers = 2 tiles
         * Total = 106 tiles.
         */
        fun createStandardDeck(): List<Tile> {
            val deck = ArrayList<Tile>(106)
            for (setIndex in 1..2) {
                for (color in TileColor.values()) {
                    for (number in 1..13) {
                        deck.add(number(color = color, number = number, setIndex = setIndex))
                    }
                }
            }
            deck.add(joker(setIndex = 1))
            deck.add(joker(setIndex = 2))
            return deck
        }
    }
}
