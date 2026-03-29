package org.emerge.sim.core.physics.primitives

import org.emerge.sim.core.physics.primitives.Frac.Companion.abs
import kotlin.math.abs
import kotlin.math.max

data class Frac2(val x: Frac, val y: Frac) {
    operator fun plus(o: Frac2): Frac2 = Frac2(x + o.x, y + o.y)
    operator fun minus(o: Frac2): Frac2 = Frac2(x - o.x, y - o.y)
    operator fun times(o: Frac): Frac2 = Frac2(x * o, y * o)
    operator fun div(o: Frac): Frac2 = Frac2(x / o, y / o)
    operator fun div(o: Int): Frac2 = Frac2(x / o, y / o)
    operator fun unaryMinus(): Frac2 = Frac2(-x, -y)
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
    val lenSq by lazy { x*x + y*y }
    val len by lazy {
        if (x.raw == 0L) abs(y)
        else if (y.raw == 0L) abs(x)
        else fracSqrt(lenSq, lenMax.toLong())
    }
    private var lenMax = abs(x) + abs(y)
    fun capMax(v: Frac) { lenMax.coerceAtLeast(v) }
    val norm by lazy { Norm(
        x/len,
        y/len,
    ) }


    // Potentially faster, but less accurate
    fun octDist() = Frac((12L*(abs(x.toLong()) + abs(y.toLong()))+17L*max(abs(x.toLong()), abs(y.toLong())))/29L)
    fun octNorm(): Frac2 = this/octDist()
    val estNorm by lazy {
        val distSq = lenSq.raw
        if (distSq == 0L) zero
        val neg = 1-distSq
        val flower = neg/2/distSq
        val oct = octNorm()*Frac(1+flower)
        Norm(oct.x, oct.y)
    }
    val estDist by lazy {
        dot(estNorm)
    }

    operator fun compareTo(o: Frac): Int = (lenSq - o*o).sign
    operator fun compareTo(o: Frac2): Int = (lenSq - o.lenSq).sign

    companion object {
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

        val zero get() = Frac2(
            Frac(0),
            Frac(0),
        )

        fun raw(x: Int, y: Int) = Frac2(Frac(x.toLong()), Frac(y.toLong()))
    }
}
