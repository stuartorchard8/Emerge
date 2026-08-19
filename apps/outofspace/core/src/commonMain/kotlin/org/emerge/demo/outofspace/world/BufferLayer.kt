package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.machine.DeckArray

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
 * A buffer holds nothing but a [Mixture]. It used to carry a `Form` alongside — what the matter had
 * been *made into* — kept here rather than on the machine so the two could never drift apart. Form
 * is gone, and with it the only thing this layer held that was not mass.
 */
class BufferLayer(val stuff: StuffLayer) {

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
    fun claimRoles(grid: Grid, machine: DeckMachine, centre: TileIndex) {
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
    fun releaseRoles(grid: Grid, machine: DeckMachine, centre: TileIndex) {
        for (role in BufferRole.entries) {
            val tile = bufferTile(grid, machine, centre, role) ?: continue
            if (hasRole(tile)) releaseRole(tile)
        }
    }

    /** Give up the store at [tile] entirely — whatever it held goes with it. */
    fun releaseRole(tile: TileIndex) {
        stuff.release(tile)
    }

    /** What the store at [tile] holds, or null if it is empty. Allocates; not for the hot path. */
    fun resourceAt(tile: TileIndex): Mixture? {
        if (!stuff.occupies(tile)) return null
        val masses = LongArray(Species.COUNT)
        stuff.forEachSpecies(tile) { s, mass -> masses[s.ordinal] = mass }
        val mixture = Mixture.of(masses, stuff.energyAt(tile))
        return if (mixture.isEmpty) null else mixture
    }

    /** Replace whatever is at [tile] with [resource], or empty it if null. */
    fun put(tile: TileIndex, resource: Mixture?) {
        if (resource == null || resource.isEmpty) {
            for (s in Species.ALL) stuff[tile, s] = 0L
            stuff.setEnergy(tile, 0L)
            return
        }
        for (s in Species.ALL) stuff[tile, s] = resource[s]
        stuff.setEnergy(tile, resource.energy)
    }

    /** Total mass in the store at [tile]. Walks only what is present. */
    fun massAt(tile: TileIndex): Long = stuff.massAt(tile)

    /** Every gram in every buffer aboard — the ledger's "what the vessel is carrying" term. */
    val totalMass: Long get() = stuff.totalMass

    val totalEnergy: Long get() = stuff.totalEnergy

    /** How many tiles this layer is stated over — must match the world it belongs to. */
    val tileCount: Int get() = stuff.tileCount

    fun copyOf(): BufferLayer = BufferLayer(stuff.copyOf())

    override fun equals(other: Any?): Boolean =
        this === other || (other is BufferLayer && stuff == other.stuff)

    override fun hashCode(): Int = stuff.hashCode()

    fun checkInvariants() {
        stuff.checkInvariants()
    }

    companion object {
        fun empty(tileCount: Int): BufferLayer = BufferLayer(StuffLayer.empty(tileCount))

        /**
         * A layer with a store already standing wherever the deck needs one, all empty.
         *
         * The default for a world stated rather than built. Going through the reducer, a store is
         * claimed as its machine goes up; a [VesselState] assembled directly — by a fixture, a save
         * or the starter vessel — never runs that code, and would otherwise hold a warehouse with no
         * store to put anything in. Deriving it from the machine list means the two routes cannot
         * disagree about which tiles have stores.
         */
        fun forDeck(grid: Grid, deck: DeckArray): BufferLayer {
            val out = empty(deck.size)
            for (tile in grid.tiles) {
                val m = deck[tile] ?: continue
                out.claimRoles(grid, m, tile)
            }
            return out
        }
    }
}
