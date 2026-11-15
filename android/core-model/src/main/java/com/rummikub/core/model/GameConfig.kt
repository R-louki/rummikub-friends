package com.rummikub.core.model

import kotlinx.serialization.Serializable

/**
 * Strategy for scoring a Joker when calculating initial meld points.
 */
@Serializable
enum class JokerPointStrategy {
    REPLACED_TILE_VALUE, // Worth the number of the tile it replaces (e.g. 9 for a replaced 9)
    ZERO_POINTS          // Worth 0 points towards the initial meld
}

/**
 * Mode of board validation.
 */
@Serializable
enum class ValidationMode {
    STRICT_AUTOMATIC, // Engine enforces 100% legal combinations; rejects turn if illegal
    FREE_ARBITRATION  // "Entre Amis" mode: allows manual acceptance or group consensus rollback
}

/**
 * Synchronized room and game rules configuration chosen by the host.
 *
 * @param turnDurationSeconds Duration of each turn in seconds (0 = unlimited).
 * @param initialMeldThreshold Minimum score required to make the initial meld (default 30, 0 = free play).
 * @param jokerAllowedInInitialMeld Whether Jokers can be included in initial meld sets.
 * @param jokerPointStrategy How Joker points are counted in initial meld calculation.
 * @param validationMode Strict engine verification or friendly manual arbitration.
 * @param penaltyTilesCount Number of tiles drawn from the deck on invalid turn timeout (1 or 3).
 * @param maxPlayers Maximum players allowed in room (2 to 4).
 * @param gracePeriodSeconds Time allowed for a disconnected player to reconnect before auto-skipping (default 30s).
 * @param botCount Computer players added to the table (0 = friends only, 1..3 = play against the computer).
 */
@Serializable
data class GameConfig(
    val turnDurationSeconds: Int = 60,
    val initialMeldThreshold: Int = 30,
    val jokerAllowedInInitialMeld: Boolean = true,
    val jokerPointStrategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE,
    val validationMode: ValidationMode = ValidationMode.STRICT_AUTOMATIC,
    val penaltyTilesCount: Int = 3,
    val maxPlayers: Int = 4,
    val gracePeriodSeconds: Int = 30,
    val botCount: Int = 0
) {
    init {
        require(turnDurationSeconds >= 0) { "turnDurationSeconds must be >= 0" }
        require(initialMeldThreshold >= 0) { "initialMeldThreshold must be >= 0" }
        require(penaltyTilesCount in 0..5) { "penaltyTilesCount must be between 0 and 5" }
        require(maxPlayers in 2..4) { "maxPlayers must be between 2 and 4" }
        require(gracePeriodSeconds >= 5) { "gracePeriodSeconds must be >= 5" }
        require(botCount in 0..3) { "botCount must be between 0 and 3" }
    }

    companion object {
        val STANDARD = GameConfig()
        val FAST_PACED = GameConfig(turnDurationSeconds = 30, penaltyTilesCount = 1)
        val CASUAL_FREE = GameConfig(
            turnDurationSeconds = 90,
            initialMeldThreshold = 0,
            validationMode = ValidationMode.FREE_ARBITRATION
        )
    }
}
