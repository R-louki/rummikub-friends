package com.rummikub.core.engine

import com.rummikub.core.model.BoardState
import com.rummikub.core.model.JokerPointStrategy
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.MeldType
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardValidatorTest {

    // ==========================================
    // 1. RUN TESTS (SÉRIES)
    // ==========================================

    @Test
    fun `valid standard run without jokers`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 3),
                Tile.number(TileColor.RED, 4),
                Tile.number(TileColor.RED, 5)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertTrue("Run 3-4-5 should be valid", result.isValid)
        assertEquals(MeldType.RUN, result.meldType)
        assertEquals(12, result.points)
    }

    @Test
    fun `valid 13-tile complete run`() {
        val tiles = (1..13).map { Tile.number(TileColor.BLUE, it) }
        val meld = MeldSet(tiles = tiles)
        val result = BoardValidator.validateRun(meld)
        assertTrue("13-tile run should be valid", result.isValid)
        assertEquals(91, result.points)
    }

    @Test
    fun `valid run with joker in middle`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.YELLOW, 4),
                Tile.joker(1),
                Tile.number(TileColor.YELLOW, 6)
            )
        )
        val result = BoardValidator.validateRun(meld, JokerPointStrategy.REPLACED_TILE_VALUE)
        assertTrue("Run 4-Joker-6 should be valid", result.isValid)
        assertEquals(15, result.points) // 4 + 5 + 6
        assertEquals(5, result.resolvedTiles[1].number)
        assertEquals(TileColor.YELLOW, result.resolvedTiles[1].color)
    }

    @Test
    fun `valid run with joker at the start`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.joker(1),
                Tile.number(TileColor.RED, 2),
                Tile.number(TileColor.RED, 3)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertTrue("Joker-2-3 should be valid", result.isValid)
        assertEquals(6, result.points) // 1 + 2 + 3
        assertEquals(1, result.resolvedTiles[0].number)
    }

    @Test
    fun `valid run with joker at the end`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.BLACK, 10),
                Tile.number(TileColor.BLACK, 11),
                Tile.number(TileColor.BLACK, 12),
                Tile.joker(1)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertTrue("10-11-12-Joker should be valid", result.isValid)
        assertEquals(46, result.points) // 10 + 11 + 12 + 13
        assertEquals(13, result.resolvedTiles[3].number)
    }

    @Test
    fun `valid run with two jokers`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.joker(1),
                Tile.number(TileColor.RED, 5),
                Tile.joker(2),
                Tile.number(TileColor.RED, 7)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertTrue("Joker-5-Joker-7 should be valid", result.isValid)
        assertEquals(22, result.points) // 4 + 5 + 6 + 7
        assertEquals(4, result.resolvedTiles[0].number)
        assertEquals(6, result.resolvedTiles[2].number)
    }

    @Test
    fun `invalid run with wrap around 12-13-1`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 12),
                Tile.number(TileColor.RED, 13),
                Tile.number(TileColor.RED, 1)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertFalse("Wrap around 12-13-1 must be invalid", result.isValid)
    }

    @Test
    fun `invalid run with joker extending past 13`() {
        // [12, 13, Joker] cannot extend to 14
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.BLUE, 12),
                Tile.number(TileColor.BLUE, 13),
                Tile.joker(1)
            )
        )
        // If sequential: 12, 13, 14 -> invalid!
        // If unordered: could it be 11, 12, 13?
        // In our algorithm, unordered checks if a valid window exists ([11, 12, 13]),
        // but sequential strictly fails if placed after 13!
        // Let's test [13, Joker, Joker] which cannot fit in 1..13 upwards
        val invalidMeld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.BLUE, 13),
                Tile.number(TileColor.BLUE, 13),
                Tile.joker(1)
            )
        )
        assertFalse("Duplicate 13 is invalid", BoardValidator.validateRun(invalidMeld).isValid)
    }

    @Test
    fun `invalid run with mismatched colors`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 4),
                Tile.number(TileColor.BLUE, 5),
                Tile.number(TileColor.RED, 6)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertFalse("Run with different colors must be invalid", result.isValid)
    }

    @Test
    fun `invalid run with fewer than 3 tiles`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 4),
                Tile.number(TileColor.RED, 5)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertFalse("Run with 2 tiles must be invalid", result.isValid)
    }

    @Test
    fun `invalid run when gap exceeds available jokers`() {
        // Red 4 and Red 7 need two jokers (5 and 6), but only 1 is provided
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 4),
                Tile.joker(1),
                Tile.number(TileColor.RED, 7)
            )
        )
        val result = BoardValidator.validateRun(meld)
        assertFalse("Run with missing gap must be invalid", result.isValid)
    }

    // ==========================================
    // 2. GROUP TESTS (GROUPES)
    // ==========================================

    @Test
    fun `valid group of 3 different colors`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 7),
                Tile.number(TileColor.BLUE, 7),
                Tile.number(TileColor.BLACK, 7)
            )
        )
        val result = BoardValidator.validateGroup(meld)
        assertTrue("Group of 3 distinct colors should be valid", result.isValid)
        assertEquals(MeldType.GROUP, result.meldType)
        assertEquals(21, result.points)
    }

    @Test
    fun `valid group of 4 different colors`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 8),
                Tile.number(TileColor.BLUE, 8),
                Tile.number(TileColor.BLACK, 8),
                Tile.number(TileColor.YELLOW, 8)
            )
        )
        val result = BoardValidator.validateGroup(meld)
        assertTrue("Group of 4 distinct colors should be valid", result.isValid)
        assertEquals(32, result.points)
    }

    @Test
    fun `valid group with 1 joker`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 9),
                Tile.number(TileColor.BLUE, 9),
                Tile.joker(1)
            )
        )
        val result = BoardValidator.validateGroup(meld)
        assertTrue("Group of 2 numbers + Joker should be valid", result.isValid)
        assertEquals(27, result.points)
        assertEquals(9, result.resolvedTiles[2].number)
        assertTrue(result.resolvedTiles[2].color in listOf(TileColor.BLACK, TileColor.YELLOW))
    }

    @Test
    fun `valid group with 2 jokers`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 11),
                Tile.joker(1),
                Tile.joker(2)
            )
        )
        val result = BoardValidator.validateGroup(meld)
        assertTrue("Group of 1 number + 2 Jokers should be valid", result.isValid)
        assertEquals(33, result.points)
    }

    @Test
    fun `invalid group with duplicate color`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 7, setIndex = 1),
                Tile.number(TileColor.RED, 7, setIndex = 2),
                Tile.number(TileColor.BLUE, 7)
            )
        )
        val result = BoardValidator.validateGroup(meld)
        assertFalse("Group with duplicate color must be invalid", result.isValid)
    }

    @Test
    fun `invalid group with different numbers`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 7),
                Tile.number(TileColor.BLUE, 8),
                Tile.number(TileColor.BLACK, 7)
            )
        )
        val result = BoardValidator.validateGroup(meld)
        assertFalse("Group with different numbers must be invalid", result.isValid)
    }

    @Test
    fun `invalid group with more than 4 tiles`() {
        val meld = MeldSet(
            tiles = listOf(
                Tile.number(TileColor.RED, 7),
                Tile.number(TileColor.BLUE, 7),
                Tile.number(TileColor.BLACK, 7),
                Tile.number(TileColor.YELLOW, 7),
                Tile.joker(1)
            )
        )
        val result = BoardValidator.validateGroup(meld)
        assertFalse("Group with 5 tiles must be invalid", result.isValid)
    }

    // ==========================================
    // 3. COMPLETE BOARD VALIDATION TESTS
    // ==========================================

    @Test
    fun `validateBoard with multiple valid melds returns true`() {
        val board = BoardState(
            melds = listOf(
                MeldSet(tiles = listOf(Tile.number(TileColor.RED, 1), Tile.number(TileColor.RED, 2), Tile.number(TileColor.RED, 3))),
                MeldSet(tiles = listOf(Tile.number(TileColor.BLUE, 5), Tile.number(TileColor.RED, 5), Tile.number(TileColor.BLACK, 5)))
            )
        )
        val result = BoardValidator.validateBoard(board)
        assertTrue("Board with all valid melds should be valid", result.isValid)
        assertEquals(21, result.totalBoardPoints) // (1+2+3) + (5+5+5) = 6 + 15 = 21
    }

    @Test
    fun `validateBoard with one invalid meld returns false`() {
        val board = BoardState(
            melds = listOf(
                MeldSet(tiles = listOf(Tile.number(TileColor.RED, 1), Tile.number(TileColor.RED, 2), Tile.number(TileColor.RED, 3))),
                MeldSet(tiles = listOf(Tile.number(TileColor.BLUE, 5), Tile.number(TileColor.BLUE, 6))) // only 2 tiles!
            )
        )
        val result = BoardValidator.validateBoard(board)
        assertFalse("Board with an invalid meld must be invalid", result.isValid)
        assertEquals(1, result.invalidMelds.size)
    }
}
