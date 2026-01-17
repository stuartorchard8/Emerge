package org.emerge.sim.core.physics

data class Vec2Fx(val x: Fx, val y: Fx) {
    operator fun plus(o: Vec2Fx): Vec2Fx = Vec2Fx(x + o.x, y + o.y)
    operator fun minus(o: Vec2Fx): Vec2Fx = Vec2Fx(x - o.x, y - o.y)
    operator fun times(s: Fx): Vec2Fx = Vec2Fx(x * s, y * s)
}

fun vfx(x: Int, y: Int): Vec2Fx = Vec2Fx(fxInt(x), fxInt(y))

