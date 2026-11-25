package com.rummikub.core.engine.layout

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Disposition pure (sans Compose) du plateau et du chevalet : testable sur la JVM.
 * Toutes les valeurs sont en pixels. Les tuiles sont identifiées par leur id.
 */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    fun contains(px: Float, py: Float) = px in x..right && py in y..bottom
    fun inflate(dx: Float, dy: Float) = Box(x - dx, y - dy, w + 2 * dx, h + 2 * dy)
    fun offset(dx: Float, dy: Float) = Box(x + dx, y + dy, w, h)
}

/** Rapport hauteur / largeur d'une tuile. */
const val TILE_ASPECT = 1.36f

/** Où le joueur dépose la tuile tenue. `meld` est l'index de la combinaison dans la liste affichée. */
sealed interface LayoutTarget {
    data class Rack(val index: Int) : LayoutTarget
    data class Meld(val index: Int, val at: Int) : LayoutTarget
    data object NewMeld : LayoutTarget
    data object None : LayoutTarget
}

// ===================================================================== PLATEAU

data class MeldFrame(
    val index: Int,
    /** Cadre complet : bandeau d'état et tuiles. */
    val box: Box,
    val tiles: List<Pair<String, Box>>,
    /** Bandeau au-dessus des tuiles (pastille de points). */
    val header: Box,
)

data class TableLayout(
    val tileW: Float,
    val tileH: Float,
    val melds: List<MeldFrame>,
    /** Emplacements fantômes : trou d'insertion dans une combinaison, ou nouvelle combinaison. */
    val ghosts: List<Box>,
    val contentHeight: Float,
    val needsScroll: Boolean,
    val width: Float,
    val meldGap: Float,
    val rowGap: Float,
) {
    fun tileBox(id: String): Box? = melds.firstNotNullOfOrNull { m -> m.tiles.firstOrNull { it.first == id }?.second }
}

/** Aperçu de dépôt : ouvre `count` trous dans une combinaison, ou prépare une nouvelle combinaison. */
data class TablePreview(val meld: Int?, val at: Int, val count: Int = 1)

object TableLayouter {
    private const val TILE_GAP = 0.05f
    private const val MELD_GAP = 0.55f
    private const val HEADER = 0.34f
    private const val ROW_GAP = 0.22f
    private const val PAD = 0.4f

    /**
     * Plus grande largeur de tuile qui fait tenir toutes les combinaisons dans la zone.
     * Calculée sans aperçu pour rester stable pendant un glisser.
     */
    fun fitTileWidth(melds: List<List<String>>, width: Float, height: Float, minTile: Float, maxTile: Float): Float {
        val widest = max(melds.maxOfOrNull { it.size } ?: 0, 6)
        val byWidth = (width / (1 + TILE_GAP) / (widest + 0.9f)).coerceAtLeast(minTile)
        var tw = min(maxTile, byWidth)
        while (tw > minTile) {
            if (layout(melds, width, height, tw).contentHeight <= height) return tw
            tw -= 1f
        }
        return minTile
    }

    fun layout(
        melds: List<List<String>>,
        width: Float,
        height: Float,
        tileW: Float,
        preview: TablePreview? = null,
    ): TableLayout {
        val th = tileW * TILE_ASPECT
        val gap = tileW * TILE_GAP
        val meldGap = tileW * MELD_GAP
        val header = th * HEADER
        val rowGap = th * ROW_GAP
        val pad = tileW * PAD

        val slots: MutableList<MutableList<String?>> = melds.map { m -> m.map<String, String?> { it }.toMutableList() }.toMutableList()
        var ghostMeld = -1
        if (preview != null) {
            val n = preview.count.coerceAtLeast(1)
            if (preview.meld == null) {
                slots.add(MutableList(n) { null })
                ghostMeld = slots.lastIndex
            } else if (preview.meld in slots.indices) {
                ghostMeld = preview.meld
                val at = preview.at.coerceIn(0, slots[ghostMeld].size)
                repeat(n) { slots[ghostMeld].add(at, null) }
            }
        }

        val frames = ArrayList<MeldFrame>()
        val ghosts = ArrayList<Box>()
        var x = pad
        var rowTop = pad
        val rowH = header + th
        for ((i, meld) in slots.withIndex()) {
            if (meld.isEmpty()) continue
            val n = meld.size
            val mw = n * tileW + (n - 1) * gap
            if (x > pad && x + mw > width - pad) {
                x = pad
                rowTop += rowH + rowGap
            }
            val boxes = ArrayList<Pair<String, Box>>()
            for ((k, t) in meld.withIndex()) {
                val b = Box(x + k * (tileW + gap), rowTop + header, tileW, th)
                if (t == null) ghosts.add(b) else boxes.add(t to b)
            }
            val newMeldGhost = i == ghostMeld && preview?.meld == null
            if (!newMeldGhost) {
                frames.add(MeldFrame(i, Box(x, rowTop, mw, rowH), boxes, Box(x, rowTop, mw, header)))
            }
            x += mw + meldGap
        }
        val contentHeight = rowTop + rowH + pad
        return TableLayout(
            tileW = tileW,
            tileH = th,
            melds = frames,
            ghosts = ghosts,
            contentHeight = contentHeight,
            needsScroll = contentHeight > height + 0.5f,
            width = width,
            meldGap = meldGap,
            rowGap = rowGap,
        )
    }

