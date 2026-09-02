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
    /**
     * Per [Species.ordinal]: pure mass the network can deliver **without being asked to do anything
     * first** — tanks, buffers, belts, and anything already marked to come apart.
     */
    private val loose: LongArray,
    /**
     * Everything loose aboard that is **not** a single species — what the ore order would deliver.
     *
     * ⛔ **The complement of [loose], drawn by the same question.** A tile holding one species is
     * deliverable as that species and a tile holding two is deliverable only as ore, which is
     * exactly the partition the mouth's orders draw. Deriving the counter's ore figure any other way
     * — total held less the sum of the pure columns, say — would offer a number the network will not
     * honour, because `held` is storages only and `loose` counts belts too.
     */
    val blended: Mixture,
    /** Per [Species.ordinal]: pure mass built into the vessel, needing a deconstruction order first. */
    private val fabric: LongArray,
) {

    val totalMass: Long get() = held.total

    val isEmpty: Boolean get() = held.isEmpty

    operator fun get(species: Species): Long = held[species]

    /**
     * How much of [species] the network could deliver to a site **without further instruction** —
     * what is in tanks, in machine buffers, riding on belts, or standing in something the player has
     * already marked for deconstruction.
     *
     * ⛔ **Marked fabric belongs here and not below**, which is Stu's correction and a better rule
     * than the one it replaces: a machine told to come apart *will* hand its metal to the network as
     * soon as there is demand for it, so it is honestly available and a site built from it is a site
     * that will finish. Counting it as fabric would have the picker refuse a material the player has
     * already done the work of freeing.
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
     *
     * ⚠️ **The line between the two is an *order*, not a location.** Mark a machine for
     * deconstruction and its metal moves from here to [buildable] without moving an inch, because
     * what separates them is whether the network has been told it may have it.
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
        /**
         * **What creative mode lets you build from whatever the ship is actually carrying.**
         *
         * ⛔ **Not stock, and deliberately not modelled as stock.** Nothing in this game is made of
         * anything by default — see `Segment.material` — so with the defaults gone a creative player
         * with an empty hold could not put down so much as one tile of track, which is the opposite
         * of what creative mode is for. The answer is not to give building a fallback substance but
         * to give the *player* a small standing allowance, and to say so where they can see it: it
         * is a property of the mode, not a property of a rail.
         *
         * Three, one of each thing a ship is made of and one that is not a metal at all: a structural
         * metal, a conductor, and a rock. Enough to build anything and to feel the difference between
         * the choices, without being a materials catalogue.
         *
         * ⚠️ **Offered below whatever is actually aboard** (Stu), and only where it is not aboard
         * already — a creative player with forty tonnes of titanium should be choosing from that
         * first, and should not see the same species listed twice.
         */
        val CREATIVE_MATERIALS: List<Species> =
            listOf(Species.Steel, Species.Copper, Species.Forsterite)

        val EMPTY: Stockpile =
            Stockpile(Mixture.EMPTY, LongArray(Species.COUNT), Mixture.EMPTY, LongArray(Species.COUNT))

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
            /** Machines told to come apart: their casings count as loose, not as fabric. */
            scrapping: Set<TileIndex> = emptySet(),
        ): Stockpile {
            var any = false
            var stored = Mixture.EMPTY
            val loose = LongArray(Species.COUNT)
            val blended = LongArray(Species.COUNT)
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
                    val pure = layer.pureSpeciesAt(tile)
                    // ⛔ **The two are exclusive and together they are everything loose.** A tile
                    // holding one species is deliverable *as* that species; a tile holding two is
                    // deliverable only as ore. That is the same partition the mouth's orders draw —
                    // see [org.emerge.demo.outofspace.world.machine.SellOrder] — and it has to be
                    // drawn by the same question, or the counter would offer a number the network
                    // will not honour.
                    if (pure != null) loose[pure.ordinal] += layer.massAt(tile)
                    else layer.forEachSpecies(tile) { s, mass -> blended[s.ordinal] += mass }
                }
            }

            // ── Fabric: casings, and the metal in every length of conduit ──
            //
            // ⚠️ Per *tile* and not per machine, unlike the storage walk above: a casing is spread
            // across a footprint rather than held at one address, so every covered tile carries its
            // own share and all of them count.
            // ⛔ **Walked by MACHINE and not by occupied tile**, which is the one thing this loop
            // cannot be talked out of. A machine is marked at its *centre* while its casing is
            // spread across its footprint, and `deck[tile]` answers only for the centre — so
            // classifying tile by tile put one ninth of every three-by-three tank in the right
            // column and eight ninths in the wrong one. Asking the machine for `tiles(grid)` is the
            // only form where the mark and the matter are looked up by the same key.
            for (i in 0 until deck.size) {
                val tile = TileIndex(i)
                val m = deck[tile] ?: continue
                if (m.center != tile) continue
                val bound = if (tile in scrapping) loose else fabric
                for (part in m.tiles(grid)) {
                    val species = deck.stuff.pureSpeciesAt(part) ?: continue
                    bound[species.ordinal] += deck.stuff.massAt(part)
                }
            }
            for (conduit in Conduit.entries) {
                val layer = conduits.tracks[conduit]
                layer.forEachOccupiedTile { tile ->
                    val species = layer.pureSpeciesAt(tile) ?: return@forEachOccupiedTile
                    // A segment carries its own mark rather than being named in a set.
                    val marked = conduits.at(conduit, tile)?.deconstructing == true
                    val bound = if (marked) loose else fabric
                    bound[species.ordinal] += layer.massAt(tile)
                }
            }

            val anything = any || loose.any { it > 0L } || fabric.any { it > 0L }
            return if (anything) Stockpile(stored, loose, Mixture.of(blended, 0L), fabric) else EMPTY
        }
    }
}
