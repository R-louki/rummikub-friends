package com.rummikub.core.engine

import com.rummikub.core.model.BoardState
import com.rummikub.core.model.GameConfig
import com.rummikub.core.model.JokerPointStrategy
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile
import kotlinx.serialization.Serializable

/**
 * Result of evaluating an attempted initial meld (Première Pose).
 */
@Serializable
data class InitialMeldResult(
    val isValid: Boolean,
    val score: Int = 0,
    val requiredThreshold: Int = 30,
    val newlyPlacedMeldsCount: Int = 0,
    val newlyPlacedTiles: List<Tile> = emptyList(),
    val failureReason: String? = null
)

/**
 * Evaluates whether a player's move satisfies the Initial Meld ("Première Pose") rules:
 * - Minimum score threshold (e.g., 30 points)
 * - Strict prohibition of manipulating pre-existing board melds
 * - Joker allowance and scoring strategy based on [GameConfig]
 */
object InitialMeldEvaluator {

    /**
     * Validates an attempted initial meld.
     *
     * @param initialBoardState Board state at the beginning of the player's turn.
     * @param proposedBoardState Board state proposed at the end of the player's turn.
     * @param initialRack Player's rack at the start of the turn.
     * @param config The active game configuration.
     */
    fun evaluate(
        initialBoardState: BoardState,
        proposedBoardState: BoardState,
        initialRack: PlayerRack,
        config: GameConfig
    ): InitialMeldResult {
        // If threshold is 0, any valid placement is accepted
        val threshold = config.initialMeldThreshold

        // 1. Identify which tiles were already on the board before this turn
        val existingTileIds = initialBoardState.allTileIds

        // 2. Identify newly placed melds and verify that existing board melds were not tampered with
        val initialMeldIds = initialBoardState.melds.map { it.id }.toSet()
        val proposedMelds = proposedBoardState.melds

        // Rule: A player making their initial meld cannot touch, split or add to pre-existing board melds
        for (initialMeld in initialBoardState.melds) {
            val matchingProposed = proposedMelds.find { it.id == initialMeld.id }
            if (matchingProposed == null || matchingProposed.tiles != initialMeld.tiles) {
                return InitialMeldResult(
                    isValid = false,
                    requiredThreshold = threshold,
                    failureReason = "Interdiction de manipuler ou modifier les combinaisons existantes sur le plateau lors de la première pose."
                )
            }
        }

        // 3. Extract the new melds placed by this player
        val newMelds = proposedMelds.filterNot { it.id in initialMeldIds }
        if (newMelds.isEmpty()) {
            return InitialMeldResult(
                isValid = false,
                requiredThreshold = threshold,
                failureReason = "Aucune nouvelle combinaison n'a été déposée depuis votre chevalet."
            )
        }

        // Verify that all tiles in the new melds were sourced purely from the player's starting rack
        val newlyPlacedTiles = newMelds.flatMap { it.tiles }
        val playerStartingTileIds = initialRack.tiles.map { it.id }.toSet()

        val illegalTiles = newlyPlacedTiles.filterNot { it.id in playerStartingTileIds }
        if (illegalTiles.isNotEmpty()) {
            return InitialMeldResult(
                isValid = false,
                requiredThreshold = threshold,
                failureReason = "Des tuiles ne provenant pas de votre chevalet ont été utilisées."
            )
        }

        // 4. Validate each new meld individually using the BoardValidator
        var totalScore = 0
        for (meld in newMelds) {
            val validation = BoardValidator.validateMeld(meld, config.jokerPointStrategy)
            if (!validation.isValid) {
                return InitialMeldResult(
                    isValid = false,
                    requiredThreshold = threshold,
                    failureReason = "Combinaison invalide : ${validation.errorMessage}"
                )
            }

            // Check if Joker is allowed in initial meld
            val hasJoker = meld.tiles.any { it.isJoker }
            if (hasJoker && !config.jokerAllowedInInitialMeld) {
                return InitialMeldResult(
                    isValid = false,
                    requiredThreshold = threshold,
                    failureReason = "Les Jokers sont interdits lors de la première pose selon les règles de cette partie."
                )
            }

            totalScore += validation.points
        }

        // 5. Compare score with threshold
        if (totalScore < threshold) {
            return InitialMeldResult(
                isValid = false,
                score = totalScore,
                requiredThreshold = threshold,
                newlyPlacedMeldsCount = newMelds.size,
                newlyPlacedTiles = newlyPlacedTiles,
                failureReason = "Score insuffisant : $totalScore points obtenus sur les $threshold points requis pour la première pose."
            )
        }

        return InitialMeldResult(
            isValid = true,
            score = totalScore,
            requiredThreshold = threshold,
            newlyPlacedMeldsCount = newMelds.size,
            newlyPlacedTiles = newlyPlacedTiles
        )
    }
}
