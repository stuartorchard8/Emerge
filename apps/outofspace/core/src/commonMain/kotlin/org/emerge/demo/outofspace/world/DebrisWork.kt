package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Resource

/** Mutable working copy of a [Debris] for one tick, so the reducer can spill and settle in passes. */
class DebrisWork(debris: Debris) {
    private val piles: MutableMap<Int, MutableList<Resource>> =
        debris.tiles().associateWithTo(LinkedHashMap()) { debris[it].toMutableList() }

    fun massAt(tile: Int): Long {
        var sum = 0L
        for (r in piles[tile] ?: return 0L) sum += r.mass
        return sum
    }

    /** Drops [spoils] onto a tile, merging into any pile of the same form already there. */
    fun spill(tile: Int, spoils: List<Resource>) {
        for (r in spoils) {
            if (r.mass <= 0L) continue
            val pile = piles.getOrPut(tile) { mutableListOf() }
            val at = pile.indexOfFirst { it.form == r.form }
            if (at >= 0) pile[at] = Resource(r.form, pile[at].mixture + r.mixture)
            else pile.add(r)
        }
    }

    /** Empties a tile and hands back what was on it. */
    fun clear(tile: Int): List<Resource> = piles.remove(tile) ?: emptyList()

    fun tiles(): List<Int> = piles.keys.sorted()

    fun snapshot(): Debris = Debris.of(piles)
}
