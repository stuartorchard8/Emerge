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
 * ⛔ **Nothing is thrown away that the player has not named.** An ejector is a [FeedBook] — see that
 * interface for what a book is, why an empty one refuses everything, and why a ticked species means
 * that species *pure*. It is the docking port's sell list by another name and for the same reason:
 * both destroy the ship's cargo from the player's point of view, and neither may do it on the
 * player's behalf.
 *
 * ⚠️ **"Takes" means "destroys" here**, which is the one place this machine's reading of the shared
 * vocabulary differs from a furnace's. Ticking IRON does not consent to throwing away rock that
 * happens to have iron in it — that is the ORE switch, and the partition is the interface's.
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
    override val whitelist: Set<Species> = emptySet(),
    /**
     * Whether mixed ore may go overboard — the same switch for the same reason the counter has one.
     *
     * ⛔ **It cannot be a species**, which is the whole point of it being a field: a blend has no
     * single species to key on, so there is nowhere on [whitelist] to put "any rock". It is also the
     * switch a player actually reaches for — tailings are the thing an ejector exists to be rid of,
     * and every one of them is a blend.
     */
    override val ore: Boolean = false,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine, FeedBook {
    override val kind: DeckMachineKind get() = DeckMachineKind.Ejector
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    override fun withFeed(whitelist: Set<Species>, ore: Boolean): FeedBook =
        copy(whitelist = whitelist, ore = ore)
}
