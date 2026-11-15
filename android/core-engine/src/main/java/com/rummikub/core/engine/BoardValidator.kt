package com.rummikub.core.engine

import com.rummikub.core.model.BoardState
import com.rummikub.core.model.JokerPointStrategy
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.MeldType
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor

/**
 * Pure, deterministic validator for Rummikub board sets and runs.
 * Supports arbitrary Joker placement, multi-Joker sets, wrap-around prevention,
 * and exact tile resolution for scoring and UI display.
 */
object BoardValidator {

    /**
     * Validates the entire board. Every single meld on the board must be valid.
     * Empty board is considered valid (e.g. at game start).
     */
    fun validateBoard(
        boardState: BoardState,
        jokerPointStrategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE
    ): BoardValidationResult {
        if (boardState.melds.isEmpty()) {
            return BoardValidationResult.valid(emptyList())
        }

        val results = boardState.melds.map { validateMeld(it, jokerPointStrategy) }
        val allValid = results.all { it.isValid }

        return if (allValid) {
            BoardValidationResult.valid(results)
        } else {
            val invalidDescriptions = results.filterNot { it.isValid }
                .map { "Meld [${it.meldId}]: ${it.errorMessage}" }
            BoardValidationResult(
                isValid = false,
                meldResults = results,
                totalBoardPoints = results.filter { it.isValid }.sumOf { it.points },
                diagnostics = invalidDescriptions
            )
        }
    }

