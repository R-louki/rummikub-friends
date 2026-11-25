package com.rummikub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.rummikub.app.game.DropTarget
import com.rummikub.app.ui.table.RackToolbar
import com.rummikub.app.ui.table.TableSurface
import com.rummikub.app.ui.theme.FeltDarkGreen
import com.rummikub.app.ui.theme.FeltLight
import com.rummikub.app.ui.theme.FeltMediumGreen
import com.rummikub.core.engine.BoardValidator
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor

/**
 * DEBUG BUILDS ONLY. The table on its own, with a few moves implemented locally, to check the look and
 * the gestures on an emulator without a server:
 *
 *   adb shell am start -n com.rummikub.app/.PreviewActivity --es screen table
 */
@Composable
fun TableDemo(full: Boolean) {
    fun t(c: TileColor, n: Int, set: Int = 1) = Tile.number(c, n, set)
    fun r(c: TileColor, n: Int) = Tile.number(c, n, 3)
    val initialMelds = if (full) {
        listOf(
            MeldSet("m1", (4..7).map { t(TileColor.BLUE, it) }),
            MeldSet("m2", listOf(TileColor.BLACK, TileColor.RED, TileColor.YELLOW).map { t(it, 9) }),
            MeldSet("m3", (10..13).map { t(TileColor.RED, it) }),
            MeldSet("m4", listOf(t(TileColor.BLACK, 3), Tile.joker(), t(TileColor.BLACK, 5), t(TileColor.BLACK, 6))),
            MeldSet("m5", listOf(TileColor.BLUE, TileColor.RED, TileColor.YELLOW).map { t(it, 1) }),
            MeldSet("m6", (1..3).map { t(TileColor.YELLOW, it) }),
            MeldSet("m7", listOf(t(TileColor.BLACK, 12), t(TileColor.BLUE, 12), t(TileColor.YELLOW, 12))),
            MeldSet("m8", (7..9).map { t(TileColor.RED, it, 2) }),
            MeldSet("m9", listOf(t(TileColor.BLACK, 2), t(TileColor.BLACK, 3, 2))),
        )
    } else {
        listOf(
            MeldSet("m1", (4..7).map { t(TileColor.BLUE, it) }),
            MeldSet("m2", listOf(TileColor.BLACK, TileColor.RED, TileColor.YELLOW).map { t(it, 9) }),
            MeldSet("m3", (10..12).map { t(TileColor.RED, it) }),
        )
    }
    val initialRack = listOf(
        r(TileColor.BLUE, 9), r(TileColor.RED, 9), r(TileColor.BLUE, 7), r(TileColor.YELLOW, 2), r(TileColor.BLACK, 9),
        r(TileColor.BLUE, 8), r(TileColor.RED, 4), r(TileColor.BLACK, 12), r(TileColor.YELLOW, 9), r(TileColor.RED, 5),
        r(TileColor.BLUE, 13), r(TileColor.RED, 13), r(TileColor.YELLOW, 6), r(TileColor.BLACK, 11),
    ) + if (full) listOf(Tile.joker(3), r(TileColor.BLACK, 1), r(TileColor.YELLOW, 11), r(TileColor.RED, 2), r(TileColor.BLUE, 3), r(TileColor.BLACK, 7)) else emptyList()

    var melds by remember { mutableStateOf(initialMelds) }
    var rack by remember { mutableStateOf(initialRack) }
    var selected by remember { mutableStateOf(emptyList<String>()) }
    val rackIds = initialRack.map { it.id }.toSet()
    val onBoard = melds.flatMap { it.tiles }.map { it.id }.toSet()
    val points = melds.filter { m -> m.tiles.all { it.id in rackIds } }.sumOf { BoardValidator.validateMeld(it).points }

    fun move(ids: List<String>, target: DropTarget, rackIndex: Int) {
        val all = (melds.flatMap { it.tiles } + rack).associateBy { it.id }
        val moving = ids.mapNotNull { all[it] }
        val movingIds = ids.toSet()
        var m = melds.map { it.copy(tiles = it.tiles.filter { x -> x.id !in movingIds }) }
        var r = rack.filter { it.id !in movingIds }
        when (target) {
            DropTarget.NewMeld -> m = m + MeldSet(tiles = moving)
            DropTarget.Rack -> r = r.take(rackIndex) + moving + r.drop(rackIndex)
            is DropTarget.MeldPosition -> {
                val sourceMeld = melds.firstOrNull { it.id == target.meldId }
                val firstSource = sourceMeld?.tiles?.indexOfFirst { it.id == ids.first() } ?: -1
                var at = target.index
                if (firstSource in 0 until at && at != Int.MAX_VALUE) at -= 1
                m = m.map { if (it.id == target.meldId) it.copy(tiles = it.tiles.take(at.coerceIn(0, it.tiles.size)) + moving + it.tiles.drop(at.coerceIn(0, it.tiles.size))) else it }
            }
        }
        melds = m.filter { it.tiles.isNotEmpty() }
        rack = r
        selected = emptyList()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(FeltLight, FeltMediumGreen, FeltDarkGreen)))
            .padding(start = 66.dp, end = 58.dp),
    ) {
        TableSurface(
            melds = melds,
            rackTiles = rack,
            isMyTurn = true,
            hasMadeInitialMeld = false,
            initialThreshold = 30,
            newMeldPoints = points,
            newTileIds = onBoard.filter { it in rackIds }.toSet(),
            selectedIds = selected,
            placedByOthers = emptySet(),
            movedByOthers = emptySet(),
            drawnTileId = null,
            emptyText = "Glissez vos tuiles ici",
            onToggleSelect = { tile -> selected = if (tile.id in selected) selected - tile.id else selected + tile.id },
            onTapTarget = { target -> if (selected.isNotEmpty()) move(selected, target, rack.size) },
            onDoubleTap = { },
            onSelectChain = { ids -> selected = ids },
            onDrop = { ids, _, target, rackIndex -> move(ids, target, rackIndex) },
            rackTools = { RackToolbar(true, false, {}, {}, {}, {}) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
