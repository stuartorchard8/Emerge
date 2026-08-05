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

    /** Drop rock at [index] (centred on tile). Mints mass → books to capturedGrams. Not via extractedGrams (extractor replacement). */
    data class DropRock(val index: Int, val radius: Int = DEFAULT_ROCK_RADIUS) : Edit

    companion object {
        /** Thrust magnitude: 250 milli-g (quarter gravity). Settles rooms over tens of ticks. */
        const val DEBUG_THRUST_MILLI_G: Long = 250L

        /** Rock radius: 2 cells (5 tiles across, 21 cells). Fits through doorways. */
        const val DEFAULT_ROCK_RADIUS: Int = 2
    }
}
