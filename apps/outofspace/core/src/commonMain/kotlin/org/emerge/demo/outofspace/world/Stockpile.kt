package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage

/**
 * The global construction inventory: what the vessel has to build with, species by species.
 *
 * **This is a view over the vessel's [org.emerge.demo.outofspace.world.machine.Storage] machines, not
 * an account of its own.** Material is available for construction because it is sitting in a
 * warehouse somewhere aboard, and it stops being available the moment that warehouse is emptied,
 * moved or breached. The earlier design had a central node that absorbed deliveries into a separate
 * tally, which meant matter existed in one of two mutually exclusive places and the conservation
 * check had to name both; deriving it instead removes the seam entirely — there is no act of
 * "banking", only of storing.
 *
 * ### ⛔ Two questions, and it used to be able to answer only the useless one
 *
 * This was **one heap**: every storage summed into a single [Mixture], defended on the grounds that
 * "two warehouses of different stuff read as their sum, which is what makes the inventory a
 * statement about the vessel rather than about its shelving". That is the right answer to *how much
 * matter is aboard* and it is exactly the wrong one for *what can I build*, because summing is what
 * destroys the only information the second question needs.
 *
 * ⚠️ **It became a contradiction the day [BUILD_PURITY_PERCENT] went to 100.** A bill can now only be
 * satisfied by a delivery that is *exactly* its species, a delivery is a proportional slice of one
 * storage's contents, and therefore a storage can supply a species **only if it holds nothing else**.
 * Buildability is a per-storage fact. A vessel with ten tonnes of pure iron in one tank and ten of
 * pure titanium in the next can build from either — and the summed heap reports a 50/50 blend with
 * no dominant species and nothing buildable at all.
 *
 * Measured on a real save before this changed: 187 machines, 36.9 t stored, and the whole of the
 * readout was **"53% WATER"**. Nothing about iron, titanium, steel or anything a machine is made of.
 *
 * So there are two views now and they answer their own questions. [held] is the old heap, still the
 * honest answer to "how much is aboard". [buildable] is per species, counts only storages that are
 * pure in it, and is what a material picker reads.
 *
 * Composition survives storage — a tank filled from filthy ore is still filthy, and whatever gets
 * built from it inherits that. It would be much easier to reduce everything to a count of items
 * here, and it would throw away the point of the chemistry.
 */
class Stockpile private constructor(
    /** Everything in every storage aboard, in one heap. How much is aboard, not what it can become. */
    val held: Mixture,
    /** Per [Species.ordinal]: mass held in storages that hold **only** that species. */
    private val pure: LongArray,
) {

    val totalMass: Long get() = held.total

    val isEmpty: Boolean get() = held.isEmpty

    operator fun get(species: Species): Long = held[species]

    /**
     * How much of [species] is sitting somewhere a construction site could actually draw it from.
     *
     * ⛔ **Counts only storages holding nothing but [species]**, which is not a conservatism — it is
     * what [buildableFrom] will do at the tile. A tank of 99% iron emits 99% iron packets, because a
     * packet is a proportional sample and not the good bits skimmed off, and every one of them is
     * refused. Reporting that tank as iron the player can build with would be the inventory lying
     * about the only thing it is being asked.
     */
    fun buildable(species: Species): Long = pure[species.ordinal]

    /** Every species there is a buildable quantity of, heaviest first — what a picker offers. */
    val buildableSpecies: List<Species>
        get() = Species.ALL.filter { pure[it.ordinal] > 0L }.sortedByDescending { pure[it.ordinal] }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Stockpile && held == other.held && pure.contentEquals(other.pure))

    override fun hashCode(): Int = 31 * held.hashCode() + pure.contentHashCode()

    override fun toString(): String = "Stockpile(${held.total}g $held)"

    companion object {
        val EMPTY: Stockpile = Stockpile(Mixture.EMPTY, LongArray(Species.COUNT))

        /** Everything sitting in every storage aboard: one heap, and the per-species pure tally. */
        fun of(grid: Grid, deck: DeckArray, buffers: BufferLayer): Stockpile {
            var any = false
            var total = Mixture.EMPTY
            val pure = LongArray(Species.COUNT)
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
                // ⚠️ **Per storage, and this is the whole reason the loop cannot just sum.** Purity
                // is a fact about one tank; once two are added together it is gone and cannot be
                // recovered from the sum.
                val dominant = held.dominant
                if (dominant != null && held[dominant] == held.total) {
                    pure[dominant.ordinal] += held.total
                }
                any = true
            }
            return if (any) Stockpile(total, pure) else EMPTY
        }
    }
}
