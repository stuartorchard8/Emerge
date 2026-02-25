package org.emerge.sim.core.physics

import kotlin.math.*

data class Vec2i(val x: Int, val y: Int) {
    operator fun plus(o: Vec2i): Vec2i = Vec2i(x + o.x, y + o.y)
    operator fun minus(o: Vec2i): Vec2i = Vec2i(x - o.x, y - o.y)
    operator fun times(s: Int): Vec2i = Vec2i(x * s, y * s)
    operator fun div(s: Int): Vec2i = Vec2i(x / s, y / s)
    fun dot(other: Vec2): Float = x*other.x + y*other.y
    fun distSq(): Long = x.toLong() * x.toLong() + y.toLong() * y.toLong()
    fun octDist(): Int = ((12L*(abs(x) + abs(y))+17L*max(abs(x), abs(y)))/29L).toInt()
    fun octNorm(): Vec2 = Vec2(
        x.toFloat(),
        y.toFloat(),
    )/octDist().toFloat()

    fun estNorm(): Vec2 {
        val distSq = distSq()
        if (distSq == 0L) return Vec2(0f, 0f)
        val neg = 1-distSq
        val flower = neg/2/distSq
        val oct = octNorm()*(1f+flower)
        return oct
    }
}
