package org.emerge.demo.physics

import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsState

/**
 * Packs bodies into an array usable by GPU shaders:
 * - 4 floats per body: x, y, radius, playerId
 * - output is always exactly 4*maxBodies floats; unused entries are zeroed
 */
fun packBodiesToFloatArray(
    state: PhysicsState,
    maxBodies: Int,
    out: FloatArray,
) {
    require(out.size >= 4 * maxBodies) { "out must be at least 4*maxBodies floats" }
    val bodies = state.bodies.values.toList()
    val n = minOf(maxBodies, bodies.size)
    val scale = Fx.SCALE.toFloat()
    for (i in 0 until maxBodies) {
        val base = i * 4
        if (i < n) {
            val b = bodies[i]
            out[base + 0] = b.pos.x.raw.toFloat() / scale
            out[base + 1] = b.pos.y.raw.toFloat() / scale
            out[base + 2] = b.radius.raw.toFloat() / scale
            out[base + 3] = b.playerId.value.toFloat()
        } else {
            out[base + 0] = 0f
            out[base + 1] = 0f
            out[base + 2] = 0f
            out[base + 3] = -1f
        }
    }
}

