package com.rummikub.app.game

import com.rummikub.core.engine.BoardValidator
import com.rummikub.core.model.BoardState
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.PlayerRack
import com.rummikub.core.model.Tile
import com.rummikub.core.network.GameNetworkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Where a tile can be dropped (or sent by tap) on the table.
 */
sealed class DropTarget {
    /** Create a brand new meld with the tile(s). */
    object NewMeld : DropTarget()

    /** Insert the tile into an existing meld at [index] (clamped, use Int.MAX_VALUE for the end). */
    data class MeldPosition(val meldId: String, val index: Int) : DropTarget()

    /** Put the tile back on the player's rack (only allowed for tiles played this turn). */
    object Rack : DropTarget()
}

/**
 * Live summary of the working turn, used by the HUD.
 */
data class TurnSummary(
    val tilesPlaced: Int = 0,
    val newMeldPoints: Int = 0,
    val invalidMeldCount: Int = 0,
    val isDirty: Boolean = false
)

/**
 * Holds the local, in-progress state of the active player's turn:
 * the working board and rack that the user edits by moving tiles around,
 * plus the start-of-turn snapshot used by "Annuler Coup".
 *
 * The authoritative state always comes from [GameNetworkRepository]; whenever the server
 * pushes a new board or rack, the working state is reset to it.
 */
