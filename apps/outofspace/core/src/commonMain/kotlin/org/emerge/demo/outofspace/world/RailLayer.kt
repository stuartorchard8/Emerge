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
 * the vessel. The **merging** path does allocate, deliberately: it reads both lumps out as mixtures
 * so the gate can ask what they are made of. It is the rare case — it happens only when a lump is
 * pressed against one ahead of it that has nowhere to go.
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
     * ⛔ **A tile carrying anything has no room**, however light what it carries — and that is still
     * true now that lumps on a jammed run may combine. This answers for [loadOnto], which is a
     * *producer setting a lump down* and refuses an occupied tile outright; merging happens between
     * two lumps already on the track and goes through [squashInto]. The only sizes that mean anything
     * here are "empty" and "not", which is what keeps every producer's `room <= 0` guard honest —
     * otherwise they size a part-packet against a gap they will then be refused.
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
     * Hand [mass] of what is riding at [from] over to [to], leaving the rest standing where it is.
     *
     * The whole lump when [mass] covers it, and null when there is nothing to hand over.
     *
     * ⛔ **This is a draw, not a merge.** A packet is only ever *added to* under [squashInto]'s gate
     * — but it has always been something that can be **taken from**: a construction site skims what it needs
     * off a lump standing on it and lets the remainder ride on. This is that same skim, performed by
     * a tile that has more than one way out, so that a route wanting 30kg of a 100kg lump takes 30kg
     * and the other 70kg goes the other way. Everything that made merging untenable — a lump's
     * composition changing under whatever was routed toward it, so that "what is on its way to this
     * tile" stopped having a stable answer — is absent here, because [Mixture.take] is proportional:
     * both halves are the blend that arrived, and anything that admitted the whole admits either
     * part.
     *
     * ⚠️ **A slice is only representative while it is big enough to carry every species.** A
     * microgram of a blend is a microgram of whichever species the apportionment lands on, so the
     * caller must ask the door of the slice and not merely of the pile — see the note in
     * `scrapDeconstructing`, where the identical draw minted a speck of pure carbon onto a belt
     * nothing could clear it from. Nothing here can check that; the sizing is the caller's.
     */
    fun splitInto(from: TileIndex, to: TileIndex, mass: Long): Mixture? {
        if (mass <= 0L) return null
        val standing = resourceAt(from) ?: return null
        if (mass >= standing.total) {
            moveInto(from, to)
            return standing
        }
        require(isEmpty(to)) { "something is already riding at $to" }
        val slice = standing.take(mass)
        if (slice.isEmpty) return null
        // The complement, exactly: [Mixture.take] and its remainder sum back to the whole, so this
        // is the one place a split can be written without a gram or a joule going astray.
        put(from, standing - slice)
        put(to, slice)
        return slice
    }

    /**
     * Move the lump at [from] onto [to], **or into it** where the two cannot be told apart by
     * anything that decides where a lump may go.
     *
     * ### Why merging is allowed again, and only like this
     *
     * It used to press one lump into another as far as it would go, and that was withdrawn on
     * 2026-08-19 for a specific reason: a lump's composition changed under whatever happened to be
     * routed at it, so a delivery a construction site had already admitted could turn into one it
     * would have refused, and every question of the form "what is on its way to this tile" stopped
     * having a stable answer. With demand-based flow that is untenable.
     *
     * Two lumps may combine here only if that cannot happen:
     *
     *  - **pure + pure of the same species** — the composition is *identical*, so every filter in
     *    the game gives the same answer about the merged lump as about either part. This is a proof
     *    rather than a judgement, and it holds whatever filters are added later.
     *  - **blend + blend** — the composition does change, and since `SpeciesFilter` collapsed to
     *    pure / mixed / no-opinion there is nothing left that admits some blends and refuses others.
     *    A construction site wants its recipe at `BUILD_PURITY_PERCENT`, an electrolyzer wants pure
     *    water, and a concentrator asks for `MIXED` — which is invariant here, because two blends
     *    cannot combine into a single species.
     *
     * ⛔ **Pure and blend never combine**, which is what keeps the first arm's proof intact: grit
     * poured into a pure lump is exactly the change no filter may be shown.
     *
     * ⚠️ **This revives dead code.** [org.emerge.demo.outofspace.world.advanceSegments] skips any
     * successor that is empty before reaching its squash-forward block, so it only ever called this
     * with an occupied destination — which refused every time. The loop has been unreachable in
     * effect since merging was withdrawn.
     *
     * [Squash.Partial] can therefore happen again, and the caller reads it the way it always meant
     * to: something moved, and the source tile is still occupied by what would not fit.
     */
    fun squashInto(from: TileIndex, to: TileIndex): Squash {
        if (isEmpty(from)) return Squash.Complete
        if (isEmpty(to)) {
            moveInto(from, to)
            return Squash.Complete
        }
        val ahead = resourceAt(to) ?: return Squash.Refused
        val incoming = resourceAt(from) ?: return Squash.Refused
        if (!mayCombine(ahead, incoming)) return Squash.Refused

        val room = Capacity.PACKET_MASS - ahead.total
        if (room <= 0L) return Squash.Refused
        if (room >= incoming.total) {
            put(to, ahead + incoming)
            put(from, null)
            return Squash.Complete
        }
        // ⚠️ A proportional slice, so what stays behind is the same blend that arrived and not the
        // good bits skimmed off it — the property [splitInto] rests on, needed here for the same
        // reason. Both arms keep it: a pure lump has one species to apportion.
        val moving = incoming.take(room)
        if (moving.isEmpty) return Squash.Refused
        put(to, ahead + moving)
        put(from, incoming - moving)
        return Squash.Partial
    }

    /**
     * Whether two lumps may become one — see [squashInto], which argues both arms.
     *
     * Disjoint by construction: a lump is either one species or more than one, and the two arms
     * never see the same pair.
     */
    private fun mayCombine(ahead: Mixture, incoming: Mixture): Boolean {
        val aheadIsPure = ahead.impurities == 0L
        if (aheadIsPure != (incoming.impurities == 0L)) return false
        return !aheadIsPure || ahead.dominant == incoming.dominant
    }

    /**
     * Put [resource] down at [tile], which must be free.
     *
     * False when something is already riding there. ⚠️ **Deliberately not merging**, where
     * [squashInto] does: this is a machine putting fresh material onto the track, and a port that
     * topped up whatever happened to be passing would be blending at the door rather than in a jam.
     * A machine with nowhere to set its output down simply waits, which is what it did whenever the
     * tile ahead was full anyway.
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
