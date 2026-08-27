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
    /** Per [Species.ordinal]: pure mass the network can already deliver — tanks, buffers, belts. */
    private val loose: LongArray,
    /** Per [Species.ordinal]: pure mass built into the vessel, recoverable only by taking it apart. */
    private val fabric: LongArray,
) {

    val totalMass: Long get() = held.total

    val isEmpty: Boolean get() = held.isEmpty

    operator fun get(species: Species): Long = held[species]

    /**
     * How much of [species] the network could deliver to a site **right now** — what is in tanks, in
     * machine buffers, and riding on belts.
     *
     * ⛔ **Counts only stores holding nothing but [species]**, which is not a conservatism — it is
     * what [buildableFrom] will do at the tile. A tank of 99% iron emits 99% iron packets, because a
     * packet is a proportional sample and not the good bits skimmed off, and every one of them is
     * refused. Reporting that tank as iron the player can build with would be the inventory lying
     * about the only thing it is being asked.
     */
    fun buildable(species: Species): Long = loose[species.ordinal]

    /**
     * How much of [species] is **built into the vessel** — casings and laid conduit — and so is
     * recoverable only by marking something for deconstruction.
     *
     * ⛔ **Counted, and counted separately, and both halves of that matter.** Stu stores his iron by
     * laying track he is not using yet, which is a perfectly good warehouse and one the old view was
     * blind to: it would have told him he had no iron while he was standing on tonnes of it. But it
     * is not the same as iron in a tank, and adding the two would have the panel promise a build the
     * network cannot start — so they are two numbers and the player is told which is which.
     */
    fun inFabric(species: Species): Long = fabric[species.ordinal]

    /**
     * Species the network could deliver right now, heaviest first — the picker's shortlist.
     *
     * ⛔ **Separate from [fabricSpecies] rather than one list ordered somehow**, and the attempt to
     * merge them is what proved it. Ranked on the sum, a vessel's own casings outweigh anything in
     * its tanks almost by definition, so whatever the ship is *made of* takes the top of every list
     * and buries what the player has. Ranked on loose with fabric as a tie-break, every fabric-only
     * metal sorts below every ore in a hold and falls off the end of the panel — which is how a save
     * with tonnes of titanium in it reported none. They are two questions and they get two lists.
     */
    val buildableSpecies: List<Species>
        get() = Species.ALL.filter { loose[it.ordinal] > 0L }.sortedByDescending { loose[it.ordinal] }

    /** Species built into the vessel, heaviest first — what a deconstruction order would free. */
    val fabricSpecies: List<Species>
        get() = Species.ALL.filter { fabric[it.ordinal] > 0L }.sortedByDescending { fabric[it.ordinal] }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is Stockpile && held == other.held &&
                loose.contentEquals(other.loose) && fabric.contentEquals(other.fabric)
            )

    override fun hashCode(): Int =
        31 * (31 * held.hashCode() + loose.contentHashCode()) + fabric.contentHashCode()

    override fun toString(): String = "Stockpile(${held.total}g $held)"

    companion object {
        val EMPTY: Stockpile =
            Stockpile(Mixture.EMPTY, LongArray(Species.COUNT), LongArray(Species.COUNT))

        /**
         * Everything aboard, sorted into what the network can move and what is built into the ship.
         *
         * ⛔ **Four stores, because the vessel keeps material in four places** and a player uses all
         * of them. Tanks and machine buffers and belts are *loose* — the network can pick them up
         * and take them somewhere. Casings and laid conduit are *fabric* — the same metal, and it
         * takes a deconstruction order to get at it.
         *
         * ⚠️ **A storage's contents are a buffer**, so walking the buffer layer already covers every
         * tank; [held] is measured on its own pass because "how much is in storage" is a different
         * question and `stockpileMass` has always answered it.
         */
        fun of(
            grid: Grid,
            deck: DeckArray,
            buffers: BufferLayer,
            rail: RailLayer,
            conduits: Conduits,
        ): Stockpile {
            var any = false
            var stored = Mixture.EMPTY
            val loose = LongArray(Species.COUNT)
            val fabric = LongArray(Species.COUNT)

            // ── What is in storage, for [held] ──
            //
            // Walked by centre: a tank is three tiles across and stored once, and adding its
            // contents per covered tile would have the inventory report nine times what is aboard.
            for (i in 0 until deck.size) {
                val tile = TileIndex(i)
                val m = deck[tile]
                if (m !is Storage || m.center != tile) continue
                val store = bufferTile(grid, m, tile, BufferRole.Inside) ?: continue
                val inside = buffers.resourceAt(store) ?: continue
                if (inside.isEmpty) continue
                stored += inside
                any = true
            }

            // ── Loose: every machine store aboard, tanks included, and everything on a belt ──
            for (layer in listOf(buffers.stuff, rail.stuff)) {
                layer.forEachOccupiedTile { tile ->
                    layer.pureSpeciesAt(tile)?.let { loose[it.ordinal] += layer.massAt(tile) }
                }
            }

            // ── Fabric: casings, and the metal in every length of conduit ──
            //
            // ⚠️ Per *tile* and not per machine, unlike the storage walk above: a casing is spread
            // across a footprint rather than held at one address, so every covered tile carries its
            // own share and all of them count.
            deck.stuff.forEachOccupiedTile { tile ->
                deck.stuff.pureSpeciesAt(tile)?.let { fabric[it.ordinal] += deck.stuff.massAt(tile) }
            }
            for (conduit in Conduit.entries) {
                val layer = conduits.tracks[conduit]
                layer.forEachOccupiedTile { tile ->
                    layer.pureSpeciesAt(tile)?.let { fabric[it.ordinal] += layer.massAt(tile) }
                }
            }

            val anything = any || loose.any { it > 0L } || fabric.any { it > 0L }
            return if (anything) Stockpile(stored, loose, fabric) else EMPTY
        }
    }
}
