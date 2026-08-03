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
}
