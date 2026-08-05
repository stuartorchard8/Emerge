package org.emerge.demo.outofspace.world.fluid

import org.emerge.sim.core.physics.primitives.Frac

/**
 * Fluid motion: stored on faces as momentum (gram·tiles/tick).
 * Momentum stored, velocity derived (v = p/m) — same pattern as HeatField (stores joules, derives kelvin).
 * Conserved quantity gets home in state (structural conservation). Long range avoids Frac's ±2 limit.
 * Units: momentum/grams = tiles/tick (velocity).
 */
class MomentumField(
    private val edges: EdgeGrid,
    private val x: LongArray,
    private val y: LongArray,
) {

    fun xAt(edge: Int): Long = x[edge]
    fun yAt(edge: Int): Long = y[edge]

    fun copyX(): LongArray = x.copyOf()
    fun copyY(): LongArray = y.copyOf()

    /** Total momentum aboard (one axis). Internal forces sum to zero; sealed vessel totals may slosh but not drift. */
    val totalX: Long get() {
        var sum = 0L
        for (m in x) sum += m
        return sum
    }

    val totalY: Long get() {
        var sum = 0L
        for (m in y) sum += m
        return sum
    }

    /** Velocity across x-edge (tiles/tick). Face mass = mean of separating tiles (boundary face averages only real tile, not vacuum). */
    fun velocityX(edge: Int, tileGrams: LongArray): Frac =
        velocity(x[edge], xFaceGrams(edges, tileGrams, edge))

    /** Velocity across a y-edge in tiles per tick. Positive is downward — see [EdgeGrid]. */
    fun velocityY(edge: Int, tileGrams: LongArray): Frac =
        velocity(y[edge], yFaceGrams(edges, tileGrams, edge))

    /** CFL safety: all faces < 1 tile/tick (explicit scheme stability). Hit wall → sub-step. */
    fun isCflSafe(tileGrams: LongArray): Boolean {
        for (e in 0 until edges.xEdgeCount) {
            if (abs(velocityX(e, tileGrams).raw) >= SPEED_LIMIT_RAW) return false
        }
        for (e in 0 until edges.yEdgeCount) {
            if (abs(velocityY(e, tileGrams).raw) >= SPEED_LIMIT_RAW) return false
        }
        return true
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is MomentumField && edges == other.edges &&
                x.contentEquals(other.x) && y.contentEquals(other.y))

    override fun hashCode(): Int = 31 * x.contentHashCode() + y.contentHashCode()

    companion object {
        /** One tile per tick, as a [Frac] raw value: the CFL limit, and `Frac`'s natural unit. */
        const val SPEED_LIMIT_RAW: Long = Int.MAX_VALUE.toLong()

        /** Still air. */
        fun still(edges: EdgeGrid): MomentumField =
            MomentumField(edges, LongArray(edges.xEdgeCount), LongArray(edges.yEdgeCount))

        /** Builds from raw per-face momenta. The arrays are copied. */
        fun of(edges: EdgeGrid, x: LongArray, y: LongArray): MomentumField =
            MomentumField(edges, x.copyOf(), y.copyOf())

        private fun velocity(momentum: Long, grams: Long): Frac =
            if (grams <= 0L) Frac(0L) else Frac(momentum * Int.MAX_VALUE.toLong() / grams)

        private fun abs(v: Long): Long = if (v < 0L) -v else v
    }
}
