package org.emerge.demo.outofspace

/**
 * Which frame the camera holds still — the grid the ship is built on, or the world it moves through.
 *
 * The ship has an orientation as of step 2 of `PLAN_trig_free_rotation.md`, and the moment it does,
 * "which way is up on screen" stops having one answer. Both are wanted, for opposite reasons:
 *
 * - [Grid] is the frame you *build* in. A blueprint that tilted while you were laying pipe would be
 *   unusable, and every editing gesture — drag a run, place a facing, read a footprint — is defined
 *   in grid axes. Holding the grid still costs the player nothing, because a ship under construction
 *   is not going anywhere.
 * - [World] is the frame you *fly* in. A burn that turns the ship should look like the ship turning,
 *   not like the universe swinging round it, and the ship's heading relative to where it is going is
 *   the only thing a pilot is actually reading.
 *
 * There is no third "north-up" option and no free-look. The two frames here are the two the game has
 * a use for; see [Mode.camera] for why the player never picks one directly.
 */
enum class CameraFrame {
    /** Grid axes on screen axes. The ship's orientation is not shown at all. */
    Grid,

    /** The world held still, so the ship turns on screen by its own [world.VesselState.ang]. */
    World,
}
