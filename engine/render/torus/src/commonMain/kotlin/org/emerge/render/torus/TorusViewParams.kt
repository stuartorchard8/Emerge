package org.emerge.render.torus

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.camera.TorusCoverTracker
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D

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
    private var torus: Torus2D? = null
    private var tracker: TorusCoverTracker? = null

    fun compute(state: PhysicsState, myId: PlayerId?, zoom: Float): TorusViewParams {
        if (torus == null) {
            torus = Torus2D(width = state.width, height = state.height)
        }
        val t = torus!!
        val scale = Fx.SCALE.toFloat()
        val worldW = state.width.raw.toFloat() / scale
        val worldH = state.height.raw.toFloat() / scale

        // zoom < 1 => zoom out
        val viewW = worldW / zoom
        val viewH = worldH / zoom

        val focusWrapped: Vec2Fx =
            if (myId != null) state.bodies[myId]?.pos ?: Vec2Fx(Fx(state.width.raw / 2), Fx(state.height.raw / 2))
            else Vec2Fx(Fx(state.width.raw / 2), Fx(state.height.raw / 2))

        val tr = (tracker ?: TorusCoverTracker(t, focusWrapped)).also { tracker = it }
        val focusCover = tr.update(focusWrapped)

        val topLeftCoverX = focusCover.x.raw.toFloat() / scale - viewW * 0.5f
        val topLeftCoverY = focusCover.y.raw.toFloat() / scale - viewH * 0.5f

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