    /**
     * Validates an individual MeldSet (must be either a legal Group or a legal Run).
     */
    fun validateMeld(
        meld: MeldSet,
        jokerPointStrategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE
    ): MeldValidationResult {
        val tiles = meld.tiles

        // Rule: A meld must contain at least 3 tiles
        if (tiles.size < 3) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.INVALID,
                isValid = false,
                errorMessage = "Une combinaison doit contenir au moins 3 tuiles (actuel: ${tiles.size})"
            )
        }

        // Rule: A meld cannot contain more than 13 tiles
        if (tiles.size > 13) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.INVALID,
                isValid = false,
                errorMessage = "Une combinaison ne peut pas dépasser 13 tuiles (actuel: ${tiles.size})"
            )
        }

        // Try validating as Group (3 or 4 tiles of same number, different colors)
        val groupResult = validateGroup(meld, jokerPointStrategy)
        if (groupResult.isValid) {
            return groupResult
        }

        // Try validating as Run (3 to 13 consecutive numbers of same color)
        val runResult = validateRun(meld, jokerPointStrategy)
        if (runResult.isValid) {
            return runResult
        }

        // Neither Group nor Run: return detailed error
        val errorMsg = buildString {
            append("Combinaison invalide : ")
            append("Non valide en Groupe (${groupResult.errorMessage}) ")
            append("et non valide en Série (${runResult.errorMessage})")
        }

        return MeldValidationResult(
            meldId = meld.id,
            meldType = MeldType.INVALID,
            isValid = false,
            errorMessage = errorMsg
        )
    }

    /**
     * Validates whether a meld is a legal Group (Groupe / Breland ou Carré).
     * Requirements:
     * 1. 3 or 4 tiles.
     * 2. All non-jokers must share the exact same number (1..13).
     * 3. All non-jokers must have strictly distinct colors.
     * 4. Jokers represent the remaining distinct color(s) for that number.
     */
    fun validateGroup(
        meld: MeldSet,
        jokerPointStrategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE
    ): MeldValidationResult {
        val tiles = meld.tiles
        if (tiles.size !in 3..4) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.GROUP,
                isValid = false,
                errorMessage = "Un groupe doit contenir 3 ou 4 tuiles (actuel: ${tiles.size})"
            )
        }

        val nonJokers = tiles.filterNot { it.isJoker }
        val jokers = tiles.filter { it.isJoker }

        // Edge case: All tiles are Jokers (e.g. 3 or 4 jokers)
        if (nonJokers.isEmpty()) {
            val resolved = tiles.mapIndexed { index, joker ->
                Tile(
                    id = joker.id,
                    color = TileColor.values()[index % TileColor.values().size],
                    number = 10,
                    isJoker = true
                )
            }
            val pts = calculatePoints(resolved, jokerPointStrategy)
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.GROUP,
                isValid = true,
                resolvedTiles = resolved,
                points = pts
            )
        }

        // All non-jokers must have the identical face number
        val distinctNumbers = nonJokers.map { it.number }.distinct()
        if (distinctNumbers.size > 1) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.GROUP,
                isValid = false,
                errorMessage = "Les tuiles d'un groupe doivent avoir la même valeur faciale: trouvées $distinctNumbers"
            )
        }
        val groupNumber = distinctNumbers.first()

        // All non-jokers must have distinct colors
        val colorsUsed = nonJokers.mapNotNull { it.color }
        if (colorsUsed.size != colorsUsed.distinct().size) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.GROUP,
                isValid = false,
                errorMessage = "Un groupe ne peut pas contenir deux tuiles de la même couleur"
            )
        }

        // Total tiles cannot exceed available colors (4)
        if (tiles.size > 4) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.GROUP,
                isValid = false,
                errorMessage = "Un groupe ne peut pas dépasser 4 couleurs différentes"
            )
        }

        // Resolve Jokers to unused colors
        val unusedColors = TileColor.values().filterNot { it in colorsUsed }.toMutableList()
        val resolvedTiles = tiles.map { tile ->
            if (tile.isJoker) {
                val assignedColor = if (unusedColors.isNotEmpty()) unusedColors.removeAt(0) else TileColor.BLACK
                Tile(
                    id = tile.id,
                    color = assignedColor,
                    number = groupNumber,
                    isJoker = true
                )
            } else {
                tile
            }
        }

        val points = calculatePoints(resolvedTiles, jokerPointStrategy)
        return MeldValidationResult(
            meldId = meld.id,
            meldType = MeldType.GROUP,
            isValid = true,
            resolvedTiles = resolvedTiles,
            points = points
        )
    }

    /**
     * Validates whether a meld is a legal Run (Série / Suite).
     * Requirements:
     * 1. 3 to 13 tiles.
     * 2. All non-jokers must have the identical color.
     * 3. Numbers must be strictly consecutive without looping (13-1-2 forbidden).
     * 4. Resolves Jokers in interior gaps and exterior boundaries (bounded in 1..13).
     */
    fun validateRun(
        meld: MeldSet,
        jokerPointStrategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE
    ): MeldValidationResult {
        val tiles = meld.tiles
        if (tiles.size !in 3..13) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = false,
                errorMessage = "Une série doit contenir entre 3 et 13 tuiles (actuel: ${tiles.size})"
            )
        }

        val nonJokers = tiles.filterNot { it.isJoker }
        val jokerCount = tiles.count { it.isJoker }

        // All non-jokers must share the identical color
        val distinctColors = nonJokers.mapNotNull { it.color }.distinct()
        if (distinctColors.size > 1) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = false,
                errorMessage = "Toutes les tuiles d'une série doivent être de la même couleur: trouvées $distinctColors"
            )
        }
        val runColor = distinctColors.firstOrNull() ?: TileColor.RED

        // No duplicate numbers among non-jokers
        val numbers = nonJokers.map { it.number }
        if (numbers.size != numbers.distinct().size) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = false,
                errorMessage = "Une série ne peut pas contenir de doublons de numéros: $numbers"
            )
        }

        // Case A: First check if tiles are already laid out in the exact valid sequence
        val sequentialResolution = tryResolveSequentialRun(tiles, runColor)
        if (sequentialResolution != null) {
            val points = calculatePoints(sequentialResolution, jokerPointStrategy)
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = true,
                resolvedTiles = sequentialResolution,
                points = points
            )
        }

        // Case B: the numbered tiles must be laid out in ascending order (Jokers stay flexible).
        // A shuffled run such as 5-3-4 is NOT a valid run on the table.
        if (numbers != numbers.sorted()) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = false,
                errorMessage = "Les tuiles de la série ne sont pas dans l'ordre croissant: $numbers"
            )
        }
        val sortedNonJokerNumbers = numbers.sorted()
        val totalLength = tiles.size

        if (sortedNonJokerNumbers.isEmpty()) {
            // Pure Jokers: default to 1..totalLength
            val resolved = tiles.mapIndexed { index, joker ->
                Tile(
                    id = joker.id,
                    color = runColor,
                    number = index + 1,
                    isJoker = true
                )
            }
            val points = calculatePoints(resolved, jokerPointStrategy)
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = true,
                resolvedTiles = resolved,
                points = points
            )
        }

        val minNum = sortedNonJokerNumbers.first()
        val maxNum = sortedNonJokerNumbers.last()

        // Count missing numbers inside the span [minNum, maxNum]
        val internalSpan = maxNum - minNum + 1
        val internalGaps = internalSpan - sortedNonJokerNumbers.size

        if (internalGaps > jokerCount) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = false,
                errorMessage = "Écart de numéros trop grand entre $minNum et $maxNum pour le nombre de Jokers disponibles ($jokerCount Jokers pour $internalGaps trous)"
            )
        }

        // A valid run of length L can start at integer S such that:
        // 1 <= S <= minNum
        // maxNum <= S + L - 1 <= 13  ==>  maxNum - L + 1 <= S <= 14 - L
        val minPossibleStart = maxOf(1, maxNum - totalLength + 1)
        val maxPossibleStart = minOf(minNum, 14 - totalLength)

        if (minPossibleStart > maxPossibleStart) {
            return MeldValidationResult(
                meldId = meld.id,
                meldType = MeldType.RUN,
                isValid = false,
                errorMessage = "Impossible de former une suite continue dans l'intervalle [1..13] (dépassement des bornes ou bouclage interdit)"
            )
        }

        // Choose optimal starting number (preferring starting at minNum if possible)
        val chosenStart = minPossibleStart
        val resolvedTiles = resolveRunTilesForStart(tiles, runColor, chosenStart, totalLength)

        val points = calculatePoints(resolvedTiles, jokerPointStrategy)
        return MeldValidationResult(
            meldId = meld.id,
            meldType = MeldType.RUN,
            isValid = true,
            resolvedTiles = resolvedTiles,
            points = points
        )
    }

    /**
     * Checks if the tiles in their exact given order form a consecutive run.
     * Handles Jokers in their explicit placed positions.
     */
    private fun tryResolveSequentialRun(tiles: List<Tile>, runColor: TileColor): List<Tile>? {
        val totalLength = tiles.size
        // Find any non-joker tile to anchor the sequence start
        val anchorIndex = tiles.indexOfFirst { !it.isJoker }
        if (anchorIndex == -1) return null

        val anchorNumber = tiles[anchorIndex].number
        val sequenceStart = anchorNumber - anchorIndex

        // Bounds check: Sequence must be entirely within 1..13 (No wrap around)
        if (sequenceStart < 1 || (sequenceStart + totalLength - 1) > 13) {
            return null
        }

        val resolved = ArrayList<Tile>(totalLength)
        for (i in 0 until totalLength) {
            val expectedNumber = sequenceStart + i
            val tile = tiles[i]
            if (tile.isJoker) {
                resolved.add(
                    Tile(
                        id = tile.id,
                        color = runColor,
                        number = expectedNumber,
                        isJoker = true
                    )
                )
            } else {
                if (tile.number != expectedNumber || tile.color != runColor) {
                    return null
                }
                resolved.add(tile)
            }
        }
        return resolved
    }

    /**
     * Constructs the resolved tiles for an unordered set forming a run starting at [startNumber].
     */
    private fun resolveRunTilesForStart(
        tiles: List<Tile>,
        runColor: TileColor,
        startNumber: Int,
        totalLength: Int
    ): List<Tile> {
        val nonJokersByNumber = tiles.filterNot { it.isJoker }.associateBy { it.number }.toMutableMap()
        val availableJokers = tiles.filter { it.isJoker }.toMutableList()

        val resolvedList = ArrayList<Tile>(totalLength)
        for (num in startNumber until (startNumber + totalLength)) {
            val nonJoker = nonJokersByNumber[num]
            if (nonJoker != null) {
                resolvedList.add(nonJoker)
            } else {
                val joker = availableJokers.removeAt(0)
                resolvedList.add(
                    Tile(
                        id = joker.id,
                        color = runColor,
                        number = num,
                        isJoker = true
                    )
                )
            }
        }
        return resolvedList
    }

    /**
     * Calculates the point sum of a resolved meld according to the [JokerPointStrategy].
     */
    private fun calculatePoints(
        resolvedTiles: List<Tile>,
        strategy: JokerPointStrategy
    ): Int {
        return resolvedTiles.sumOf { tile ->
            if (tile.isJoker && strategy == JokerPointStrategy.ZERO_POINTS) {
                0
            } else {
                tile.number
            }
        }
    }
}
