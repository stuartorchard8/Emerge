package org.emerge.render.torus

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx

data class TorusViewParams(
    val worldSizeX: Float,
    val worldSizeY: Float,
    val zoom: Float,
    val viewFocusX: Float,
    val viewFocusY: Float,
)

/**
 * Shared computation used by Android GLES + desktop LWJGL renderers.
 */
class TorusViewComputer {
    fun compute(state: PhysicsState, myId: PlayerId?, zoom: Float): TorusViewParams {
        val worldSizeX = 2f
        val worldSizeY = 2f

        val focusWrapped: Vec2Fx =
            if (myId != null) state.bodies[myId]?.pos ?: Vec2Fx(0, 0)
            else Vec2Fx(0, 0)

        val focusX = focusWrapped.x.toFloat()/Int.MAX_VALUE
        val focusY = focusWrapped.y.toFloat()/Int.MAX_VALUE

        return TorusViewParams(
            worldSizeX = worldSizeX,
            worldSizeY = worldSizeY,
            // zoom < 1 => zoom out
            zoom = 1f/zoom,
            viewFocusX = focusX,
            viewFocusY = focusY,
        )
    }
}

