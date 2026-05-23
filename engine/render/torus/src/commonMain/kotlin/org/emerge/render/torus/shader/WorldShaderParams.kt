package org.emerge.render.torus.shader

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.Vec2

data class WorldShaderParams(
    val worldSize: Vec2,
    val zoom: Float,
    val viewFocus: Vec2,
    val viewRotationRad: Float,
    val myId: PlayerId?,
) {
    companion object {
        fun compute(focus: Vec2, myId: PlayerId?, zoom: Float, viewRotationRad: Float = 0f): WorldShaderParams =
            WorldShaderParams(
                worldSize = Vec2(2f, 2f),
                // zoom < 1 => zoom out
                zoom = 1f / zoom,
                viewFocus = focus,
                viewRotationRad = viewRotationRad,
                myId = myId,
            )
    }
}
