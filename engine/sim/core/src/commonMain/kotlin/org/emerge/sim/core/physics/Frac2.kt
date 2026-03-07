package org.emerge.sim.core.physics

import kotlin.math.*

data class Frac2(val x: Int, val y: Int) {
    operator fun plus(o: Frac2): Frac2 = Frac2(x + o.x, y + o.y)
    operator fun minus(o: Frac2): Frac2 = Frac2(x - o.x, y - o.y)
    fun dot(other: Norm): Int = ((
        x.toLong()*other.x.toLong() +
        y.toLong()*other.y.toLong()
    )/Int.MAX_VALUE.toLong()).toInt()
    val distSq by lazy { x.toLong() * x.toLong() + y.toLong() * y.toLong() }
    val len by lazy { longISqrt(distSq).toInt() }
    val norm by lazy { Norm(
        (x.toLong()*Int.MAX_VALUE.toLong()/len.toLong()).toInt(),
        (y.toLong()*Int.MAX_VALUE.toLong()/len.toLong()).toInt()
    ) }
    fun octDist(): Int = ((12L*(abs(x) + abs(y))+17L*max(abs(x), abs(y)))/29L).toInt()
    fun octNorm(): Vec2 = Vec2(
        x.toFloat(),
        y.toFloat(),
    )/octDist().toFloat()

    fun estNorm(): Vec2 {
        val distSq = distSq
        if (distSq == 0L) return Vec2(0f, 0f)
        val neg = 1-distSq
        val flower = neg/2/distSq
        val oct = octNorm()*(1f+flower)
        return oct
    }

    operator fun compareTo(o: Int): Int = (distSq - o.toLong()*o.toLong()).sign
    operator fun compareTo(o: Frac2): Int = (distSq - o.distSq).sign

    companion object {
        private fun longISqrt(n: Long): Long {
            if (n < 2) return n
            var low = 1L
            var high = n / 2
            var result = 1L
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
    }
}

data class Norm(val x: Int, val y: Int) {
    operator fun times(s: Int): Frac2 = Frac2(
        ((x.toLong() * s.toLong())/Int.MAX_VALUE.toLong()).toInt(),
        ((y.toLong() * s.toLong())/Int.MAX_VALUE.toLong()).toInt(),
    )
    val perp by lazy { Norm(y, -x) }

    companion object {
        fun fromAngle(angle: Frac): Norm {
            val rad: Float = (angle.raw.toFloat() / UInt.MAX_VALUE.toFloat()) * 2f * PI.toFloat()
            return Norm(
                (cos(rad)*Int.MAX_VALUE.toFloat()).roundToInt(),
                (sin(rad)*Int.MAX_VALUE.toFloat()).roundToInt(),
            )
        }
    }
}
