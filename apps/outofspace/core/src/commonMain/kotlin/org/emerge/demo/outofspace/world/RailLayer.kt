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

    /**
     * How much may be set down at [tile]: a whole belt-load, or nothing at all.
     *
     * ⛔ **A tile carrying anything has no room**, however light what it carries. Since a packet is
     * never merged into (see [squashInto]) the only sizes that mean anything are "empty" and "not",
     * and saying so here is what keeps every producer's `room <= 0` guard honest — otherwise they
     * size a part-packet against a gap they will then be refused.
     */
    fun headroom(tile: TileIndex): Long = if (isEmpty(tile)) Capacity.PACKET_MASS else 0L

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
     * Move the lump at [from] onto [to] if [to] is free.
     *
     * ⛔ **A packet is never merged into another packet.** Once minted a lump can be *taken from* —
     * a construction site skims what it needs, a machine lifts it off — but nothing is ever poured
     * into it. Stu, 2026-08-19.
     *
     * It used to press one into the other as far as it would go, which is what made belt blending
     * possible and is why the plan's alloy section named it as a route. It caused more trouble than
     * it was worth: a lump's composition changed under whatever was already routed toward it, so a
     * delivery a construction site had admitted could turn into one it would have refused, and every
     * question of the form "what is on its way to this tile" stopped having a stable answer. That
     * becomes untenable with demand-based flow, where the whole point is knowing what a packet is
     * *before* deciding where it may go. Blending is a **storage** operation now, and only that.
     *
     * [Squash.Partial] can therefore no longer happen, and the type keeps it only because
     * [advanceSegments] reads [Squash.Refused] to mean "try the next way on".
     */
    fun squashInto(from: TileIndex, to: TileIndex): Squash {
        if (isEmpty(from)) return Squash.Complete
        if (!isEmpty(to)) return Squash.Refused
        moveInto(from, to)
        return Squash.Complete
    }

    /**
     * Put [resource] down at [tile], which must be free.
     *
     * False when something is already riding there — see [squashInto] for why nothing is ever added
     * to a lump that already exists. A machine with nowhere to set its output down simply waits,
     * which is what it did whenever the tile ahead was full anyway.
     */
    fun loadOnto(tile: TileIndex, resource: Mixture): Boolean {
        if (!isEmpty(tile)) return false
        put(tile, resource)
        return true
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
