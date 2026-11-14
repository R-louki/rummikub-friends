package com.rummikub.core.engine

import com.rummikub.core.model.BoardState
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.JokerPointStrategy
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialMeldEvaluatorTest {

    @Test
    fun `initial meld with exact 30 points succeeds`() {
        val t1 = Tile.number(TileColor.RED, 10)
        val t2 = Tile.number(TileColor.BLUE, 10)
        val t3 = Tile.number(TileColor.BLACK, 10)

        val initialBoard = BoardState()
        val initialRack = PlayerRack(
            playerId = "player_1",
            tiles = listOf(t1, t2, t3, Tile.number(TileColor.YELLOW, 1))
        )

        val proposedMeld = MeldSet(id = "meld_1", tiles = listOf(t1, t2, t3))
        val proposedBoard = BoardState(melds = listOf(proposedMeld))

        val config = GameConfig(initialMeldThreshold = 30)
        val result = InitialMeldEvaluator.evaluate(initialBoard, proposedBoard, initialRack, config)

        assertTrue("Initial meld of 30 pts should succeed", result.isValid)
        assertEquals(30, result.score)
    }

    @Test
    fun `initial meld with 27 points fails when threshold is 30`() {
        val t1 = Tile.number(TileColor.RED, 9)
        val t2 = Tile.number(TileColor.BLUE, 9)
        val t3 = Tile.number(TileColor.BLACK, 9)

        val initialBoard = BoardState()
        val initialRack = PlayerRack(
            playerId = "player_1",
            tiles = listOf(t1, t2, t3)
        )

        val proposedMeld = MeldSet(id = "meld_1", tiles = listOf(t1, t2, t3))
        val proposedBoard = BoardState(melds = listOf(proposedMeld))

        val config = GameConfig(initialMeldThreshold = 30)
        val result = InitialMeldEvaluator.evaluate(initialBoard, proposedBoard, initialRack, config)

        assertFalse("Initial meld of 27 pts should fail threshold 30", result.isValid)
        assertEquals(27, result.score)
        assertTrue(result.failureReason!!.contains("Score insuffisant"))
    }

    @Test
    fun `initial meld with multiple melds totaling over 30 points succeeds`() {
        // Run: Red 7-8-9 = 24 points
        val r7 = Tile.number(TileColor.RED, 7)
        val r8 = Tile.number(TileColor.RED, 8)
        val r9 = Tile.number(TileColor.RED, 9)

        // Group: Blue 3, Black 3, Yellow 3 = 9 points
        val b3 = Tile.number(TileColor.BLUE, 3)
        val k3 = Tile.number(TileColor.BLACK, 3)
        val y3 = Tile.number(TileColor.YELLOW, 3)

        val initialBoard = BoardState()
        val initialRack = PlayerRack(
            playerId = "player_1",
            tiles = listOf(r7, r8, r9, b3, k3, y3)
        )

        val meld1 = MeldSet(id = "m1", tiles = listOf(r7, r8, r9))
        val meld2 = MeldSet(id = "m2", tiles = listOf(b3, k3, y3))
        val proposedBoard = BoardState(melds = listOf(meld1, meld2))

        val config = GameConfig(initialMeldThreshold = 30)
        val result = InitialMeldEvaluator.evaluate(initialBoard, proposedBoard, initialRack, config)

        assertTrue("Total 24 + 9 = 33 pts should succeed", result.isValid)
        assertEquals(33, result.score)
        assertEquals(2, result.newlyPlacedMeldsCount)
    }

    @Test
    fun `initial meld fails if player modifies existing board melds`() {
        // Board already has an existing meld placed by another player
        val existingTile1 = Tile.number(TileColor.RED, 5)
        val existingTile2 = Tile.number(TileColor.BLUE, 5)
        val existingTile3 = Tile.number(TileColor.BLACK, 5)
        val existingMeld = MeldSet(id = "existing_1", tiles = listOf(existingTile1, existingTile2, existingTile3))
        val initialBoard = BoardState(melds = listOf(existingMeld))

        // Player has Yellow 5 and 10-10-10
        val myY5 = Tile.number(TileColor.YELLOW, 5)
        val t1 = Tile.number(TileColor.RED, 10)
        val t2 = Tile.number(TileColor.BLUE, 10)
        val t3 = Tile.number(TileColor.BLACK, 10)
        val initialRack = PlayerRack(playerId = "p1", tiles = listOf(myY5, t1, t2, t3))

        // Player illegally adds Yellow 5 to existing meld to make a group of 4
        val tamperedMeld = existingMeld.withTileAdded(myY5)
        val legalNewMeld = MeldSet(id = "new_1", tiles = listOf(t1, t2, t3))
        val proposedBoard = BoardState(melds = listOf(tamperedMeld, legalNewMeld))

        val config = GameConfig(initialMeldThreshold = 30)
        val result = InitialMeldEvaluator.evaluate(initialBoard, proposedBoard, initialRack, config)

        assertFalse("Tampering with pre-existing melds on initial meld is strictly forbidden", result.isValid)
        assertTrue(result.failureReason!!.contains("Interdiction de manipuler"))
    }

    @Test
    fun `initial meld with joker disallowed in config fails`() {
        val t1 = Tile.number(TileColor.RED, 10)
        val t2 = Tile.number(TileColor.BLUE, 10)
        val joker = Tile.joker(1)

        val initialBoard = BoardState()
        val initialRack = PlayerRack(playerId = "p1", tiles = listOf(t1, t2, joker))

        val proposedMeld = MeldSet(id = "m1", tiles = listOf(t1, t2, joker))
        val proposedBoard = BoardState(melds = listOf(proposedMeld))

        val config = GameConfig(initialMeldThreshold = 30, jokerAllowedInInitialMeld = false)
        val result = InitialMeldEvaluator.evaluate(initialBoard, proposedBoard, initialRack, config)

        assertFalse("Joker must be rejected when jokerAllowedInInitialMeld is false", result.isValid)
        assertTrue(result.failureReason!!.contains("Les Jokers sont interdits"))
    }

    @Test
    fun `initial meld with joker allowed and replaced value strategy succeeds`() {
        val t1 = Tile.number(TileColor.RED, 10)
        val t2 = Tile.number(TileColor.BLUE, 10)
        val joker = Tile.joker(1)

        val initialBoard = BoardState()
        val initialRack = PlayerRack(playerId = "p1", tiles = listOf(t1, t2, joker))

        val proposedMeld = MeldSet(id = "m1", tiles = listOf(t1, t2, joker))
        val proposedBoard = BoardState(melds = listOf(proposedMeld))

        val config = GameConfig(
            initialMeldThreshold = 30,
            jokerAllowedInInitialMeld = true,
            jokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE
        )
        val result = InitialMeldEvaluator.evaluate(initialBoard, proposedBoard, initialRack, config)

        assertTrue("Joker should replace 10 and give 30 pts", result.isValid)
        assertEquals(30, result.score)
    }

    @Test
    fun `initial meld with zero-point joker strategy fails to reach threshold`() {
        val t1 = Tile.number(TileColor.RED, 10)
        val t2 = Tile.number(TileColor.BLUE, 10)
        val joker = Tile.joker(1)

        val initialBoard = BoardState()
        val initialRack = PlayerRack(playerId = "p1", tiles = listOf(t1, t2, joker))

        val proposedMeld = MeldSet(id = "m1", tiles = listOf(t1, t2, joker))
        val proposedBoard = BoardState(melds = listOf(proposedMeld))

        val config = GameConfig(
            initialMeldThreshold = 30,
            jokerAllowedInInitialMeld = true,
            jokerPointStrategy = JokerPointStrategy.ZERO_POINTS
        )
        val result = InitialMeldEvaluator.evaluate(initialBoard, proposedBoard, initialRack, config)

        assertFalse("Zero points joker leaves total at 20 points, failing 30-pt threshold", result.isValid)
        assertEquals(20, result.score)
    }
}
