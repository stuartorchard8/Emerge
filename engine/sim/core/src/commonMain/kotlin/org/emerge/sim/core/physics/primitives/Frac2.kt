package org.emerge.sim.core.physics.primitives

import org.emerge.sim.core.physics.primitives.Frac.Companion.abs

data class Frac2(val x: Frac, val y: Frac) {
    operator fun plus(o: Frac2?): Frac2 = if (o == null) this else Frac2(x + o.x, y + o.y)
    operator fun minus(o: Frac2?): Frac2 = if (o == null) this else Frac2(x - o.x, y - o.y)
    operator fun times(o: Frac): Frac2 = Frac2(x * o, y * o)
    operator fun times(o: Int): Frac2 = Frac2(x * o, y * o)
    operator fun div(o: Frac): Frac2 = Frac2(x / o, y / o)
    operator fun div(o: Int): Frac2 = Frac2(x / o, y / o)
    operator fun unaryMinus(): Frac2 = Frac2(-x, -y)
    fun wrap(): Coord2 = Coord2(x.wrap(), y.wrap())
    fun dot(other: Norm): Frac = (
        x*other.x +
        y*other.y
    )
    /**
     * Rotated by the orientation [by] is pointing in — the 2D rotation matrix, applied directly.
     *
     * A [Norm] *is* the `(cos θ, sin θ)` pair the matrix is built from:
     *
     * ```
     * [ cos  -sin ]   [ by.x  -by.y ]
     * [ sin   cos ] = [ by.y   by.x ]
     * ```
     *
     * so there is no angle in this at all. Prefer it over [rotateByAngle] whenever the caller
     * already holds a direction, and — more to the point — whenever one orientation is applied to
     * *many* vectors: the direction is recovered once and the loop is four multiplies a vector.
     */
    fun rotateBy(by: Norm): Frac2 = Frac2(
        x = x * by.x - y * by.y,
        y = x * by.y + y * by.x,
    )

    /** [rotateBy], for a caller that holds an angle rather than a direction. */
    fun rotateByAngle(angle: Coord): Frac2 = rotateBy(Norm.fromAngle(angle))

    // len/lenSq/norm are computed on demand rather than cached via `by lazy`: a per-instance
    // lazy delegate (plus its capturing closure) is allocated eagerly in the constructor for
    // every Frac2, accessed or not — and Frac2s are created in tight per-tick physics loops.
    // Recomputing a cheap formula is far cheaper than that allocation; call sites that need
    // both `len` and `norm` should compute `len` once and pass it to [normFromLen].
    val lenSq: Frac get() = x*x + y*y
    val len: Frac get() {
        val ax = if (x.raw < 0L) -x.raw else x.raw
        val ay = if (y.raw < 0L) -y.raw else y.raw
        if (ax == 0L) return Frac(ay)
        if (ay == 0L) return Frac(ax)
        // Exact raw-space hypot: len.raw = √(ax² + ay²). The value-space lenSq path underflows —
        // x*x = ax²/Int.MAX rounds to 0 when |raw| < √Int.MAX ≈ 46341 — which zeroed the length of
        // small OFF-axis vectors (a slowly-drifting cell read as zero speed and slipped past drag,
        // and any sub-46341-raw delta read as coincident). Fall back to the value-space sqrt only for
        // raws so large that ax² + ay² would overflow Long.
        return if (ax <= HYPOT_RAW_MAX && ay <= HYPOT_RAW_MAX) Frac(longISqrt(ax * ax + ay * ay))
        else fracSqrt(lenSq, (abs(x) + abs(y)).toLong())
    }
    val norm: Norm get() = normFromLen(len)

    /** Normalised direction, reusing an already-computed [len] to avoid a second sqrt. */
    fun normFromLen(len: Frac): Norm =
        if (len.raw == 0L) Norm(Frac(1, 1), Frac(0))
        else Norm(x / len, y / len)

    operator fun compareTo(o: Frac): Int = (lenSq - o*o).sign
    operator fun compareTo(o: Frac2): Int = (lenSq - o.lenSq).sign

    companion object {
        /** Max |raw| for the exact raw-space hypot in [len]: above this, ax²+ay² could overflow Long
         *  (2·Int.MAX² ≈ 9.22e18 < Long.MAX), so [len] falls back to the value-space sqrt. */
        private const val HYPOT_RAW_MAX = Int.MAX_VALUE.toLong()

        // Exact floor(√n) clamped to [min, max]. The former bisection over [min, max] was, for n ≥ 2,
        // exactly clamp(floor(√n), min, max) — and for n < 2 it returns n. This computes the same value
        // from a double seed corrected to integer exactness (the true root is < 2^52 for every n in range,
        // so `sqrt(n.toDouble())` is within ±1 and the two correction loops finish in O(1)), trading the
        // ~32 divisions of the bisection for one sqrt + a couple of overflow-safe division checks. The
        // corrected result is the exact integer floor regardless of the double's rounding, so it is
        // deterministic across platforms and bit-identical to the old bisection.
        private fun longISqrt(n: Long, min: Long = 2L, max: Long = 2*Int.MAX_VALUE.toLong()): Long {
            if (n < 2) return n
            var x = kotlin.math.sqrt(n.toDouble()).toLong()
            if (x < 1L) x = 1L
            while (x > n / x) x--                 // descend until x·x ≤ n
            while (x + 1L <= n / (x + 1L)) x++     // ascend until (x+1)·(x+1) > n  → x = floor(√n)
            return if (x < min) min else if (x > max) max else x
        }
        val sqrtMaxInt = longISqrt(Int.MAX_VALUE.toLong())
        private fun fracSqrt(f: Frac, max: Long): Frac {
            val n = f.toLong()
            val x = longISqrt(n, 2L, max)*sqrtMaxInt

            return Frac(x)
        }

        val zero = Frac2(Frac(0), Frac(0))

        fun raw(x: Int, y: Int) = Frac2(Frac(x.toLong()), Frac(y.toLong()))
    }
}
