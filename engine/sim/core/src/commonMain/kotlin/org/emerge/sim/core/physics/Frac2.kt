package org.emerge.sim.core.physics

import kotlin.math.*

data class Frac2(val x: Int, val y: Int) {
    operator fun plus(o: Frac2): Frac2 = Frac2(x + o.x, y + o.y)
    operator fun minus(o: Frac2): Frac2 = Frac2(x - o.x, y - o.y)
    operator fun times(s: Int): Frac2 = Frac2(x * s, y * s)
    operator fun div(s: Int): Frac2 = Frac2(x / s, y / s)
    operator fun div(s: Float): Vec2 = Vec2(x / s, y / s)
    fun dot(other: Vec2): Float = x*other.x + y*other.y
    val distSq by lazy { x.toLong() * x.toLong() + y.toLong() * y.toLong() }
    val len by lazy { longISqrt(distSq).toInt() }
    val norm by lazy { Vec2(x.toFloat()/len, y.toFloat()/len) }
    val perp by lazy { Vec2(norm.y, -norm.x) }
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

    fun smallerThan(minDist: Int): Boolean = distSq < minDist.toLong()*minDist.toLong()

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
