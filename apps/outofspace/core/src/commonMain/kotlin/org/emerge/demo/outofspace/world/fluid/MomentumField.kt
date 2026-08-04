package org.emerge.demo.outofspace.world.fluid

import org.emerge.sim.core.physics.primitives.Frac

/**
 * The fluid's motion, stored on faces as **momentum** in gram·tiles per tick.
 *
 * ### Why momentum and not velocity
 *
 * Every other Eulerian fluid sim, Lague's included, stores velocity and advects it semi-Lagrangian
 * — trace backwards, sample bilinearly. It is stable, cheap, and conserves nothing. For smoke that
 * costs nothing anybody can see. Here it takes out the goal the whole thing exists for: a vessel
 * propelled by its own exhaust. Thrust is the momentum leaving through the boundary, and if the
 * advection scheme quietly creates and destroys momentum in the interior, then thrust is a number
 * produced by the discretisation rather than by the rocket. No amount of tuning fixes that, because
 * there is nothing underneath to tune toward.
 *
 * So momentum is the stored quantity and **velocity is derived**, which is the same inversion
 * [org.emerge.demo.outofspace.world.HeatField] already makes by storing joules and deriving kelvin,
 * and for the same reason: the conserved thing is the one that gets a home in state, so that
 * conservation is structural rather than something to test for afterwards.
 *
 * Integers, per [org.emerge.demo.outofspace.chem.Mixture]. This also disposes of a range problem:
 * `Frac` tops out near ±2, which a fast exhaust would overrun, whereas a `Long` of gram·tiles has
 * room to spare. `Frac` appears only at the moment a velocity is actually wanted, by which point
 * the CFL condition already requires it to be under a tile per tick.
 *
 * The units are worth saying out loud, because they are what make the ledger check out: momentum on
 * a face is `grams × tiles / tick`. Divided by the grams sitting on that face it gives tiles per
 * tick, which is a velocity in the only units this grid has.
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

    /**
     * Total momentum aboard, one axis at a time.
     *
     * This is half of the ledger the rocket rests on. Internal forces — pressure between two tiles,
     * a face pushing back on the fluid touching it — are equal and opposite and must sum to nothing
     * at all. So a sealed vessel's totals may slosh but must not drift, and any drift is momentum
     * appearing from nowhere, which is indistinguishable from free energy. The other half is what
     * crosses the boundary, which is increment D.
     */
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

    /**
     * Velocity across an x-edge in tiles per tick, or zero where there is no fluid to be moving.
     *
     * [tileGrams] is mass per *tile*; the mass on a face is the mean of the tiles it separates, and
     * a boundary face averages only the tile it actually has rather than treating the vacuum beyond
     * as a real half-cell of nothing — which would read every escaping draught at double speed.
     */
    fun velocityX(edge: Int, tileGrams: LongArray): Frac =
        velocity(x[edge], faceGrams(tileGrams, edges.xEdgeBefore(edge), edges.xEdgeAfter(edge)))

    /** Velocity across a y-edge in tiles per tick. Positive is downward — see [EdgeGrid]. */
    fun velocityY(edge: Int, tileGrams: LongArray): Frac =
        velocity(y[edge], faceGrams(tileGrams, edges.yEdgeBefore(edge), edges.yEdgeAfter(edge)))

    /**
     * Whether every face is moving slower than a tile per tick.
     *
     * The explicit scheme is only stable while that holds — fluid must not skip over a tile in one
     * step, or it is jumping past cells it should have interacted with. It is the known wall on the
     * way to a genuinely fast exhaust, and the answer when it is hit is sub-stepping. Exposed so the
     * wall can be *observed* being approached rather than inferred from the sim going strange.
     */
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

        private fun faceGrams(tileGrams: LongArray, before: Int, after: Int): Long {
            var sum = 0L
            var count = 0
            if (before >= 0) { sum += tileGrams[before]; count++ }
            if (after >= 0) { sum += tileGrams[after]; count++ }
            return if (count == 0) 0L else sum / count
        }

        private fun velocity(momentum: Long, grams: Long): Frac =
            if (grams <= 0L) Frac(0L) else Frac(momentum * Int.MAX_VALUE.toLong() / grams)

        private fun abs(v: Long): Long = if (v < 0L) -v else v
    }
}
