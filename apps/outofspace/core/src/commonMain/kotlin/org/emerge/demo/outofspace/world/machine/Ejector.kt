package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * An ejector: throws named material overboard. Somewhere for slag to go that is not "jam the line".
 *
 * A deck machine, because it takes a tile away from anything else that wants one — which is the
 * whole of what makes something a deck machine. Its casing is matter in [DeckArray.stuff] like every
 * other, so an ejector has a temperature made of the metal it is built from rather than of a
 * constant.
 *
 * ⚠️ **It was called a VENT**, and files written under that name still say so — see
 * `Save.canonicalKindName`. The old name described the hole; this one describes what the machine
 * does with it, which matters now that the machine has an opinion about what goes through.
 *
 * ### The whitelist is the whole of its appetite
 *
 * ⛔ **Nothing is thrown away that the player has not named.** An ejector's [whitelist] becomes an
 * [org.emerge.demo.outofspace.world.Acceptance] at its own tile, so the network only ever *routes*
 * here what is on the list — nothing travels toward a place that cannot use it. It is the docking
 * port's sell list by another name and for the same reason: both destroy the ship's cargo from the
 * player's point of view, and neither may do it on the player's behalf.
 *
 * ⛔ **An EMPTY list refuses everything; it does not mean "no opinion".** That is the one thing this
 * machine cannot be allowed to get wrong. A tile that states no acceptance at all takes anything for
 * ever — see `sinkAdmits`, where "nothing stated means anything" is written down — so a fresh
 * ejector has to state its emptiness out loud or it would be the old vent wearing a list. Place one
 * and the belts back up behind it until a row is ticked, which is correct and not a failure to
 * explain away.
 *
 * ### Pure and mixed are two categories, and the counter drew the line first
 *
 * ⛔ **A species on the [whitelist] means that species PURE**, and a blend of anything is [ore] —
 * one switch of its own. This is `DockingPort`'s partition, taken whole and for its reasons: a tile
 * holding one species is deliverable *as* that species and a tile holding two is deliverable only as
 * ore, so that is the only line the network can honour. Ticking IRON therefore does not consent to
 * throwing away rock that happens to have iron in it — which is what a set-membership reading of a
 * blend would have meant, and what would have quietly destroyed the metal along with the gangue.
 *
 * ⚠️ **The two never compete for the same lump**, because pure and mixed are complementary — see
 * [org.emerge.demo.outofspace.world.SpeciesFilter.MIXED], where that argument is written down for
 * the mouth that needed it first.
 */
data class Ejector(
    override val center: TileIndex,
    val ventedMass: Long = 0L,
    /**
     * Every species this ejector may throw overboard **pure**. Empty means none, not any.
     *
     * A set rather than a signed book like [DockingPort.orders], because there is one direction here
     * and no quantity: matter goes out, and it goes out for ever. What the port needs a number for
     * — how much of an unbounded permission is left — an ejector has no use for.
     *
     * ⚠️ **Says nothing about blends.** See [ore], and the class note above.
     */
    val whitelist: Set<Species> = emptySet(),
    /**
     * Whether mixed ore may go overboard — the same switch for the same reason the counter has one.
     *
     * ⛔ **It cannot be a species**, which is the whole point of it being a field: a blend has no
     * single species to key on, so there is nowhere on [whitelist] to put "any rock". It is also the
     * switch a player actually reaches for — tailings are the thing an ejector exists to be rid of,
     * and every one of them is a blend.
     */
    val ore: Boolean = false,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Ejector
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /** Whether pure [species] is on the list — one press of the switch away from either answer. */
    fun ejects(species: Species): Boolean = species in whitelist

    /** True when this ejector will take nothing at all: no species named, and no ore. */
    val isShut: Boolean get() = whitelist.isEmpty() && !ore

    /**
     * This ejector with [species] on the stated side of its switch.
     *
     * ⛔ **Set, not flip.** The panel's two buttons each name a side, so pressing the lit one has to
     * come to nothing — a `toggled` here would turn a double tap on EJECT into a silent reversal,
     * and the whole reason the control is a pair is to make that impossible.
     */
    fun switched(species: Species, ejecting: Boolean): Ejector =
        copy(whitelist = if (ejecting) whitelist + species else whitelist - species)

    /** The same press on the ORE row, which has no species to key on. */
    fun switchedOre(ejecting: Boolean): Ejector = copy(ore = ejecting)
}
