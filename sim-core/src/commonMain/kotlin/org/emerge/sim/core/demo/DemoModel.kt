package org.emerge.sim.core.demo

import org.emerge.sim.core.PlayerId

data class Vec2i(val x: Int, val y: Int) {
    operator fun plus(other: Vec2i): Vec2i = Vec2i(x + other.x, y + other.y)
}

data class DemoInput(val move: Vec2i)

data class DemoState(
    val positions: Map<PlayerId, Vec2i>,
)

