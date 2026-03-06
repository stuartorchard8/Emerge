package org.emerge.render.torus.shader

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Frac2

data class WorldShaderParams(
    val worldSize: Vec2,
    val zoom: Float,
    val viewFocus: Vec2,
    val viewRotationRad: Float,
    val myId: PlayerId?,
    val bodies: List<CircleBody>,
) {
    companion object {
        fun compute(state: PhysicsState, myId: PlayerId?, zoom: Float, viewRotationRad: Float = 0f): WorldShaderParams {
            val focusWrapped: Frac2 =
                if (myId != null) state.bodies[myId]?.pos ?: Frac2(0, 0)
                else Frac2(0, 0)

            val focus = Vec2(
                focusWrapped.x.toFloat()/Int.MAX_VALUE,
                focusWrapped.y.toFloat()/Int.MAX_VALUE,
            )

            return WorldShaderParams(
                worldSize = Vec2(2f,2f),
                // zoom < 1 => zoom out
                1f/zoom,
                viewFocus = focus,
                viewRotationRad = viewRotationRad,
                myId,
                bodies = state.bodies.values.toList(),
            )
        }
    }
}

