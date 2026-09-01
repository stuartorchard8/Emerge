package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex

/**
 * What the build tool would put down if the player clicked right now, and whether the world would
 * take it — the cursor's own ghost.
 *
 * ### Show, don't tell
 *
 * Everything in this game that is not yet real is drawn as a plan: a run of track the player has
 * just dragged, a machine waiting for its metal. The one thing that was still *told* rather than
 * shown was the click itself. A 5×5 extractor's footprint was invisible until it existed, a
 * thruster's bell was somewhere in front of the cursor, and the answer to "will this fit here" was a
 * click that either worked or silently did nothing at all. Silence is the worst of those: nothing
 * moves, nothing is said, and the player is left to work out whether they misclicked, chose the
 * wrong tool, or hit a rule they have not met yet.
 *
 * So the cursor carries the machine. The footprint is drawn where it would land, turned the way the
 * player has turned it, with its ports on the sides they will actually be on — and it is drawn in
 * one colour when it would go down and another when it would be refused, which turns the whole
 * question into something answered by moving the mouse.
 *
 * ⛔ **[allowed] is not this file's opinion.** It comes from
 * [org.emerge.demo.outofspace.world.canStand], which is the same call the reducer makes before it
 * places anything. A preview that reasoned separately would eventually promise a placement the
 * reducer refuses, and a promise the game breaks is worse than no promise.
 */
data class BuildPlan(
    val tile: TileIndex,
    val brush: Brush,
    /** Which way it would face — the brush's, not the tile's. Only some kinds read it. */
    val facing: Direction,
    /**
     * Whether the click would actually put something there.
     *
     * A single flag rather than a reason, because the cursor has nowhere to say a reason and the
     * player does not need one: the picture already shows the footprint over the thing that is in
     * the way, or hanging off the rim, or filling the room that has nowhere to put its air.
     */
    val allowed: Boolean,
)
