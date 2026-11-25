package com.rummikub.core.engine

import com.rummikub.core.model.JokerPointStrategy
import com.rummikub.core.model.MeldSet
import com.rummikub.core.model.MeldType
import com.rummikub.core.model.Tile

/**
 * Aides au joueur : suggestion de coup, placement automatique d'une tuile, échange de joker.
 * Pur et sans Android : testable sur la JVM.
 */
object MoveAssistant {

    /** Un coup proposé : plateau et chevalet complets, prêts à être appliqués. */
    data class Suggestion(
        val melds: List<MeldSet>,
        val rack: List<Tile>,
        val points: Int,
        val tilesPlayed: Int,
        /** Combinaisons nouvelles (ids) et combinaisons de la table prolongées (ids). */
        val newMeldIds: Set<String>,
        val extendedMeldIds: Set<String>,
    )

    private class Slot(val color: Int, val number: Int, val joker: Boolean = false)

    private class Best(val score: Long, val melds: List<List<Slot>>)

    private const val NODE_LIMIT = 300_000

    // ------------------------------------------------------------------ suggestion

    /**
     * Cherche la meilleure pose avec le chevalet.
     * Avant la première pose : maximise les points (il faut atteindre [threshold]) et ne touche pas à la table.
     * Ensuite : maximise le nombre de tuiles posées puis prolonge les combinaisons de la table.
     */
    fun suggest(
        board: List<MeldSet>,
        rack: List<Tile>,
        hasMadeInitialMeld: Boolean,
        threshold: Int,
        strategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE,
    ): Suggestion? {
        val counts = Array(4) { IntArray(15) }
        val byKey = HashMap<Int, ArrayDeque<Tile>>()
        val jokers = ArrayDeque<Tile>()
        for (t in rack) {
            if (t.isJoker) {
                jokers.addLast(t)
            } else {
                val c = t.color!!.ordinal
                counts[c][t.number]++
                byKey.getOrPut(c * 20 + t.number) { ArrayDeque() }.addLast(t)
            }
        }
        val needPoints = !hasMadeInitialMeld
        val best = Solver(counts, jokers.size, maximizePoints = needPoints).solve(0)

        val newMelds = best.melds.map { slots ->
            MeldSet(tiles = slots.map { s -> if (s.joker) jokers.removeFirst() else byKey.getValue(s.color * 20 + s.number).removeFirst() })
        }.filter { BoardValidator.validateMeld(it, strategy).isValid }

        var points = newMelds.sumOf { BoardValidator.validateMeld(it, strategy).points }
        if (needPoints && threshold > 0 && points < threshold) return null

        // les tuiles des combinaisons rejetées par la validation retournent au chevalet
        val used = newMelds.flatMap { it.tiles }.map { it.id }.toSet()
        val leftover = ArrayList(rack.filter { it.id !in used })

        var table = board
        val extended = LinkedHashSet<String>()
        if (hasMadeInitialMeld) {
            var changed = true
            while (changed && leftover.isNotEmpty()) {
                changed = false
                val it = leftover.iterator()
                while (it.hasNext()) {
                    val tile = it.next()
                    val idx = table.indexOfFirst { m -> extend(m, tile, strategy) != null }
                    if (idx >= 0) {
                        val bigger = extend(table[idx], tile, strategy)!!
                        points += BoardValidator.validateMeld(bigger, strategy).points -
                            BoardValidator.validateMeld(table[idx], strategy).points
                        table = table.toMutableList().also { l -> l[idx] = bigger }
                        extended += bigger.id
                        it.remove()
                        changed = true
                    }
                }
            }
        }
        val played = rack.size - leftover.size
        if (played == 0) return null
        return Suggestion(
            melds = table + newMelds,
            rack = leftover,
            points = points,
            tilesPlayed = played,
            newMeldIds = newMelds.map { it.id }.toSet(),
            extendedMeldIds = extended,
        )
    }

    /** Ajoute une tuile au début ou à la fin d'une combinaison si le résultat reste valide et du même genre. */
    private fun extend(meld: MeldSet, tile: Tile, strategy: JokerPointStrategy): MeldSet? {
        val before = BoardValidator.validateMeld(meld, strategy)
        if (!before.isValid) return null
        val candidates = if (before.meldType == MeldType.RUN) {
            listOf(meld.copy(tiles = meld.tiles + tile), meld.copy(tiles = listOf(tile) + meld.tiles))
        } else {
            listOf(meld.copy(tiles = meld.tiles + tile))
        }
        return candidates.firstOrNull {
            val r = BoardValidator.validateMeld(it, strategy)
            r.isValid && r.meldType == before.meldType
        }
    }

    // ------------------------------------------------------------------ placement automatique

    /** Où une tuile prolonge naturellement une combinaison de la table : (id de la combinaison, index d'insertion). */
    fun bestPlaceOnTable(
        board: List<MeldSet>,
        tile: Tile,
        strategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE,
    ): Pair<String, Int>? {
        if (tile.isJoker) return null
        for (m in board) {
            val before = BoardValidator.validateMeld(m, strategy)
            if (!before.isValid) continue
            val ends = if (before.meldType == MeldType.RUN) listOf(m.tiles.size, 0) else listOf(m.tiles.size)
            for (at in ends) {
                val candidate = m.withTileInserted(at, tile)
                val r = BoardValidator.validateMeld(candidate, strategy)
                if (r.isValid && r.meldType == before.meldType) return m.id to at
            }
        }
        return null
    }

