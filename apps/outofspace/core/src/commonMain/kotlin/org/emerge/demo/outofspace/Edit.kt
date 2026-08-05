package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.Trigger

/** A player action. Actions are values, so they replay, serialise and travel over a wire. */
sealed interface Edit {
    data class Place(val index: Int, val kind: MachineKind, val facing: Direction) : Edit
    data class Rotate(val index: Int) : Edit
    data class Remove(val index: Int) : Edit

    /**
     * Lays conduit from [from] to the **adjacent** tile [to] and joins the two — one step of a drag.
     *
     * Connection is an edit in its own right rather than a consequence of placement, because that is
     * the only way two runs can touch without merging. A drag is a stream of these, so the line the
     * player drew is exactly the graph they get; track placed by a single click joins nothing until
     * something is drawn through it.
     *
     * Missing track at either end is laid, so one gesture both builds and connects. Non-adjacent
     * tiles are ignored rather than pathfound: a drag that skips a tile is a slipped mouse, and
     * quietly inventing the tiles in between is how you end up with runs nobody meant to build.
     */
    data class Lay(val from: Int, val to: Int, val conduit: Conduit = Conduit.Rail) : Edit

    /** Severs the join between two adjacent tiles, leaving both lengths of track in place. */
    data class Cut(val from: Int, val to: Int, val conduit: Conduit = Conduit.Rail) : Edit

    /**
     * Rewires one term of one action. [slot] at or past the end appends; a null [trigger] removes.
     * One edit covers add, change and remove because they are the same operation on a list, and
     * three edit types would be three chances to get replay ordering subtly different.
     */
    data class Wire(val index: Int, val action: Action, val slot: Int, val trigger: Trigger?) : Edit

    /** Retunes a sensor to a different channel. */
    data class SetChannel(val index: Int, val channel: Channel) : Edit

    /**
     * **A stand-in engine**: fires the ship one tick's worth in ([dx], [dy]), each of which is −1, 0
     * or 1. See `docs/out-of-space-plan.md` §5f.
     *
     * A real engine is a nozzle, a high-pressure exhaust and the CFL wall §5b left standing, and
     * building one before the gameplay loop closes means tuning a subsystem that every later change
     * detunes again. This is the loop's placeholder, and the plan's rule for placeholders is that
     * they are allowed to be fake and not allowed to be *silent* — everything it mints is counted in
     * [org.emerge.demo.outofspace.world.VesselState.debugImpulseX], the ledger's fifth store, so
     * `momentumBalance` stays exactly zero and stays worth reading. The miner is the cautionary
     * version of the same idea: fine as a stand-in, and a problem because it minted mass quietly.
     *
     * A **direction** rather than an impulse, because what should feel the same between a bare hull
     * and a laden one is the acceleration, not the push. The reducer multiplies by the ship's own
     * mass, so [DEBUG_THRUST_MILLI_G] is what the pilot actually experiences either way — and a
     * heavy hold is a sluggish ship without anything having to say so.
     */
    data class Thrust(val dx: Int, val dy: Int) : Edit

    /**
     * **A stand-in for capture**: puts a rock in the world at [index], centred on that tile.
     *
     * Increment H4 is where rocks arrive by being flown at, and this is how there is one to look at
     * before then. It obeys the same rule [Thrust] does: it mints mass, so it books what it mints
     * into [org.emerge.demo.outofspace.world.VesselState.capturedGrams], and the rock ledger closes
     * either way. It emphatically does **not** go through
     * [org.emerge.demo.outofspace.world.VesselState.minedGrams] — the miner is the thing the
     * extractor exists to delete, and hanging the replacement off it would weld the two together.
     */
    data class DropRock(val index: Int, val radius: Int = DEFAULT_ROCK_RADIUS) : Edit

    companion object {
        /**
         * What [Thrust] is worth, in thousandths of one tile per tick per tick.
         *
         * A quarter of a g, which is a torch ship rather than a station-keeping nudge.
         *
         * ⚠️ It was twenty milli-g, chosen to be gentle enough that the atmosphere would lean into a
         * burn and slosh rather than being slammed against the stern. That reasoning was sound and
         * the number was worthless: at 0.02 g the settling passes rounded to *nothing at all* — see
         * [org.emerge.demo.outofspace.world.fluid.scaleByGravity] for the measurement — so a burn
         * moved the ship and left everything inside it exactly where it was.
         *
         * With the truncation fixed and the deck plating gone, this is the only gravity a vessel
         * has, so it has to be worth having. A quarter of a g settles a room over tens of ticks,
         * which is the regime the settling rate was tuned for in the first place.
         */
        const val DEBUG_THRUST_MILLI_G: Long = 250L

        /**
         * How big a dropped rock is, in cells of radius — so five across, twenty-one cells filled.
         *
         * Big enough to read as an object rather than a speck at the zoom anyone plays at, and small
         * enough to fit through a doorway, which will matter the moment it stops flying through
         * walls in H2.
         */
        const val DEFAULT_ROCK_RADIUS: Int = 2
    }
}