class TurnController(
    private val repository: GameNetworkRepository,
    private val scope: CoroutineScope
) {
    private val _board = MutableStateFlow(BoardState())
    val board: StateFlow<BoardState> = _board.asStateFlow()

    private val _rack = MutableStateFlow(PlayerRack(playerId = ""))
    val rack: StateFlow<PlayerRack> = _rack.asStateFlow()

    /** Bumped when the rack is re-sorted so the UI re-lays the tiles out (free placement is UI-only). */
    private val _rackEpoch = MutableStateFlow(0)
    val rackEpoch: StateFlow<Int> = _rackEpoch.asStateFlow()

    /** Selected tiles, in selection order (tap mode supports multi-selection). */
    private val _selectedTileIds = MutableStateFlow<List<String>>(emptyList())
    val selectedTileIds: StateFlow<List<String>> = _selectedTileIds.asStateFlow()

    /** Previous (table, rack) states of this turn, for "undo the last move". */
    private val history = ArrayDeque<Pair<BoardState, PlayerRack>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private fun clearHistory() {
        history.clear()
        _canUndo.value = false
    }

    /** Takes back only the last tile move of this turn (the other moves stay). */
    fun undoLast() {
        val previous = history.removeLastOrNull() ?: return
        _board.value = previous.first
        _rack.value = previous.second
        _selectedTileIds.value = emptyList()
        broadcastWorkingBoard()
        _canUndo.value = history.isNotEmpty()
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var snapshotBoard: BoardState = BoardState()
        set(value) { field = value; _snapshotVersion.value++ }
    private var snapshotRack: PlayerRack = PlayerRack(playerId = "")
        set(value) { field = value; _snapshotVersion.value++ }

    /** Bumped each time the start-of-turn snapshot changes, so derived flows recompute even if the working state looks equal. */
    private val _snapshotVersion = MutableStateFlow(0)

    /** IDs of the tiles that were on the rack at the start of the turn (the only ones that may come back). */
    private val snapshotRackIds: Set<String>
        get() = snapshotRack.tiles.map { it.id }.toSet()

    /** IDs of the tiles that were already on the table at the start of the turn. */
    private val snapshotBoardTileIds: Set<String>
        get() = snapshotBoard.allTileIds

    /** Tiles currently on the table that were not there at the start of the turn. */
    val newBoardTileIds: StateFlow<Set<String>> = combine(_board, _rack, _snapshotVersion) { board, _, _ -> board.allTileIds - snapshotBoardTileIds }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val summary: StateFlow<TurnSummary> = combine(_board, _rack, _snapshotVersion) { board, rack, _ -> computeSummary(board, rack) }
        .stateIn(scope, SharingStarted.Eagerly, TurnSummary())

    init {
        scope.launch {
            repository.boardState.collect { server ->
                val sameTurn = server.versionId == snapshotBoard.versionId &&
                    server.activePlayerId == snapshotBoard.activePlayerId &&
                    server.melds == snapshotBoard.melds
                if (sameTurn) {
                    // Nothing changed on the server (e.g. a reconnection snapshot): keep the player's work.
                    snapshotBoard = server
                } else {
                    snapshotBoard = server
                    _board.value = server
                    _selectedTileIds.value = emptyList()
                    clearHistory()
                }
                dropStaleWork()
            }
        }
        scope.launch {
            repository.playerRack.collect { server ->
                val sameRack = server.tiles.map { it.id }.toSet() == snapshotRack.tiles.map { it.id }.toSet() &&
                    server.hasMadeInitialMeld == snapshotRack.hasMadeInitialMeld
                if (sameRack) {
                    snapshotRack = server.copy(tiles = snapshotRack.tiles)
                    return@collect
                }
                val merged = mergeKeepingOrder(_rack.value.tiles, server.tiles)
                snapshotRack = server.copy(tiles = merged)
                _rack.value = snapshotRack
                _selectedTileIds.value = emptyList()
                clearHistory()
                dropStaleWork()
            }
        }
    }

    /**
     * The in-progress arrangement may only contain tiles of the start-of-turn table and rack.
     * Anything else is left over from another game or round (new deal, restart): throw it away so
     * Valider never sends tiles the server does not know.
     */
    private fun dropStaleWork() {
        val allowed = snapshotBoard.allTileIds + snapshotRack.tiles.map { it.id }
        val working = _board.value.allTileIds + _rack.value.tiles.map { it.id }
        if (!allowed.containsAll(working)) {
            clearHistory()
            _board.value = snapshotBoard
            _rack.value = snapshotRack
            _selectedTileIds.value = emptyList()
        }
    }

    // ==========================================
    // SELECTION (tap mode)
    // ==========================================

    fun toggleSelection(tileId: String) {
        val current = _selectedTileIds.value
        _selectedTileIds.value = if (tileId in current) current - tileId else current + tileId
    }

    fun clearSelection() {
        _selectedTileIds.value = emptyList()
    }

    /** Sends the selected tile(s) to [target]. Returns false if nothing was selected. */
    fun sendSelectedTo(target: DropTarget): Boolean {
        val selected = _selectedTileIds.value
        if (selected.isEmpty()) return false
        moveTiles(selected, target)
        return true
    }

    // ==========================================
    // TILE MOVES
    // ==========================================

    /**
     * A tile was dragged and dropped. If it is part of a multi-tile selection, the whole selection
     * travels with it (in selection order), otherwise only that tile moves.
     */
    fun dropTile(tileId: String, target: DropTarget) {
        val selected = _selectedTileIds.value
        if (tileId in selected && selected.size > 1) moveTiles(selected, target) else moveTile(tileId, target)
    }

    /** Moves several tiles as a block: together into one new meld, or one after another into a meld. */
    fun moveTiles(tileIds: List<String>, target: DropTarget) {
        if (tileIds.isEmpty()) return
        if (tileIds.size == 1) {
            moveTile(tileIds[0], target)
            return
        }
        when (target) {
            DropTarget.NewMeld -> {
                moveTile(tileIds[0], DropTarget.NewMeld)
                val newMeld = _board.value.findMeldContainingTile(tileIds[0]) ?: return
                for (id in tileIds.drop(1)) {
                    moveTile(id, DropTarget.MeldPosition(newMeld.id, Int.MAX_VALUE))
                }
            }
            is DropTarget.MeldPosition -> {
                tileIds.forEachIndexed { i, id ->
                    val index = if (target.index == Int.MAX_VALUE) Int.MAX_VALUE else target.index + i
                    moveTile(id, DropTarget.MeldPosition(target.meldId, index))
                }
            }
            DropTarget.Rack -> tileIds.forEach { moveTile(it, DropTarget.Rack) }
        }
        _selectedTileIds.value = emptyList()
    }

    fun moveTile(tileId: String, target: DropTarget) {
        val board = _board.value
        val rack = _rack.value

        val tileInRack = rack.tiles.firstOrNull { it.id == tileId }
        val sourceMeld = board.findMeldContainingTile(tileId)
        val tile = tileInRack ?: sourceMeld?.tiles?.firstOrNull { it.id == tileId } ?: return

        if (target is DropTarget.Rack) {
            if (tileInRack != null) {
                _selectedTileIds.value = _selectedTileIds.value - tileId
                return
            }
            if (tileId !in snapshotRackIds) {
                _messages.tryEmit("Cette tuile appartient à la table, elle ne peut pas revenir sur votre chevalet.")
                return
            }
        }

        var melds: List<MeldSet> = board.melds
        var rackTiles: List<Tile> = rack.tiles

        // Reordering inside the same meld: no split, just move the tile.
        if (target is DropTarget.MeldPosition && sourceMeld != null && sourceMeld.id == target.meldId) {
            val without = sourceMeld.tiles.filterNot { it.id == tileId }
            val sourceIndex = sourceMeld.tiles.indexOfFirst { it.id == tileId }
            var insertAt = target.index
            if (insertAt > sourceIndex) insertAt -= 1
            insertAt = insertAt.coerceIn(0, without.size)
            val reordered = without.toMutableList().also { it.add(insertAt, tile) }
            melds = melds.map { if (it.id == sourceMeld.id) it.copy(tiles = reordered) else it }
            commitWorkingState(board.copy(melds = melds), rack, tileId)
            return
        }

        // 1. Remove from source
        if (tileInRack != null) {
            rackTiles = rackTiles.filterNot { it.id == tileId }
        } else if (sourceMeld != null) {
            melds = removeTileSplitting(melds, sourceMeld, tileId)
        }

        // 2. Add to target
        when (target) {
            DropTarget.Rack -> rackTiles = rackTiles + tile
            DropTarget.NewMeld -> melds = melds + MeldSet(tiles = listOf(tile))
            is DropTarget.MeldPosition -> {
                val idx = melds.indexOfFirst { it.id == target.meldId }
                melds = if (idx < 0) {
                    melds + MeldSet(tiles = listOf(tile))
                } else {
                    val m = melds[idx]
                    val duplicateIndex = duplicateIndexInRun(m, tile)
                    if (duplicateIndex >= 0) {
                        // Same colour and number already in this run (e.g. a 6 dropped on 4-5-6-7-8):
                        // split into 4-5-6 and 6-7-8 instead of building an impossible run.
                        _messages.tryEmit("Série scindée en deux combinaisons.")
                        val left = m.tiles.subList(0, duplicateIndex + 1).toList()
                        val right = listOf(tile) + m.tiles.subList(duplicateIndex + 1, m.tiles.size)
                        melds.toMutableList().also {
                            it[idx] = m.copy(tiles = left)
                            it.add(idx + 1, MeldSet(tiles = right, gridRow = m.gridRow, gridCol = m.gridCol))
                        }
                    } else {
                        val insertAt = snapInsertIndex(m, tile) ?: target.index.coerceIn(0, m.tiles.size)
                        melds.toMutableList().also { it[idx] = m.withTileInserted(insertAt, tile) }
                    }
                }
            }
        }

        commitWorkingState(board.copy(melds = melds), rack.copy(tiles = rackTiles), tileId)
    }

    /**
     * If [meld] is a run without jokers and [tile] has the run's colour, returns the index that keeps
     * the numbers in order wherever the tile was dropped (mistakes made impossible). Null: use the drop spot.
     */
    private fun snapInsertIndex(meld: MeldSet, tile: Tile): Int? {
        if (tile.isJoker || meld.tiles.any { it.isJoker }) return null
        val numbers = meld.tiles.map { it.number }
        if (meld.tiles.map { it.color }.distinct().size != 1 || numbers.distinct().size < 2) return null
        if (meld.tiles.first().color != tile.color) return null
        val sorted = numbers.zipWithNext().all { (x, y) -> x < y }
        if (!sorted) return null
        return numbers.count { it < tile.number }
    }

    /**
     * If [meld] is a run (one colour, at least two different numbers) that already holds a tile
     * with the same colour and number as [tile], returns that tile's index, otherwise -1.
     */
    private fun duplicateIndexInRun(meld: MeldSet, tile: Tile): Int {
        if (tile.isJoker) return -1
        val numbered = meld.tiles.filterNot { it.isJoker }
        if (numbered.map { it.color }.distinct().size != 1) return -1
        if (numbered.map { it.number }.distinct().size < 2) return -1
        return meld.tiles.indexOfFirst { !it.isJoker && it.color == tile.color && it.number == tile.number }
    }

    /** Lets the other players watch the table being rearranged, move by move. */
    private fun broadcastWorkingBoard() {
        val melds = _board.value.melds
        scope.launch { repository.sendSetModified(melds) }
    }

    private fun commitWorkingState(board: BoardState, rack: PlayerRack, movedTileId: String) {
        history.addLast(_board.value to _rack.value)
        if (history.size > 60) history.removeFirst()
        _canUndo.value = true
        _board.value = board.copy(melds = board.melds.filter { it.isNotEmpty })
        broadcastWorkingBoard()
        _rack.value = rack
        _selectedTileIds.value = _selectedTileIds.value - movedTileId
    }

    /**
     * Removes [tileId] from [meld]. If the tile sits in the middle, the meld is split in two:
     * the left part keeps the original id, the right part gets a new one.
     */
    private fun removeTileSplitting(melds: List<MeldSet>, meld: MeldSet, tileId: String): List<MeldSet> {
        val i = meld.tiles.indexOfFirst { it.id == tileId }
        if (i < 0) return melds
        val left = meld.tiles.subList(0, i).toList()
        val right = meld.tiles.subList(i + 1, meld.tiles.size).toList()

        // A group (same number, different colours): the remaining tiles simply stay together.
        // Only a run is cut in two when a tile is taken from its middle.
        val numbered = meld.tiles.filterNot { it.isJoker }
        val isGroup = numbered.size >= 2 && numbered.map { it.number }.distinct().size == 1
        if (isGroup) {
            val rest = left + right
            return melds.mapNotNull { m ->
                when {
                    m.id != meld.id -> m
                    rest.isEmpty() -> null
                    else -> m.copy(tiles = rest)
                }
            }
        }

        val result = ArrayList<MeldSet>(melds.size + 1)
        for (m in melds) {
            if (m.id != meld.id) {
                result += m
                continue
            }
            when {
                left.isEmpty() && right.isEmpty() -> Unit
                left.isEmpty() -> result += m.copy(tiles = right)
                right.isEmpty() -> result += m.copy(tiles = left)
                else -> {
                    result += m.copy(tiles = left)
                    result += MeldSet(tiles = right, gridRow = m.gridRow, gridCol = m.gridCol)
                }
            }
        }
        return result
    }

    // ==========================================
    // RACK SORTING (local only, never part of the turn)
    // ==========================================

    fun sortByRun() {
        _rack.value = _rack.value.sortedByRun()
        snapshotRack = snapshotRack.sortedByRun()
        _rackEpoch.value += 1
    }

    fun sortByGroup() {
        _rack.value = _rack.value.sortedByGroup()
        snapshotRack = snapshotRack.sortedByGroup()
        _rackEpoch.value += 1
    }

    // ==========================================
    // TURN ACTIONS
    // ==========================================

    /** "Annuler Coup": restore the start-of-turn snapshot. */
    fun resetTurn() {
        clearHistory()
        _board.value = snapshotBoard
        broadcastWorkingBoard()
        _rack.value = snapshotRack
        _selectedTileIds.value = emptyList()
    }

    /** "Piocher": give up on this turn's moves and draw a tile. */
    fun drawTile() {
        resetTurn()
        scope.launch { repository.drawTile() }
    }

    /**
     * "Valider Tour": checks the board locally first so that an obvious mistake never costs
     * a penalty, then sends the proposed board to the authoritative server.
     * Returns true if the turn was sent.
     */
    fun commitTurn(): Boolean {
        val error = validateLocally()
        if (error != null) {
            _messages.tryEmit(error)
            return false
        }
        val board = _board.value
        val rack = _rack.value
        scope.launch {
            repository.commitTurn(
                proposedMelds = board.melds.filter { it.isNotEmpty },
                remainingRackTileIds = rack.tiles.map { it.id }
            )
        }
        return true
    }

    private fun validateLocally(): String? {
        val board = _board.value
        val rack = _rack.value
        val config = repository.gameConfig.value

        val placedIds = snapshotRackIds - rack.tiles.map { it.id }.toSet()
        if (placedIds.isEmpty()) {
            return "Déposez au moins une tuile sur la table, ou piochez pour passer votre tour."
        }

        val validation = BoardValidator.validateBoard(board, config.jokerPointStrategy)
        if (!validation.isValid) {
            val count = validation.invalidMelds.size
            return if (count == 1) "Une combinaison est invalide (bordure rouge). Corrigez-la ou annulez le coup."
            else "$count combinaisons sont invalides (bordures rouges). Corrigez-les ou annulez le coup."
        }

        if (!snapshotRack.hasMadeInitialMeld && config.initialMeldThreshold > 0) {
            // First meld: existing melds must be untouched and new melds must come only from the rack.
            val original = snapshotBoard.melds.associateBy { it.id }
            for ((id, meld) in original) {
                val now = board.findMeld(id)
                if (now == null || now.tiles.map { it.id } != meld.tiles.map { it.id }) {
                    return "Première pose : vous ne pouvez pas toucher aux combinaisons déjà sur la table."
                }
            }
            val mine = snapshotRackIds
            val newMelds = board.melds.filter { it.id !in original }
            if (newMelds.any { m -> m.tiles.any { it.id !in mine } }) {
                return "Première pose : vos nouvelles combinaisons doivent venir uniquement de votre chevalet."
            }
            val points = newMelds.sumOf { BoardValidator.validateMeld(it, config.jokerPointStrategy).points }
            if (points < config.initialMeldThreshold) {
                return "Première pose : $points pts sur ${config.initialMeldThreshold} requis."
            }
        }
        return null
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private fun computeSummary(board: BoardState, rack: PlayerRack): TurnSummary {
        val rackIds = rack.tiles.map { it.id }.toSet()
        val placed = snapshotRackIds.count { it !in rackIds }
        val config = repository.gameConfig.value
        val results = board.melds.map { BoardValidator.validateMeld(it, config.jokerPointStrategy) }
        val invalid = results.count { !it.isValid }
        val originalIds = snapshotBoard.melds.map { it.id }.toSet()
        val mine = snapshotRackIds
        val newPoints = board.melds
            .filter { it.id !in originalIds && it.tiles.all { t -> t.id in mine } }
            .sumOf { BoardValidator.validateMeld(it, config.jokerPointStrategy).points }
        val dirty = placed > 0 || board.melds.map { it.id to it.tiles.map { t -> t.id } } !=
            snapshotBoard.melds.map { it.id to it.tiles.map { t -> t.id } }
        return TurnSummary(
            tilesPlaced = placed,
            newMeldPoints = newPoints,
            invalidMeldCount = invalid,
            isDirty = dirty
        )
    }

    /**
     * Keeps the user's current rack order for tiles that are still there,
     * and appends tiles that are new (drawn, penalty, or returned by a rollback).
     */
    private fun mergeKeepingOrder(current: List<Tile>, server: List<Tile>): List<Tile> {
        val serverById = server.associateBy { it.id }
        val kept = current.mapNotNull { serverById[it.id] }
        val keptIds = kept.map { it.id }.toSet()
        val added = server.filter { it.id !in keptIds }
        return kept + added
    }
}
