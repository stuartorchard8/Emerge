package org.emerge.render.torus.shader

import org.emerge.render.torus.ScreenLayout
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2
import org.emerge.sim.core.physics.Vec2i
import kotlin.math.max
import kotlin.math.min
import kotlin.times

data class WorldShaderParams(
    val worldSize: Vec2,
    val zoom: Float,
    val viewFocus: Vec2,
    val myId: PlayerId?,
    val bodies: List<CircleBody>,
) {
    companion object {
        fun compute(state: PhysicsState, myId: PlayerId?, zoom: Float): WorldShaderParams {
            val focusWrapped: Vec2i =
                if (myId != null) state.bodies[myId]?.pos ?: Vec2i(0, 0)
                else Vec2i(0, 0)

            val focus = Vec2(
                focusWrapped.x.toFloat()/Int.MAX_VALUE,
                focusWrapped.y.toFloat()/Int.MAX_VALUE,
            )

            return WorldShaderParams(
                worldSize = Vec2(2f,2f),
                // zoom < 1 => zoom out
                1f/zoom,
                viewFocus = focus,
                myId,
                bodies = state.bodies.values.toList(),
            )
        }
    }
}

