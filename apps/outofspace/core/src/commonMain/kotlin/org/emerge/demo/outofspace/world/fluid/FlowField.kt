package org.emerge.demo.outofspace.world.fluid

import kotlin.math.sqrt

/**
 * The air's velocity **at tile centres**, in tiles per tick — the staggered field collapsed back
 * into something that can be drawn and read.
 *
 * Nothing in the simulation uses this. Momentum lives on faces because that is the only layout in
 * which pressure couples to its actual neighbours (see [EdgeGrid]), and every pass is written against
 * that. But a face is a boundary rather than a place, and "which way is the air moving *here*" is a
 * question about a place. Asked of the raw arrays it has no answer: a tile has four faces and any one
 * of them on its own is half the story.
 *
 * So this averages each opposing pair. That is the standard reconstruction, and it is worth naming
 * what it costs: a tile whose left face flows in at the same rate its right face flows out reads as
 * *still*, because on the whole it is — the air is passing through rather than moving. Divergence,
 * which is the other thing that pair of faces knows, is deliberately not recovered here. The pressure
 * solve is what watches divergence, and a flow picture that also tried to show it would show neither.
 *
 * ### Why this is derived and not stored
 *
 * The same reason [MomentumField] stores momentum rather than velocity. This is a lossy view of the
 * state, built on demand from the state; keeping a copy would mean two things that could disagree,
 * and the one that gets drawn would be the one nobody checks.
 */
class FlowField(
    private val x: LongArray,
    private val y: LongArray,
) {

    /** Velocity along +x, as a raw fixed-point value where [MomentumField.SPEED_LIMIT_RAW] is one tile per tick. */
    fun xAt(tile: Int): Long = x[tile]

    /** Velocity along +y — **downward**, since the world is side-on and screen-down is gravity-down. */
    fun yAt(tile: Int): Long = y[tile]

    /** Speed in tiles per tick, as a float, for anything that only wants "how fast". */
    fun speedAt(tile: Int): Float {
        val fx = x[tile].toDouble()
        val fy = y[tile].toDouble()
        return (sqrt(fx * fx + fy * fy) / MomentumField.SPEED_LIMIT_RAW).toFloat()
    }

    /** The fastest tile in the field, in tiles per tick. Zero for still air. */
    fun peakSpeed(): Float {
        var best = 0f
        for (tile in x.indices) {
            val s = speedAt(tile)
            if (s > best) best = s
        }
        return best
    }

    companion object {

        /**
         * Reconstructs tile-centre velocities from the face momenta.
         *
         * Each face's velocity is momentum over the mass on that face, exactly as the sim computes
         * it — not momentum over the tile's mass, which would read a face between a full tile and an
         * empty one at the wrong speed and make every draught look fastest where it is thinnest.
         */
        fun derive(edges: EdgeGrid, momentum: MomentumField, tileGrams: LongArray): FlowField {
            val size = edges.grid.size
            val x = LongArray(size)
            val y = LongArray(size)
            for (tile in 0 until size) {
                x[tile] = (momentum.velocityX(edges.leftEdgeOf(tile), tileGrams).raw +
                    momentum.velocityX(edges.rightEdgeOf(tile), tileGrams).raw) / 2L
                y[tile] = (momentum.velocityY(edges.upEdgeOf(tile), tileGrams).raw +
                    momentum.velocityY(edges.downEdgeOf(tile), tileGrams).raw) / 2L
            }
            return FlowField(x, y)
        }

        fun still(size: Int): FlowField = FlowField(LongArray(size), LongArray(size))
    }
}
