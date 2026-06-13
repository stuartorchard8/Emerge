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
    fun rotateByAngle(angle: Coord): Frac2 {
        val rotation = Norm.fromAngle(angle)
        return Frac2(
            x = x * rotation.x - y * rotation.y,
            y = x * rotation.y + y * rotation.x,
        )
    }

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

        private fun longISqrt(n: Long, min: Long = 2L, max: Long = 2*Int.MAX_VALUE.toLong()): Long {
            if (n < 2) return n
            var low = min
            var high = max
            var result = min
            while (low <= high) {
                val mid = low + (high - low) / 2
                if (mid <= n / mid) {
                    result = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return result
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
