package org.emerge.sim.core.physics

import kotlin.math.*

data class Frac2(val x: Frac, val y: Frac) {
    operator fun plus(o: Frac2): Frac2 = Frac2(x + o.x, y + o.y)
    operator fun minus(o: Frac2): Frac2 = Frac2(x - o.x, y - o.y)
    fun dot(other: Norm): Frac = (
        x*other.x +
        y*other.y
    )
    val lenSq by lazy { x*x + y*y }
    val len by lazy {
        if (x.raw == 0) abs(y)
        else if (y.raw == 0) abs(x)
        else fracSqrt(lenSq, lenMax.toLong())
    }
    private var lenMax = abs(x)+abs(y)
    fun capMax(v: Frac) { lenMax.coerceAtLeast(v) }
    val norm by lazy { Norm(
        x/len,
        y/len,
    ) }

    operator fun compareTo(o: Frac): Int = (lenSq - o*o).sign
    operator fun compareTo(o: Frac2): Int = (lenSq - o.lenSq).sign

    companion object {
        private fun longISqrt(n: Long, min: Long = 2L, max: Long = Int.MAX_VALUE.toLong()): Long {
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
            val n = f.toLong()*Int.MAX_VALUE.toLong()
            val x = longISqrt(n, sqrtMaxInt, max)

            return Frac(x.toInt())
        }

        val zero get() = Frac2(
            Frac(0),
            Frac(0),
        )

        fun raw(x: Int, y: Int) = Frac2(Frac(x), Frac(y))
    }
}

data class Norm(val x: Frac, val y: Frac) {
    operator fun times(s: Frac): Frac2 = Frac2(
        x*s,
        y*s,
    )
    val perp by lazy { Norm(y, -x) }

    companion object {
        fun fromAngle(angle: Frac): Norm {
            val rad: Float = (angle.raw.toFloat() / Int.MAX_VALUE.toFloat()) * 2f * PI.toFloat()
            return Norm(
                Frac((cos(rad)*Int.MAX_VALUE.toFloat()).roundToInt()),
                Frac((sin(rad)*Int.MAX_VALUE.toFloat()).roundToInt()),
            )
        }
    }
}
