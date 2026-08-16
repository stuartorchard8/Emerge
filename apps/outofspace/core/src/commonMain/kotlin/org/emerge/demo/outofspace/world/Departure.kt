package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.logistics.Packet

/**
 * A packet that was somewhere at the start of the tick and is nowhere at the end of it.
 *
 * Kept whole rather than reduced to a position, because the shrinking ghost is drawn from the real
 * thing — its colour is its dominant species and its size is its mass, exactly as it was a moment
 * ago. A departure holds the *last* state of a packet the world has otherwise forgotten.
 */
data class Departure(
    /** The tile it was standing on, which is where the ghost of it shrinks away. */
    val tile: TileIndex,
    val packet: Packet,
)
