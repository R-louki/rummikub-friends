package com.rummikub.core.engine.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableLayoutTest {

    private var seq = 0
    private fun meld(n: Int): List<String> = List(n) { "t${seq++}" }

    @Test
    fun `huit combinaisons tiennent sans defilement avec la plus grande tuile possible`() {
        val melds = listOf(4, 5, 3, 4, 3, 4, 3, 5).map { meld(it) }
        val tw = TableLayouter.fitTileWidth(melds, 900f, 420f, 28f, 90f)
        val layout = TableLayouter.layout(melds, 900f, 420f, tw)
        assertTrue("doit tenir : ${layout.contentHeight}", layout.contentHeight <= 420f)
        assertFalse(layout.needsScroll)
        assertTrue(TableLayouter.layout(melds, 900f, 420f, tw + 2).contentHeight > 420f || tw >= 89f)
    }

    @Test
    fun `plateau vide utilise la tuile maximale`() {
        assertEquals(90f, TableLayouter.fitTileWidth(emptyList(), 900f, 420f, 28f, 90f), 0.01f)
    }

    @Test
    fun `defilement quand meme la tuile minimale ne suffit pas`() {
        val melds = List(30) { meld(3) }
        val tw = TableLayouter.fitTileWidth(melds, 400f, 200f, 28f, 90f)
        assertEquals(28f, tw, 0.01f)
        assertTrue(TableLayouter.layout(melds, 400f, 200f, tw).needsScroll)
    }

    @Test
    fun `une combinaison de 13 tuiles tient sur une ligne`() {
        val melds = listOf(meld(13))
        val tw = TableLayouter.fitTileWidth(melds, 700f, 400f, 20f, 90f)
        val l = TableLayouter.layout(melds, 700f, 400f, tw)
        assertTrue(l.melds[0].box.right <= 700f)
    }

    @Test
    fun `aucune tuile ne chevauche une autre`() {
        val melds = listOf(meld(5), meld(5), meld(3))
        val l = TableLayouter.layout(melds, 800f, 400f, 50f)
        val boxes = l.melds.flatMap { it.tiles }.map { it.second }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]
            val b = boxes[j]
            val overlap = a.x < b.right - 0.1f && b.x < a.right - 0.1f && a.y < b.bottom - 0.1f && b.y < a.bottom - 0.1f
            assertFalse("tuiles $i et $j se chevauchent", overlap)
        }
    }

    @Test
    fun `l'apercu ouvre un trou dans la combinaison visee`() {
        val melds = listOf(meld(4), meld(5))
        val base = TableLayouter.layout(melds, 800f, 400f, 50f)
        val prev = TableLayouter.layout(melds, 800f, 400f, 50f, TablePreview(meld = 0, at = 2))
        assertEquals(1, prev.ghosts.size)
        val third = melds[0][2]
        assertTrue(prev.tileBox(third)!!.x > base.tileBox(third)!!.x)
        assertEquals(base.tileBox(melds[0][0]), prev.tileBox(melds[0][0]))
    }

    @Test
    fun `un glisser de plusieurs tuiles ouvre plusieurs trous`() {
        val melds = listOf(meld(4))
        val prev = TableLayouter.layout(melds, 800f, 400f, 50f, TablePreview(meld = 0, at = 1, count = 3))
        assertEquals(3, prev.ghosts.size)
    }

    @Test
    fun `l'apercu nouvelle combinaison ajoute un emplacement fantome`() {
        val melds = listOf(meld(4))
        val prev = TableLayouter.layout(melds, 800f, 400f, 50f, TablePreview(meld = null, at = 0))
        assertEquals(1, prev.ghosts.size)
        assertEquals(1, prev.melds.size)
    }

    @Test
    fun `depot sur une combinaison renvoie son index et la position`() {
        val melds = listOf(meld(4), meld(5))
        val base = TableLayouter.layout(melds, 800f, 400f, 50f)
        val second = base.melds[1]
        val target = TableLayouter.hitTest(base, second.tiles[2].second.cx + 1f, second.tiles[2].second.cy, 400f)
        assertEquals(LayoutTarget.Meld(1, 3), target)
        val atStart = TableLayouter.hitTest(base, second.box.x + 1f, second.tiles[0].second.cy, 400f)
        assertEquals(LayoutTarget.Meld(1, 0), atStart)
    }

    @Test
    fun `depot dans le vide cree une nouvelle combinaison`() {
        val base = TableLayouter.layout(listOf(meld(4)), 800f, 400f, 50f)
        assertEquals(LayoutTarget.NewMeld, TableLayouter.hitTest(base, 700f, 300f, 400f))
        assertEquals(LayoutTarget.None, TableLayouter.hitTest(base, -20f, 100f, 400f))
    }

    @Test
    fun `les combinaisons vides gardent l'index des autres`() {
        val l = TableLayouter.layout(listOf(emptyList(), meld(5)), 800f, 400f, 50f)
        assertEquals(listOf(1), l.melds.map { it.index })
    }

    @Test
    fun `le chevalet s'ajuste pour tenir sur la zone`() {
        val tw = RackLayouter.fitTileWidth(21, 700f, 180f, 24f, 80f)
        val l = RackLayouter.layout(List(21) { "r$it" }, 700f, 180f, tw)
        assertTrue(l.contentHeight <= 180f)
        assertEquals(21, l.slots.size)
    }

    @Test
    fun `index d'insertion dans le chevalet`() {
        val tiles = List(10) { "r$it" }
        val l = RackLayouter.layout(tiles, 600f, 200f, 50f)
        val third = l.slots[2].second
        assertEquals(3, RackLayouter.hitIndex(l, third.cx + 1f, third.cy))
        assertEquals(0, RackLayouter.hitIndex(l, 0f, third.cy))
        if (l.rowStarts.size > 1) assertEquals(l.rowStarts[1], RackLayouter.hitIndex(l, 0f, l.rowTops[1] + 5f))
    }

    @Test
    fun `trou du chevalet decale les tuiles suivantes`() {
        val tiles = List(5) { "r$it" }
        val base = RackLayouter.layout(tiles, 600f, 200f, 50f)
        val gap = RackLayouter.layout(tiles, 600f, 200f, 50f, ghostAt = 2, ghostCount = 2)
        assertEquals(2, gap.ghosts.size)
        assertNotNull(gap.tileBox("r3"))
        assertTrue(base.tileBox("r3") != gap.tileBox("r3"))
        assertEquals(5, gap.slots.size)
    }
}
