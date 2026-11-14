package com.rummikub.core.engine

import com.rummikub.core.model.BoardState
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile
import kotlinx.serialization.Serializable

/**
 * Immutable snapshot of the game state at the exact moment a player's turn begins.
 */
@Serializable
data class TurnSnapshot(
    val turnNumber: Int,
    val playerId: String,
    val boardState: BoardState,
    val playerRack: PlayerRack,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Outcome of an atomic rollback.
 */
@Serializable
data class RollbackOutcome(
    val playerId: String,
    val reason: String,
    val restoredBoardState: BoardState,
    val restoredRack: PlayerRack,
    val penaltyTiles: List<Tile>
)

/**
 * Outcome of attempting to commit a turn.
 */
sealed class CommitOutcome {
    data class Success(
        val updatedBoardState: BoardState,
        val updatedRack: PlayerRack,
        val tilesPlayed: List<Tile>,
        val madeInitialMeldThisTurn: Boolean
    ) : CommitOutcome()

    data class Failure(
        val reason: String,
        val rollbackOutcome: RollbackOutcome
    ) : CommitOutcome()
}

/**
 * Manages start-of-turn snapshots, real-time intermediate state tracking,
 * and atomic rollback on invalid moves or timer expiration.
 */
class TurnSnapshotManager(
    private val config: GameConfig
) {
    var activeSnapshot: TurnSnapshot? = null
        private set

    /**
     * Captures the authoritative snapshot at the beginning of a turn.
     */
    fun beginTurn(
        turnNumber: Int,
        playerId: String,
        boardState: BoardState,
        playerRack: PlayerRack
    ): TurnSnapshot {
        val snapshot = TurnSnapshot(
            turnNumber = turnNumber,
            playerId = playerId,
            boardState = boardState,
            playerRack = playerRack,
            timestamp = System.currentTimeMillis()
        )
        activeSnapshot = snapshot
        return snapshot
    }

    /**
     * Attempts to commit the turn with proposed board state and remaining rack.
     * If valid, commits changes. If invalid, executes atomic rollback automatically.
     */
    fun attemptCommitTurn(
        proposedBoardState: BoardState,
        remainingRackTileIds: List<String>,
        drawPile: MutableList<Tile>
    ): CommitOutcome {
        val snapshot = requireNotNull(activeSnapshot) { "No active turn snapshot to commit" }
        val playerId = snapshot.playerId
        val initialRack = snapshot.playerRack
        val initialBoard = snapshot.boardState

        // 1. Reconstruct current player's rack based on remaining IDs
        val initialTileMap = initialRack.tiles.associateBy { it.id }
        val boardTileIdsBefore = initialBoard.allTileIds

        // Determine tiles played from rack to board
        val remainingTiles = remainingRackTileIds.mapNotNull { initialTileMap[it] }
        val playedFromRack = initialRack.tiles.filterNot { it.id in remainingRackTileIds }

        // Must have played at least 1 tile from rack to commit a turn without drawing
        if (playedFromRack.isEmpty()) {
            val rollback = executeAtomicRollback(
                reason = "Aucune tuile n'a été jouée depuis le chevalet. Vous devez piocher une tuile si vous ne jouez pas.",
                drawPile = drawPile,
                applyPenalty = false
            )
            return CommitOutcome.Failure(
                reason = "Aucune tuile jouée",
                rollbackOutcome = rollback
            )
        }

        // 2. Validate Board Combinations (Must be 100% legal)
        val boardValidation = BoardValidator.validateBoard(proposedBoardState, config.jokerPointStrategy)
        if (!boardValidation.isValid) {
            val rollback = executeAtomicRollback(
                reason = "Combinaisons invalides sur le plateau : ${boardValidation.diagnostics.joinToString("; ")}",
                drawPile = drawPile,
                applyPenalty = true
            )
            return CommitOutcome.Failure(
                reason = "Plateau invalide",
                rollbackOutcome = rollback
            )
        }

        // 3. Initial Meld Check (if player has not yet made initial meld)
        var madeInitialMeldThisTurn = false
        var updatedRack = initialRack.copy(tiles = remainingTiles)

        if (!initialRack.hasMadeInitialMeld) {
            val initialMeldResult = InitialMeldEvaluator.evaluate(
                initialBoardState = initialBoard,
                proposedBoardState = proposedBoardState,
                initialRack = initialRack,
                config = config
            )

            if (!initialMeldResult.isValid) {
                val rollback = executeAtomicRollback(
                    reason = initialMeldResult.failureReason ?: "Première pose non conforme aux règles",
                    drawPile = drawPile,
                    applyPenalty = true
                )
                return CommitOutcome.Failure(
                    reason = initialMeldResult.failureReason ?: "Première pose non conforme",
                    rollbackOutcome = rollback
                )
            }

            madeInitialMeldThisTurn = true
            updatedRack = updatedRack.copy(
                hasMadeInitialMeld = true,
                initialMeldScore = initialMeldResult.score
            )
        }

        // Commit successful: increment versionId to signal atomic update
        val committedBoard = proposedBoardState.copy(
            versionId = initialBoard.versionId + 1,
            turnRemainingMillis = 0L,
            isGracePeriod = false,
            disconnectedPlayerId = null
        ).withoutEmptyMelds()

        activeSnapshot = null
        return CommitOutcome.Success(
            updatedBoardState = committedBoard,
            updatedRack = updatedRack,
            tilesPlayed = playedFromRack,
            madeInitialMeldThisTurn = madeInitialMeldThisTurn
        )
    }

    /**
     * Executes an atomic rollback back to [activeSnapshot].
     * Restores the board state, restores the player's rack,
     * and optionally draws penalty tiles from [drawPile].
     */
    fun executeAtomicRollback(
        reason: String,
        drawPile: MutableList<Tile>,
        applyPenalty: Boolean = true
    ): RollbackOutcome {
        val snapshot = requireNotNull(activeSnapshot) { "No active turn snapshot to rollback" }
        val penaltyTiles = ArrayList<Tile>()

        if (applyPenalty && config.penaltyTilesCount > 0) {
            val drawCount = minOf(config.penaltyTilesCount, drawPile.size)
            for (i in 0 until drawCount) {
                penaltyTiles.add(drawPile.removeAt(0))
            }
        }

        // Restored rack = starting rack + penalty tiles
        val restoredRack = snapshot.playerRack.withTilesAdded(penaltyTiles)

        // Restored board = starting board with bumped versionId so all clients resync instantly
        val restoredBoard = snapshot.boardState.copy(
            versionId = snapshot.boardState.versionId + 1,
            turnRemainingMillis = 0L,
            isGracePeriod = false,
            disconnectedPlayerId = null
        )

        activeSnapshot = null
        return RollbackOutcome(
            playerId = snapshot.playerId,
            reason = reason,
            restoredBoardState = restoredBoard,
            restoredRack = restoredRack,
            penaltyTiles = penaltyTiles
        )
    }

    /**
     * Resets the active turn snapshot manually (e.g. player clicked "Undo All / Reset Turn" button).
     * Does NOT draw penalty tiles.
     */
    fun manualResetTurn(): Pair<BoardState, PlayerRack>? {
        val snapshot = activeSnapshot ?: return null
        return Pair(snapshot.boardState, snapshot.playerRack)
    }
}
