package com.rummikub.core.engine

import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.Tile
import com.rummikub.core.model.TileColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveAssistantTest {

    private var n = 0
    private fun t(color: TileColor, number: Int) = Tile.number(color, number, id = "x${n++}_${color.name}_$number")
    private fun joker() = Tile.joker(id = "J${n++}")

    @Test
    fun `propose une suite et un groupe pour la premiere pose`() {
        val rack = listOf(
            t(TileColor.BLACK, 10), t(TileColor.BLACK, 11), t(TileColor.BLACK, 12),
            t(TileColor.BLUE, 5), t(TileColor.RED, 5), t(TileColor.YELLOW, 5), t(TileColor.BLUE, 1),
        )
        val s = MoveAssistant.suggest(emptyList(), rack, false, 30)!!
        assertEquals(48, s.points)
        assertEquals(1, s.rack.size)
        assertTrue(s.melds.all { BoardValidator.validateMeld(it).isValid })
    }

    @Test
    fun `premiere pose impossible sous le seuil`() {
        val rack = listOf(t(TileColor.BLACK, 2), t(TileColor.BLUE, 2), t(TileColor.RED, 2), t(TileColor.YELLOW, 9))
        assertNull(MoveAssistant.suggest(emptyList(), rack, false, 30))
    }

    @Test
    fun `utilise un joker pour atteindre la pose`() {
        val rack = listOf(t(TileColor.BLACK, 12), t(TileColor.BLUE, 12), joker(), t(TileColor.YELLOW, 1))
        val s = MoveAssistant.suggest(emptyList(), rack, false, 30)!!
        assertEquals(36, s.points)
    }

    @Test
    fun `apres la pose prolonge les combinaisons de la table`() {
        val run = MeldSet(id = "run", tiles = (3..5).map { t(TileColor.BLACK, it) })
        val group = MeldSet(id = "grp", tiles = listOf(TileColor.BLUE, TileColor.RED, TileColor.YELLOW).map { t(it, 9) })
        val six = t(TileColor.BLACK, 6)
        val two = t(TileColor.BLACK, 2)
        val nine = t(TileColor.BLACK, 9)
        val thirteen = t(TileColor.RED, 13)
        val s = MoveAssistant.suggest(listOf(run, group), listOf(six, two, nine, thirteen), true, 30)!!
        assertEquals(listOf(thirteen), s.rack)
        assertEquals(setOf("run", "grp"), s.extendedMeldIds)
        assertEquals(listOf(two, run.tiles[0], run.tiles[1], run.tiles[2], six).map { it.id }, s.melds.first { it.id == "run" }.tiles.map { it.id })
    }

    @Test
    fun `rien a jouer renvoie null`() {
        val run = MeldSet(id = "run", tiles = (3..5).map { t(TileColor.BLACK, it) })
        assertNull(MoveAssistant.suggest(listOf(run), listOf(t(TileColor.RED, 11), t(TileColor.BLUE, 1)), true, 30))
    }

    @Test
    fun `gros chevalet reste valide et rapide`() {
        val rack = listOf(
            t(TileColor.BLACK, 1), t(TileColor.BLACK, 2), t(TileColor.BLACK, 3), t(TileColor.BLACK, 4), t(TileColor.BLUE, 4),
            t(TileColor.RED, 4), t(TileColor.YELLOW, 4), t(TileColor.BLUE, 7), t(TileColor.BLUE, 8), t(TileColor.BLUE, 9),
            t(TileColor.RED, 11), t(TileColor.YELLOW, 12), t(TileColor.BLACK, 13), joker(),
        )
        val start = System.currentTimeMillis()
        val s = MoveAssistant.suggest(emptyList(), rack, false, 30)!!
        assertTrue(System.currentTimeMillis() - start < 3000)
        assertTrue(s.tilesPlayed >= 10)
        assertTrue(s.melds.all { BoardValidator.validateMeld(it).isValid })
    }

    @Test
    fun `une tuile qui prolonge une serie trouve sa place sur la table`() {
        val run = MeldSet(id = "run", tiles = (3..5).map { t(TileColor.BLACK, it) })
        val place = MoveAssistant.bestPlaceOnTable(listOf(run), t(TileColor.BLACK, 6))!!
        assertEquals("run" to 3, place)
        val before = MoveAssistant.bestPlaceOnTable(listOf(run), t(TileColor.BLACK, 2))!!
        assertEquals("run" to 0, before)
        assertNull(MoveAssistant.bestPlaceOnTable(listOf(run), t(TileColor.RED, 9)))
    }

    @Test
    fun `compagnons dans le chevalet pour une serie ou un groupe`() {
        val seven = t(TileColor.BLUE, 7)
        val rack = listOf(seven, t(TileColor.BLUE, 8), t(TileColor.BLUE, 9), t(TileColor.RED, 1))
        val c = MoveAssistant.companionsFor(seven, rack)!!
        assertEquals(setOf(8, 9), c.map { it.number }.toSet())
        val five = t(TileColor.RED, 5)
        val rack2 = listOf(five, t(TileColor.BLUE, 5), t(TileColor.YELLOW, 5))
        assertEquals(2, MoveAssistant.companionsFor(five, rack2)!!.size)
        assertNull(MoveAssistant.companionsFor(five, listOf(five, t(TileColor.BLUE, 5))))
    }

    @Test
    fun `echange de joker dans une serie et dans un groupe`() {
        val j = joker()
        val run = MeldSet(tiles = listOf(t(TileColor.BLACK, 7), j, t(TileColor.BLACK, 9)))
        assertEquals(1, MoveAssistant.jokerSwapPosition(run, t(TileColor.BLACK, 8)))
        assertNull(MoveAssistant.jokerSwapPosition(run, t(TileColor.RED, 8)))
        val group = MeldSet(tiles = listOf(t(TileColor.BLACK, 7), t(TileColor.BLUE, 7), j))
        assertEquals(2, MoveAssistant.jokerSwapPosition(group, t(TileColor.RED, 7)))
        assertNull(MoveAssistant.jokerSwapPosition(group, t(TileColor.BLACK, 7)))
    }
}
