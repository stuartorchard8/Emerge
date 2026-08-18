package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.machine.Placed

/**
 * Machine buffers — every input, output, waste and processing store in the vessel, on **one** layer.
 *
 * ### One layer, spread across the footprint
 *
 * A machine's buffers do not share a tile: an input store sits on the machine's input port, an
 * output store on its output port, waste on the waste port, and a processing store — the one role
 * with no port to live on — at the machine's centre. Since [Port] offsets are taken inside the
 * footprint, a size-3 or size-5 machine has four distinct tiles for those four roles with the centre
 * to spare, so one layer can hold all of them without a slot index. That is what makes "iron waiting
 * to go in" and "iron waiting to come out" separable while still being one array.
 *
 * ⚠️ **Only `reach == 0` machines can break that.** A one-tile machine's ports all resolve to its
 * own centre, so two distinct roles there would silently become one store. No machine does this
 * today — a thruster has one input, a vent's four inputs feed one buffer, and pumps and sensors have
 * no ports at all — and [claimRole] refuses rather than merging if one ever tries.
 *
 * ### Why [Form] lives here and not on the machine
 *
 * A buffer holds a [Resource], which is a [Form] *and* a [Mixture], and the mass side of that goes
 * into [stuff] like everything else. The form has to go somewhere, and putting it on the machine
 * would recreate precisely the split this storage exists to close: the matter would live at a port
 * tile while the label describing it lived on a data class that `copy()` re-seats independently, so
 * a buffer could end up as ore that thinks it is ingots. Here they are written and cleared together.
 *
 * Form is *packaging*, not chemistry — nothing in a reaction reads it — so a chemical pass over
 * [stuff] ignores it entirely, which is why it can ride along without complicating that walk.
 */
class BufferLayer(val stuff: StuffLayer, private val forms: IntArray) {

    /** What form the store at [tile] is holding, or null if it holds nothing. */
    fun formAt(tile: TileIndex): Form? =
        forms[tile.index].let { if (it == NO_FORM) null else Form.ALL[it] }

    /** True if a store has been placed at [tile] — it may still be empty. */
    fun hasRole(tile: TileIndex): Boolean = stuff.occupies(tile)

    /**
     * Reserve [tile] as a buffer store. Refuses if one is already there, which is the guard against
     * two roles of a one-tile machine quietly becoming one store.
     */
    fun claimRole(tile: TileIndex) {
        require(!stuff.occupies(tile)) { "a buffer store already stands at $tile" }
        stuff.claim(tile)
    }

    /**
     * Stand up every store [machine] keeps, all empty. Idempotent, because the routes that reach a
     * world disagree about who has already claimed what: the reducer claims as it builds, a save
     * fills stores before the state exists, and a fixture states a machine list and nothing else.
     */
    fun claimRoles(grid: Grid, machine: Placed, centre: TileIndex) {
        for (role in BufferRole.entries) {
            if (localBufferOffset(machine, role) == NO_OFFSET) continue
            // Loud, because the alternative is a machine that stands but has nowhere to put
            // anything — and that only shows up as a null far away, in whichever function first
            // reaches for the store. A store off the edge of the grid means the machine was placed
            // where its own footprint does not fit.
            val tile = bufferTile(grid, machine, centre, role)
                ?: error("$machine at $centre has its $role store off the grid")
            if (!hasRole(tile)) claimRole(tile)
        }
    }

    /** Take down every store [machine] keeps, discarding whatever is in them. */
    fun releaseRoles(grid: Grid, machine: Placed, centre: TileIndex) {
        for (role in BufferRole.entries) {
            val tile = bufferTile(grid, machine, centre, role) ?: continue
            if (hasRole(tile)) releaseRole(tile)
        }
    }

    /** Give up the store at [tile] entirely — whatever it held goes with it. */
    fun releaseRole(tile: TileIndex) {
        stuff.release(tile)
        forms[tile.index] = NO_FORM
    }

    /** What the store at [tile] holds, or null if it is empty. Allocates; not for the hot path. */
    fun resourceAt(tile: TileIndex): Resource? {
        val form = formAt(tile) ?: return null
        val masses = LongArray(Species.COUNT)
        stuff.forEachSpecies(tile) { s, mass -> masses[s.ordinal] = mass }
        val mixture = Mixture.of(masses, stuff.energyAt(tile))
        return if (mixture.isEmpty) null else Resource(form, mixture)
    }

    /**
     * Replace whatever is at [tile] with [resource], or empty it if null.
     *
     * Emptying clears the form too. A store that holds nothing has no form — the alternative is a
     * tank that still claims to be full of ingots after the last one leaves, which is what a
     * [Sensor] would then read.
     */
    fun put(tile: TileIndex, resource: Resource?) {
        if (resource == null || resource.isEmpty) {
            for (s in Species.ALL) stuff[tile, s] = 0L
            stuff.setEnergy(tile, 0L)
            forms[tile.index] = NO_FORM
            return
        }
        forms[tile.index] = resource.form.ordinal
        for (s in Species.ALL) stuff[tile, s] = resource.mixture[s]
        stuff.setEnergy(tile, resource.mixture.energy)
    }

    /** Total mass in the store at [tile]. Walks only what is present. */
    fun massAt(tile: TileIndex): Long = stuff.massAt(tile)

    /** Every gram in every buffer aboard — the ledger's "what the vessel is carrying" term. */
    val totalMass: Long get() = stuff.totalMass

    val totalEnergy: Long get() = stuff.totalEnergy

    /** How many tiles this layer is stated over — must match the world it belongs to. */
    val tileCount: Int get() = forms.size

    fun copyOf(): BufferLayer = BufferLayer(stuff.copyOf(), forms.copyOf())

    override fun equals(other: Any?): Boolean =
        this === other || (other is BufferLayer && stuff == other.stuff && forms.contentEquals(other.forms))

    override fun hashCode(): Int = 31 * stuff.hashCode() + forms.contentHashCode()

    /** Asserts a store has a form exactly when it has matter. */
    fun checkInvariants() {
        stuff.checkInvariants()
        for (i in forms.indices) {
            val tile = TileIndex(i)
            val hasMatter = stuff.massAt(tile) > 0L
            require(!hasMatter || forms[i] != NO_FORM) { "tile $i holds matter with no form" }
            require(forms[i] == NO_FORM || hasMatter) { "tile $i has a form but holds nothing" }
        }
    }

    companion object {
        private const val NO_FORM: Int = -1

        fun empty(tileCount: Int): BufferLayer =
            BufferLayer(StuffLayer.empty(tileCount), IntArray(tileCount) { NO_FORM })

        /**
         * A layer with a store already standing wherever [machines] needs one, all empty.
         *
         * The default for a world stated rather than built. Going through the reducer, a store is
         * claimed as its machine goes up; a [VesselState] assembled directly — by a fixture, a save
         * or the starter vessel — never runs that code, and would otherwise hold a warehouse with no
         * store to put anything in. Deriving it from the machine list means the two routes cannot
         * disagree about which tiles have stores.
         */
        fun forMachines(grid: Grid, machines: List<Machine?>): BufferLayer {
            val out = empty(machines.size)
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                out.claimRoles(grid, m, TileIndex(i))
            }
            return out
        }
    }
}
