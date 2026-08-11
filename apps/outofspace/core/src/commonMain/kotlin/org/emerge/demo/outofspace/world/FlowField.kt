package org.emerge.demo.outofspace.world

import kotlin.math.sqrt

/**
 * Tile-centre velocities (tiles/tick). Derived from face momenta (averaged opposing pairs).
 * Not stored: lossy reconstruction from state (avoiding two sources that could disagree).
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
         * Reconstruct tile-centre velocities from face momenta.
         * Face velocity = momentum / face-mass (not tile-mass — avoids reading empty-face draughts as fastest).
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
