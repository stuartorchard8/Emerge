package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.SolidPacket


/**
 * Everything riding on the track — one lump per tile, on the same storage every other layer uses.
 *
 * ### Occupied means loaded
 *
 * Unlike [BufferLayer], there is nothing to claim. A store is a thing a machine *has* whether or not
 * anything is in it, so a buffer tile stays occupied while empty; a lump of ore is not. Track itself
 * lives in [Conduits] and is unaffected by whether anything is on it, so occupancy here means
 * exactly "there is a packet at this tile" and nothing else.
 *
 * ### Why a tile holds at most one
 *
 * That is the existing rule, not a new one: a segment held a single `Packet?`. So tile → (form,
 * mixture) loses nothing. Two ingots of the same metal on one tile were never representable and
 * still are not — [squashOnto]'s powder rule is about *whether a second lump may join*, which is a
 * rule on the operation and not on the storage.
 *
 * ### What is worth reading closely
 *
 * [moveInto] and the empty case of [squashInto] never allocate: they walk the source's present
 * species and hand the masses over. That matters because they are the inner loop of every belt in
 * the vessel. The partial-merge path *does* allocate, deliberately — it defers to [squashOnto] so
 * the capacity, form and powder rules live in one place, and it is the rare case: it happens only
 * when a lump is pressed against a partly-full one ahead of it.
 */
/** What came of pressing one lump into another — see [RailLayer.squashInto]. */
enum class Squash { Refused, Partial, Complete }

class RailLayer(val stuff: StuffLayer) {

    /** True when nothing is riding at [tile]. */
    fun isEmpty(tile: TileIndex): Boolean = !stuff.occupies(tile) || stuff.massAt(tile) == 0L

    /** Total mass riding at [tile]. Walks only what is present. */
    fun massAt(tile: TileIndex): Long = if (stuff.occupies(tile)) stuff.massAt(tile) else 0L

    /** How much more this tile could take before it is a full belt-load. */
    fun headroom(tile: TileIndex): Long = (Capacity.PACKET_MASS - massAt(tile)).coerceAtLeast(0L)

    /** The lump at [tile], or null if there is none. Allocates; not for the hot path. */
    fun resourceAt(tile: TileIndex): Mixture? {
        if (tile.index < 0 || tile.index >= stuff.tileCount) return null
        if (!stuff.occupies(tile)) return null
        val masses = LongArray(Species.COUNT)
        stuff.forEachSpecies(tile) { s, mass -> masses[s.ordinal] = mass }
        val mixture = Mixture.of(masses, stuff.energyAt(tile))
        return if (mixture.isEmpty) null else mixture
    }

    /** The lump at [tile] as the packet the logistics code speaks in, or null. Allocates. */
    fun packetAt(tile: TileIndex): SolidPacket? = resourceAt(tile)?.let { SolidPacket(it) }

    /** Put [resource] at [tile], replacing whatever was there, or clear the tile if it is null. */
    fun put(tile: TileIndex, resource: Mixture?) {
        if (resource == null || resource.isEmpty) {
            if (stuff.occupies(tile)) stuff.release(tile)
            return
        }
        stuff.claim(tile)
        for (s in Species.ALL) stuff[tile, s] = resource[s]
        stuff.setEnergy(tile, resource.energy)
    }

    /**
     * Hand the whole lump at [from] over to [to], which must be empty. Allocates nothing.
     *
     * This is a belt advancing, and it is the one operation that happens to every loaded tile of
     * every run on every step — so it walks the source's present species directly rather than going
     * out through a [Mixture] and back.
     */
    fun moveInto(from: TileIndex, to: TileIndex) {
        require(isEmpty(to)) { "something is already riding at $to" }
        if (isEmpty(from)) return
        stuff.claim(to)
        stuff.forEachSpecies(from) { s, mass -> stuff[to, s] = mass }
        stuff.setEnergy(to, stuff.energyAt(from))
        stuff.release(from)
    }

    /**
     * Press the lump at [from] into the one at [to], as far as it will go.
     *
     * [Squash.Refused] and [Squash.Partial] are different answers and the caller needs both: a belt
     * backing up part way has still moved matter and the tile is spoken for, while a refusal has
     * changed nothing and the lump should try the next way on.
     */
    fun squashInto(from: TileIndex, to: TileIndex): Squash {
        if (isEmpty(from)) return Squash.Complete
        if (isEmpty(to)) {
            moveInto(from, to)
            return Squash.Complete
        }
        val ahead = packetAt(to) ?: return Squash.Refused
        val incoming = packetAt(from) ?: return Squash.Complete
        val merged = squashOnto(ahead, incoming) ?: return Squash.Refused
        put(to, (merged.merged as SolidPacket).contents)
        put(from, (merged.rejected as? SolidPacket)?.contents)
        return if (merged.rejected == null) Squash.Complete else Squash.Partial
    }

    /**
     * Put [resource] down at [tile], merging with whatever is already there.
     *
     * False when none of it would go — a different form, a lump that is not a powder, or no room.
     *
     * ⚠️ It also answers false for a **partial** merge, having already written the part that fit.
     * That is what it has always done, and the one caller where the difference is reachable is
     * [org.emerge.demo.outofspace.OutofspaceReducer]'s bridge deposit.
     */
    fun loadOnto(tile: TileIndex, resource: Mixture): Boolean {
        if (isEmpty(tile)) {
            put(tile, resource)
            return true
        }
        val ahead = packetAt(tile) ?: return false
        val merged = squashOnto(ahead, SolidPacket(resource)) ?: return false
        put(tile, (merged.merged as SolidPacket).contents)
        return merged.rejected == null
    }

    /** Every gram on the track — the ledger's "in transit" term. */
    val totalMass: Long get() = stuff.totalMass

    val totalEnergy: Long get() = stuff.totalEnergy

    /** How many tiles this layer is stated over — must match the world it belongs to. */
    val tileCount: Int get() = stuff.tileCount

    fun copyOf(): RailLayer = RailLayer(stuff.copyOf())

    override fun equals(other: Any?): Boolean =
        this === other || (other is RailLayer && stuff == other.stuff)

    override fun hashCode(): Int = stuff.hashCode()

    fun checkInvariants() {
        stuff.checkInvariants()
    }

    companion object {
        fun empty(tileCount: Int): RailLayer = RailLayer(StuffLayer.empty(tileCount))
    }
}
