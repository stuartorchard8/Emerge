package org.emerge.render.torus

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusCoverTracker
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx

data class TorusViewParams(
    val worldW: Float,
    val worldH: Float,
    val viewW: Float,
    val viewH: Float,
    val topLeftCoverX: Float,
    val topLeftCoverY: Float,
)

/**
 * Shared computation used by Android GLES + desktop LWJGL renderers.
 *
 * Keeps a [TorusCoverTracker] so we can compute a stable "cover-space" top-left that avoids camera wrap jitter.
 */
class TorusViewComputer {
    private var tracker: TorusCoverTracker? = null

    fun compute(state: PhysicsState, myId: PlayerId?, zoom: Float): TorusViewParams {
        val worldW = 2f
        val worldH = 2f

        // zoom < 1 => zoom out
        val viewW = worldW / zoom
        val viewH = worldH / zoom

        val focusWrapped: Vec2Fx =
            if (myId != null) state.bodies[myId]?.pos ?: Vec2Fx(0, 0)
            else Vec2Fx(0, 0)

        val tr = (tracker ?: TorusCoverTracker(focusWrapped)).also { tracker = it }
        val focusCover = tr.update(focusWrapped)

        val topLeftCoverX = focusCover.x.toFloat()/Int.MAX_VALUE
        val topLeftCoverY = focusCover.y.toFloat()/Int.MAX_VALUE

        return TorusViewParams(
            worldW = worldW,
            worldH = worldH,
            viewW = viewW,
            viewH = viewH,
            topLeftCoverX = topLeftCoverX,
            topLeftCoverY = topLeftCoverY,
        )
    }
}

