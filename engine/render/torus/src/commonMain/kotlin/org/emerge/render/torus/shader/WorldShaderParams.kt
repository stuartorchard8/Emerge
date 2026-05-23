package org.emerge.render.torus.shader

import org.emerge.render.torus.rendererViewFocus
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Vec2

data class WorldShaderParams(
    val worldSize: Vec2,
    val zoom: Float,
    val viewFocus: Vec2,
    val viewRotationRad: Float,
    val myId: PlayerId?,
) {
    companion object {
        fun compute(state: PhysicsState, myId: PlayerId?, zoom: Float, viewRotationRad: Float = 0f): WorldShaderParams {
            val focusWrapped: Coord2 =
                if (myId != null) state.rendererViewFocus(myId)
                else Coord2.zero

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
