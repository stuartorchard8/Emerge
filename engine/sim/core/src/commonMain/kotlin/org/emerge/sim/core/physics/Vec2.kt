package org.emerge.sim.core.physics

import kotlin.math.*

data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2): Vec2 = Vec2(x + o.x, y + o.y)
    operator fun plus(s: Float): Vec2 = Vec2(x + s, y + s)
    operator fun minus(o: Vec2): Vec2 = Vec2(x - o.x, y - o.y)
    operator fun minus(s: Float): Vec2 = Vec2(x - s, y - s)
    operator fun times(s: Float): Vec2 = Vec2(x * s, y * s)
    operator fun times(s: Int): Frac2 = Frac2((x * s).roundToInt(), (y * s).roundToInt())
    operator fun div(s: Float): Vec2 = Vec2(x / s, y / s)
    operator fun div(o: Vec2): Vec2 = Vec2(x / o.x, y / o.y)
    operator fun div(o: Frac2): Vec2 = Vec2(x / o.x, y / o.y)
    fun distSq(): Float = x * x + y * y
    fun octDist(): Float = (2*(abs(x) + abs(y))+3*max(abs(x), abs(y)))/5
    fun octNorm(): Vec2 = this/octDist()

    fun estNorm(): Vec2 {
        val distSq = distSq()
        if (distSq == 0f) return Vec2(0f, 0f)
        val neg = 1f-distSq
        val flower = neg/2f/distSq
        val oct = octNorm()*(1f+flower)
        return oct
    }
    fun estDist(): Float {
        if (x == 0f) return 0f
        val norm = estNorm().x
        if (norm == 0f) return 0f
        return x / norm
    }
}