    /** Où tombe la tuile tenue en (px, py), en coordonnées du contenu. `base` = disposition sans aperçu. */
    fun hitTest(base: TableLayout, px: Float, py: Float, areaHeight: Float): LayoutTarget {
        if (px < 0 || px > base.width) return LayoutTarget.None
        var best: Pair<Int, Float>? = null
        for (m in base.melds) {
            val zone = m.box.inflate(base.meldGap * 0.5f, base.rowGap * 0.5f)
            if (zone.contains(px, py)) {
                val d = (px - m.box.cx) * (px - m.box.cx) + (py - m.box.cy) * (py - m.box.cy)
                if (best == null || d < best.second) best = m.index to d
            }
        }
        if (best != null) {
            val m = base.melds.first { it.index == best.first }
            val at = m.tiles.count { (_, b) -> b.cx < px }
            return LayoutTarget.Meld(m.index, at)
        }
        return if (py >= 0 && py <= max(areaHeight, base.contentHeight)) LayoutTarget.NewMeld else LayoutTarget.None
    }
}

// ===================================================================== CHEVALET

data class RackLayout(
    val tileW: Float,
    val tileH: Float,
    val slots: List<Pair<String, Box>>,
    val ghosts: List<Box>,
    val contentHeight: Float,
    val needsScroll: Boolean,
    /** Index (dans la liste de tuiles) de la première tuile de chaque ligne. */
    val rowStarts: List<Int>,
    val rowTops: List<Float>,
    val width: Float,
) {
    fun tileBox(id: String): Box? = slots.firstOrNull { it.first == id }?.second
}

object RackLayouter {
    private const val GAP = 0.07f
    private const val ROW_GAP = 0.10f
    private const val PAD = 0.30f

    fun fitTileWidth(count: Int, width: Float, height: Float, minTile: Float, maxTile: Float): Float {
        var tw = maxTile
        while (tw > minTile) {
            if (layout(List(count) { "$it" }, width, height, tw).contentHeight <= height) return tw
            tw -= 1f
        }
        return minTile
    }

    fun layout(tiles: List<String>, width: Float, height: Float, tileW: Float, ghostAt: Int? = null, ghostCount: Int = 1): RackLayout {
        val th = tileW * TILE_ASPECT
        val gap = tileW * GAP
        val rowGap = th * ROW_GAP
        val pad = tileW * PAD
        val ghosts = if (ghostAt != null) ghostCount.coerceAtLeast(1) else 0
        val total = tiles.size + ghosts
        val perRow = max(1, floor((width - 2 * pad + gap) / (tileW + gap)).toInt())
        val rows = max(1, ceil(total / perRow.toFloat()).toInt())
        val slots = ArrayList<Pair<String, Box>>()
        val ghostBoxes = ArrayList<Box>()
        val rowStarts = ArrayList<Int>()
        val rowTops = ArrayList<Float>()
        var seq = 0
        var tileIndex = 0
        for (r in 0 until rows) {
            val inRow = min(perRow, total - r * perRow)
            val rowWidth = inRow * tileW + (inRow - 1) * gap
            val x0 = max(pad, (width - rowWidth) / 2)
            val y = pad + r * (th + rowGap)
            rowStarts.add(tileIndex)
            rowTops.add(y)
            for (k in 0 until inRow) {
                val b = Box(x0 + k * (tileW + gap), y, tileW, th)
                if (ghostAt != null && seq >= ghostAt && seq < ghostAt + ghosts) {
                    ghostBoxes.add(b)
                } else {
                    slots.add(tiles[tileIndex] to b)
                    tileIndex++
                }
                seq++
            }
        }
        val contentHeight = pad * 2 + rows * th + (rows - 1) * rowGap
        return RackLayout(tileW, th, slots, ghostBoxes, contentHeight, contentHeight > height + 0.5f, rowStarts, rowTops, width)
    }

    /** Position d'insertion (0..n) pour une tuile tenue en (px, py) ; `base` = disposition sans trou. */
    fun hitIndex(base: RackLayout, px: Float, py: Float): Int {
        if (base.slots.isEmpty()) return 0
        var row = base.rowTops.indexOfLast { py >= it - base.tileH * 0.15f }
        if (row < 0) row = 0
        val start = base.rowStarts[row]
        val end = if (row + 1 < base.rowStarts.size) base.rowStarts[row + 1] else base.slots.size
        var idx = start
        for (i in start until end) {
            if (base.slots[i].second.cx < px) idx = i + 1
        }
        return idx.coerceIn(0, base.slots.size)
    }
}
