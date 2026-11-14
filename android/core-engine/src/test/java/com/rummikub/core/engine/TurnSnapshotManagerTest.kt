package com.rummikub.core.engine

import com.rummikub.core.model.BoardState
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSnapshotManagerTest {

    @Test
    fun `beginTurn captures starting state correctly`() {
        val config = GameConfig.STANDARD
        val manager = TurnSnapshotManager(config)

        val t1 = Tile.number(TileColor.RED, 1)
        val rack = PlayerRack(playerId = "player_1", tiles = listOf(t1))
        val board = BoardState(versionId = 10L)

        val snapshot = manager.beginTurn(1, "player_1", board, rack)

        assertNotNull(manager.activeSnapshot)
        assertEquals(1, snapshot.turnNumber)
        assertEquals("player_1", snapshot.playerId)
        assertEquals(10L, snapshot.boardState.versionId)
    }

    @Test
    fun `valid turn commit succeeds and updates board versionId and player rack`() {
        val config = GameConfig(initialMeldThreshold = 0) // No 30-pt barrier for this test
        val manager = TurnSnapshotManager(config)

        val t1 = Tile.number(TileColor.RED, 3)
        val t2 = Tile.number(TileColor.RED, 4)
        val t3 = Tile.number(TileColor.RED, 5)
        val extra = Tile.number(TileColor.BLACK, 1)

        val initialRack = PlayerRack(
            playerId = "p1",
            tiles = listOf(t1, t2, t3, extra),
            hasMadeInitialMeld = true
        )
        val initialBoard = BoardState(versionId = 5L)
        val drawPile = mutableListOf<Tile>()

        manager.beginTurn(1, "p1", initialBoard, initialRack)

        // Proposed: place 3-4-5 on board, keep 'extra' in rack
        val proposedMeld = MeldSet(id = "meld_1", tiles = listOf(t1, t2, t3))
        val proposedBoard = BoardState(versionId = 5L, melds = listOf(proposedMeld))
        val remainingRackIds = listOf(extra.id)

        val outcome = manager.attemptCommitTurn(proposedBoard, remainingRackIds, drawPile)

        assertTrue("Outcome should be Success", outcome is CommitOutcome.Success)
        val success = outcome as CommitOutcome.Success
        assertEquals(6L, success.updatedBoardState.versionId)
        assertEquals(1, success.updatedRack.tileCount)
        assertEquals(listOf(extra), success.updatedRack.tiles)
        assertEquals(3, success.tilesPlayed.size)
        assertNull("Snapshot must be cleared after successful commit", manager.activeSnapshot)
    }

    @Test
    fun `invalid board commit triggers atomic rollback and draws penalty tiles`() {
        val config = GameConfig(penaltyTilesCount = 3)
        val manager = TurnSnapshotManager(config)

        val t1 = Tile.number(TileColor.RED, 3)
        val t2 = Tile.number(TileColor.RED, 4) // Only 2 tiles -> invalid run!

        val initialRack = PlayerRack(
            playerId = "p1",
            tiles = listOf(t1, t2),
            hasMadeInitialMeld = true
        )
        val initialBoard = BoardState(versionId = 2L, melds = emptyList())

        // Create draw pile with 3 penalty tiles
        val p1 = Tile.number(TileColor.BLUE, 1, id = "P1")
        val p2 = Tile.number(TileColor.BLUE, 2, id = "P2")
        val p3 = Tile.number(TileColor.BLUE, 3, id = "P3")
        val drawPile = mutableListOf(p1, p2, p3)

        manager.beginTurn(1, "p1", initialBoard, initialRack)

        val invalidMeld = MeldSet(id = "m1", tiles = listOf(t1, t2))
        val proposedBoard = BoardState(versionId = 2L, melds = listOf(invalidMeld))

        val outcome = manager.attemptCommitTurn(proposedBoard, emptyList(), drawPile)

        assertTrue("Outcome should be Failure", outcome is CommitOutcome.Failure)
        val failure = outcome as CommitOutcome.Failure
        val rollback = failure.rollbackOutcome

        // Board must be restored to initial melds (empty) with incremented versionId
        assertEquals(3L, rollback.restoredBoardState.versionId)
        assertTrue(rollback.restoredBoardState.melds.isEmpty())

        // Player rack must contain original tiles + 3 penalty tiles = 5 tiles
        assertEquals(5, rollback.restoredRack.tileCount)
        assertEquals(3, rollback.penaltyTiles.size)
        assertTrue(drawPile.isEmpty())
        assertNull(manager.activeSnapshot)
    }

    @Test
    fun `manual reset turn returns pristine snapshot state without penalty`() {
        val config = GameConfig.STANDARD
        val manager = TurnSnapshotManager(config)

        val t1 = Tile.number(TileColor.BLACK, 7)
        val initialRack = PlayerRack(playerId = "p1", tiles = listOf(t1))
        val initialBoard = BoardState(versionId = 1L)

        manager.beginTurn(1, "p1", initialBoard, initialRack)

        val reset = manager.manualResetTurn()
        assertNotNull(reset)
        val (restoredBoard, restoredRack) = reset!!

        assertEquals(1L, restoredBoard.versionId)
        assertEquals(1, restoredRack.tileCount)
        assertEquals(t1, restoredRack.tiles.first())
        assertNotNull("Manual reset does not destroy the active turn snapshot", manager.activeSnapshot)
    }
}
