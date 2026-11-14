package com.rummikub.core.engine

import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.MeldType
import com.rummikub.core.model.Tile
import kotlinx.serialization.Serializable

/**
 * Result of validating an individual meld.
 */
@Serializable
data class MeldValidationResult(
    val meldId: String,
    val meldType: MeldType,
    val isValid: Boolean,
    val errorMessage: String? = null,
    val resolvedTiles: List<Tile> = emptyList(),
    val points: Int = 0
)

/**
 * Overall result of validating the entire game board.
 */
@Serializable
data class BoardValidationResult(
    val isValid: Boolean,
    val meldResults: List<MeldValidationResult> = emptyList(),
    val totalBoardPoints: Int = 0,
    val diagnostics: List<String> = emptyList()
) {
    val invalidMelds: List<MeldValidationResult>
        get() = meldResults.filterNot { it.isValid }

    companion object {
        fun valid(meldResults: List<MeldValidationResult>): BoardValidationResult {
            val totalPts = meldResults.sumOf { it.points }
            return BoardValidationResult(
                isValid = true,
                meldResults = meldResults,
                totalBoardPoints = totalPts
            )
        }

        fun invalid(meldResults: List<MeldValidationResult>, error: String): BoardValidationResult {
            return BoardValidationResult(
                isValid = false,
                meldResults = meldResults,
                diagnostics = listOf(error)
            )
        }
    }
}
