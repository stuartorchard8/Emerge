package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.TileIndex

/**
 * A machine that states, **by species, what may be sent to it**.
 *
 * ⛔ **The list is the whole of such a machine's appetite.** It becomes an
 * [org.emerge.demo.outofspace.world.Acceptance] at the machine's own input port, so the network only
 * ever *routes* here what is on it — nothing travels toward a place that cannot use it. What the
 * player is deciding is therefore not "what does this machine keep" but "what does the vessel send
 * it", and those are one decision because in this game they are one mechanism.
 *
 * ⛔ **An EMPTY book refuses everything; it does not mean "no opinion".** A tile that states no
 * acceptance at all takes anything for ever — see `sinkAdmits`, where "nothing stated means
 * anything" is written down — so a machine wearing this interface has to state its emptiness out
 * loud. Place one and the belts back up behind it until a row is ticked, which is correct and not a
 * failure to explain away.
 *
 * ⚠️ **Which is why this is an interface and not a field two machines happen to share.** The rule
 * above is easy to state and easy to forget: the `accepts` build has to write the machine's entry
 * *outside* the loop over its species, or a shut machine reads as an open door. Every reader of a
 * book — the demand pass, the panel, the save, the stamp — asks the same three questions, and asking
 * them of one type is what stops the third machine to grow a list from being the one that gets the
 * empty case wrong.
 *
 * ### Pure and mixed are two categories, and the counter drew the line first
 *
 * ⛔ **A species on the [whitelist] means that species PURE**, and a blend of anything is [ore] —
 * one switch of its own. This is `DockingPort`'s partition, taken whole and for its reasons: a tile
 * holding one species is deliverable *as* that species and a tile holding two is deliverable only as
 * ore, so that is the only line the network can honour. Ticking IRON therefore does not consent to
 * sending rock that happens to have iron in it.
 *
 * ⚠️ **The two never compete for the same lump**, because pure and mixed are complementary — see
 * [org.emerge.demo.outofspace.world.SpeciesFilter.MIXED].
 *
 * ### Who wears it
 *
 * [Ejector], for whom the book is a list of things to destroy, and [Furnace], for whom it is a list
 * of things to cook. Two very different machines asking the network one identical question, which is
 * the argument for the type: a decomposer set to 1100 K wants ammonia and serpentine and nothing
 * else on the vessel, and until it could *say* so the only way to feed it was a tank in front of its
 * mouth locked to one species at a time. Stu, 2026-09-10.
 */
interface FeedBook {
    /**
     * Where the machine stands — the only thing this needs off [DeckMachine], and the reason it does
     * not extend it.
     *
     * ⛔ **[DeckMachine] is SEALED, and a new subtype of it is a new branch in every exhaustive
     * `when` in the game** — eight of them, each of which would have to decide what a "feed book" is
     * where it means to be deciding what an *ejector* is. This is a capability two machines have,
     * not a kind of machine, so it is a bare interface they also implement and every `when` over
     * kinds is untouched.
     */
    val center: TileIndex
    /**
     * Every species that may be sent here **pure**. Empty means none, not any.
     *
     * A set rather than a signed book like [DockingPort.orders], because there is one direction here
     * and no quantity. ⚠️ **Says nothing about blends** — see [ore].
     */
    val whitelist: Set<Species>

    /**
     * Whether mixed ore may be sent — the same switch for the same reason the counter has one.
     *
     * ⛔ **It cannot be a species**, which is the whole point of it being a field of its own: a blend
     * has no single species to key on, so there is nowhere on [whitelist] to put "any rock".
     */
    val ore: Boolean

    /**
     * This machine with a new book. Implementations `copy`; nothing else about them changes.
     *
     * ⚠️ **Returns a [FeedBook] and not a [DeckMachine]**, so that presses chain — `switched(a)
     * .switched(b)` is what a panel does and what a test reads. The one caller that needs to put the
     * result back on the deck casts there, which is one cast against a cast at every other site.
     */
    fun withFeed(whitelist: Set<Species>, ore: Boolean): FeedBook

    /** Whether pure [species] is on the list — one press of the switch away from either answer. */
    fun takes(species: Species): Boolean = species in whitelist

    /** True when this machine will be sent nothing at all: no species named, and no ore. */
    val isShut: Boolean get() = whitelist.isEmpty() && !ore

    /**
     * This machine with [species] on the stated side of its switch.
     *
     * ⛔ **Set, not flip.** The panel's two buttons each name a side, so pressing the lit one has to
     * come to nothing — a `toggled` here would turn a double tap into a silent reversal, and the
     * whole reason the control is a pair is to make that impossible.
     */
    fun switched(species: Species, taking: Boolean): FeedBook =
        withFeed(if (taking) whitelist + species else whitelist - species, ore)

    /** The same press on the ORE row, which has no species to key on. */
    fun switchedOre(taking: Boolean): FeedBook = withFeed(whitelist, taking)
}
