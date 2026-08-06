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
    /**
     * Takes something off a tile — one layer of it, or a named one, or all of it.
     *
     * [layer] defaults to [DeleteLayer.Top], which is the blind one-layer-per-click behaviour every
     * caller had before the delete tool existed, so nothing that predates it changes meaning.
     */
    data class Remove(val index: Int, val layer: DeleteLayer = DeleteLayer.Top) : Edit

    /** Lay conduit between adjacent tiles [from]→[to] (one drag step). Missing track laid at ends. Non-adjacent = ignored (no pathfind). */
    data class Lay(val from: Int, val to: Int, val conduit: Conduit = Conduit.Rail) : Edit

    /** Severs the join between two adjacent tiles, leaving both lengths of track in place. */
    data class Cut(val from: Int, val to: Int, val conduit: Conduit = Conduit.Rail) : Edit

    /** Wire: rewires action term. slot≥end=append, null trigger=remove. Single edit type (add/change/remove are same list op). */
    data class Wire(val index: Int, val action: Action, val slot: Int, val trigger: Trigger?) : Edit

    /** Retunes a sensor to a different channel. */
    data class SetChannel(val index: Int, val channel: Channel) : Edit

    /** Directional thrust (dx, dy each −1/0/1). Acceleration-based (reducer multiplies by vessel mass). Placeholder engine (ledger: debugImpulseX). */
    data class Thrust(val dx: Int, val dy: Int) : Edit

    /** Drop rock at ([x], [y]) (relative to grid). Mints mass → books to capturedGrams. Not via extractedGrams (extractor replacement). */
    data class DropRock(val x: Float, val y: Float, val radius: Int = DEFAULT_ROCK_RADIUS) : Edit

    /**
     * Puts [INJECT_GRAMS] of room-temperature air into a tile — the debug bellows.
     *
     * **It mints matter, and that is the whole difficulty with it.** Every other way gas enters this
     * world is a transfer from somewhere else, which is why `atmosphere + vented == baseline` can be
     * asserted on every tick and why a leak is one assertion away rather than a mystery. Gas from
     * nowhere makes that identity false forever, and an instrument that reads LEAK whatever happens
     * is an instrument nobody looks at again.
     *
     * So it is booked, exactly as [Thrust] is booked into `debugImpulseX`: the balance becomes
     * `atmosphere + vented − injected == baseline`, which is the old identity precisely whenever
     * nothing has cheated. See [org.emerge.demo.outofspace.world.VesselState.injectedAirGrams].
     *
     * One edit per tick for as long as the button is held — see [OutofspaceController.injectTile] —
     * so the rate is a rate rather than a function of the frame rate.
     */
    data class Inject(val index: Int, val grams: Long = INJECT_GRAMS) : Edit

    /**
     * Takes the grid back to the ship plus its pad. The only edit that can make the grid smaller,
     * so the only one that discards cells — which [org.emerge.demo.outofspace.world.remapped] vents.
     *
     * An edit rather than a controller method so two hosts fit on the same tick.
     */
    data object Fit : Edit

    companion object {
        /** Thrust magnitude: 250 milli-g (quarter gravity). Settles rooms over tens of ticks. */
        const val DEBUG_THRUST_MILLI_G: Long = 250L

        /**
         * What one tick of the injector delivers: a kilogram, which is about what a tile holds at one
         * atmosphere. So a held button fills a room at roughly a tile a tick — fast enough to be a
         * tool and slow enough to watch the front move.
         */
        const val INJECT_GRAMS: Long = 1000L

        /** Rock radius: 2 cells (5 tiles across, 21 cells). Fits through doorways. */
        const val DEFAULT_ROCK_RADIUS: Int = 2
    }
}
