package org.emerge.render.torus.shader

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Frac2
import org.emerge.sim.core.physics.PhysicsState

data class WorldShaderParams(
    val worldSize: Vec2,
    val zoom: Float,
    val viewFocus: Vec2,
    val viewRotationRad: Float,
    val myId: PlayerId?,
) {
    companion object {
        fun compute(state: PhysicsState, myId: PlayerId?, zoom: Float, viewRotationRad: Float = 0f): WorldShaderParams {
            val focusWrapped: Frac2 =
                if (myId != null) state.playerTransform(myId)?.pos ?: Frac2.zero
                else Frac2.zero

            val focus = Vec2(
                focusWrapped.x.toFloat(),
                focusWrapped.y.toFloat(),
            )

            return WorldShaderParams(
                worldSize = Vec2(2f,2f),
                // zoom < 1 => zoom out
                1f/zoom,
                viewFocus = focus,
                viewRotationRad = viewRotationRad,
                myId,
            )
        }
    }
}

