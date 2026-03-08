package org.emerge.render.torus

import org.emerge.sim.core.physics.Body

/**
 * Packs bodies into an array usable by GPU shaders:
 * - 4 floats per body: x, y, radius, playerId
 * - output is always exactly 4*maxBodies floats; unused entries are zeroed
 */
fun packBodiesToFloatArray(
    bodies: List<Body>,
    maxBodies: Int,
    out: FloatArray,
) {
    require(out.size >= 4 * maxBodies) { "out must be at least 4*maxBodies floats" }
    val n = minOf(maxBodies, bodies.size)
    for (i in 0 until maxBodies) {
        val base = i * 4
        if (i < n) {
            val b = bodies[i]
            // Scale everything by the world size
            out[base + 0] = b.pos.x.toFloat()/Int.MAX_VALUE
            out[base + 1] = b.pos.y.toFloat()/Int.MAX_VALUE
            out[base + 2] = b.radius.toFloat()/Int.MAX_VALUE
            out[base + 3] = b.playerId.value.toFloat()
        } else {
            out[base + 0] = 0f
            out[base + 1] = 0f
            out[base + 2] = 0f
            out[base + 3] = -1f
        }
    }
}
