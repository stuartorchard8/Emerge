package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * **A rod through the deck, and the only machine aboard whose whole job is to touch things.**
 *
 * Increment 3c of `PLAN_power_network.md`, and decision 4: terminals come in two forms — built into
 * a machine at its stated ends, which is how a panel and a cell declare theirs, and *standalone*,
 * which is this. It holds nothing, runs nothing and produces nothing. What it does is bond every
 * layer standing on its tile, so charge may cross between them there.
 *
 * ### ⭐ Why this is the machine that makes wiring a decision
 *
 * Charge crosses layers **only** where a terminal stands (`PLAN_power_network.md` §3) — that is the
 * single place the electrical contact graph differs from the thermal one. Without a standalone
 * terminal the player can only bond where a machine happens to stand, so the layers a run reaches
 * are decided by what they built rather than by what they wired. With one, *"the player picks their
 * conductor — wire, rail, or the building itself"* stops being a property of the model and becomes
 * something they do with a click.
 *
 * So this is the counterpart to the crossing: a rail over a power run is two circuits, and a
 * terminal on that tile is one. Both gestures are now the player's.
 *
 * ### ⚠️ It has no end-ness, and that is why [org.emerge.demo.outofspace.world.TerminalRole.Bond]
 * exists
 *
 * A device terminal is one of a pair — the whole of §5 is that a machine's casing is a parallel path
 * *between* its two ends — and a bonding point is not one of anything. Naming it `Positive` would
 * make it answer a question it has no answer to, and the first thing to read `terminalRolesOf` would
 * believe it.
 *
 * ### ⚠️ Its material does not gate the bond
 *
 * The rod is *"assumed not to be the bottleneck"* — the join it makes is weighted by the worse of
 * the two things it bonds, exactly as every other edge is — so a terminal built out of rock still
 * bonds. That is the same rule a machine-borne terminal follows and it has to be: a silicon panel's
 * casing conducts nothing at all, and its terminals are what drive the whole network. See
 * `PLAN_power_network.md` §3 and the rod loop in `Circuit.kt`.
 *
 * Permeable, like every other fitting-sized thing: a rod standing in a corridor is still a corridor,
 * and one that displaced the air out of its own tile would seal a room by being screwed to the deck.
 */
data class Terminal(
    override val center: TileIndex,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Terminal
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
