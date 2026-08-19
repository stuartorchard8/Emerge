package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage

/**
 * The global construction inventory: what the vessel has to build with, species by species.
 *
 * **This is a view over the vessel's [org.emerge.demo.outofspace.world.machine.Storage] machines, not an account of its own.** Material is
 * available for construction because it is sitting in a warehouse somewhere aboard, and it stops
 * being available the moment that warehouse is emptied, moved or breached. The earlier design had a
 * central node that absorbed deliveries into a separate tally, which meant matter existed in one of
 * two mutually exclusive places and the conservation check had to name both; deriving it instead
 * removes the seam entirely — there is no act of "banking", only of storing.
 *
 * Composition survives storage — a tank filled from filthy ore is still filthy, and whatever gets
 * built from it inherits that. It would be much easier to reduce everything to a count of items
 * here, and it would throw away the point of the chemistry.
 *
 * ⚠️ It is **one heap**, not a heap per form. Two warehouses of different stuff read as their sum,
 * which is what makes the inventory a statement about the vessel rather than about its shelving.
 */
class Stockpile private constructor(val held: Mixture) {

    val totalMass: Long get() = held.total

    val isEmpty: Boolean get() = held.isEmpty

    operator fun get(species: Species): Long = held[species]

    override fun equals(other: Any?): Boolean =
        this === other || (other is Stockpile && held == other.held)

    override fun hashCode(): Int = held.hashCode()

    override fun toString(): String = "Stockpile(${held.total}g $held)"

    companion object {
        val EMPTY: Stockpile = Stockpile(Mixture.EMPTY)

        /** Everything sitting in every storage aboard, in one heap. */
        fun of(grid: Grid, deck: DeckArray, buffers: BufferLayer): Stockpile {
            var any = false
            var total = Mixture.EMPTY
            // Off the deck, which is where warehouses live now. Walked by centre: a tank is three
            // tiles across and stored once, and adding its contents per covered tile would have the
            // inventory report nine times what is aboard.
            for (i in 0 until deck.size) {
                val tile = TileIndex(i)
                val m = deck[tile]
                if (m !is Storage || m.center != tile) continue
                val store = bufferTile(grid, m, tile, BufferRole.Inside) ?: continue
                val held = buffers.resourceAt(store) ?: continue
                if (held.isEmpty) continue
                total += held
                any = true
            }
            return if (any) Stockpile(total) else EMPTY
        }
    }
}