    /**
     * Deux autres tuiles du chevalet qui forment avec [tile] une combinaison valide, ou null.
     * Série : trois numéros consécutifs de même couleur. Groupe : même numéro, couleurs différentes.
     */
    fun companionsFor(tile: Tile, rack: List<Tile>): List<Tile>? {
        if (tile.isJoker) return null
        val others = rack.filter { it.id != tile.id && !it.isJoker }
        // série
        for (start in (tile.number - 2)..tile.number) {
            if (start < 1 || start + 2 > 13) continue
            val wanted = (start..start + 2).filter { it != tile.number }
            val picks = wanted.mapNotNull { n -> others.firstOrNull { it.color == tile.color && it.number == n } }
            if (picks.size == 2 && picks[0].id != picks[1].id) return picks.sortedBy { it.number }
        }
        // groupe
        val sameNumber = others.filter { it.number == tile.number && it.color != tile.color }
            .distinctBy { it.color }
        if (sameNumber.size >= 2) return sameNumber.take(2)
        return null
    }

    // ------------------------------------------------------------------ retrait d'une tuile

    /**
     * Retire [tileId] de [meld]. Un groupe (même numéro, couleurs différentes) reste d'un seul tenant ;
     * une suite à qui on enlève une tuile du milieu est coupée en deux, les extrémités ne la coupent pas.
     * La combinaison d'origine garde son id (partie gauche), la partie droite en reçoit un nouveau.
     */
    fun removeTileSplitting(melds: List<MeldSet>, meld: MeldSet, tileId: String): List<MeldSet> {
        val i = meld.tiles.indexOfFirst { it.id == tileId }
        if (i < 0) return melds
        val left = meld.tiles.subList(0, i).toList()
        val right = meld.tiles.subList(i + 1, meld.tiles.size).toList()

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

    // ------------------------------------------------------------------ jokers

    /**
     * Position d'un joker de [meld] que la tuile [tile] peut remplacer, ou null.
     * La combinaison doit déjà être valide ; le joker ainsi libéré doit être rejoué dans le tour.
     */
    fun jokerSwapPosition(meld: MeldSet, tile: Tile, strategy: JokerPointStrategy = JokerPointStrategy.REPLACED_TILE_VALUE): Int? {
        if (tile.isJoker || meld.tiles.none { it.isJoker }) return null
        val result = BoardValidator.validateMeld(meld, strategy)
        if (!result.isValid) return null
        val tiles = meld.tiles
        return when (result.meldType) {
            MeldType.RUN -> {
                val firstReal = tiles.indexOfFirst { !it.isJoker }
                val start = tiles[firstReal].number - firstReal
                val color = tiles[firstReal].color
                tiles.indices.firstOrNull { i -> tiles[i].isJoker && tile.color == color && tile.number == start + i }
            }
            MeldType.GROUP -> {
                val real = tiles.filter { !it.isJoker }
                val number = real.first().number
                val colors = real.mapNotNull { it.color }.toSet()
                if (tile.number == number && tile.color !in colors) tiles.indexOfFirst { it.isJoker } else null
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ recherche exhaustive

    private class Solver(val counts: Array<IntArray>, jokers: Int, val maximizePoints: Boolean) {
        private var j = jokers
        private val memo = HashMap<String, Best>()
        private var nodes = 0

        private fun key(p: Int): String {
            val sb = StringBuilder()
            sb.append(p).append('|').append(j).append('|')
            for (c in 0 until 4) for (v in 1..13) sb.append(counts[c][v])
            return sb.toString()
        }

        private fun score(points: Int, tiles: Int): Long =
            if (maximizePoints) points * 1000L + tiles else tiles * 1000L + points

        fun solve(p: Int): Best {
            var pos = p
            while (pos < 52 && counts[pos / 13][pos % 13 + 1] == 0) pos++
            if (pos >= 52 || ++nodes > NODE_LIMIT) return Best(0, emptyList())
            val k = key(pos)
            memo[k]?.let { return it }

            val c = pos / 13
            val v = pos % 13 + 1

            // 1) la tuile reste au chevalet
            counts[c][v]--
            var best = solve(pos)
            counts[c][v]++

            // 2) elle ouvre une suite (des jokers peuvent combler les trous)
            counts[c][v]--
            var jokersUsed = 0
            val used = ArrayList<Int>()
            val slots = ArrayList<Slot>()
            slots.add(Slot(c, v))
            var vv = v + 1
            while (vv <= 13) {
                if (counts[c][vv] > 0) {
                    counts[c][vv]--
                    used.add(vv)
                    slots.add(Slot(c, vv))
                } else if (j > 0) {
                    j--
                    jokersUsed++
                    slots.add(Slot(c, vv, joker = true))
                } else {
                    break
                }
                if (slots.size >= 3) {
                    val cand = solve(pos)
                    val total = score((v..vv).sum(), slots.size) + cand.score
                    if (total > best.score) best = Best(total, listOf(slots.toList()) + cand.melds)
                }
                vv++
            }
            for (u in used) counts[c][u]++
            j += jokersUsed
            counts[c][v]++

            // 3) elle ouvre un groupe de numéro v
            counts[c][v]--
            val others = (c + 1 until 4).filter { counts[it][v] > 0 }
            for (mask in 1 until (1 shl others.size)) {
                val chosen = others.indices.filter { mask and (1 shl it) != 0 }.map { others[it] }
                val real = 1 + chosen.size
                for (jk in 0..2) {
                    val total = real + jk
                    if (total !in 3..4 || jk > j) continue
                    chosen.forEach { counts[it][v]-- }
                    j -= jk
                    val cand = solve(pos)
                    j += jk
                    chosen.forEach { counts[it][v]++ }
                    val group = listOf(Slot(c, v)) + chosen.map { Slot(it, v) } + List(jk) { Slot(-1, v, joker = true) }
                    val sc = score(total * v, total) + cand.score
                    if (sc > best.score) best = Best(sc, listOf(group) + cand.melds)
                }
            }
            counts[c][v]++

            memo[k] = best
            return best
        }
    }
}
