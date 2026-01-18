package org.emerge.sim.core.camera

import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D

/**
 * Orthographic camera on a true torus.
 *
 * Unlike clamped cameras, the view origin wraps around the world.
 * Rendering should use torus tiling (e.g. 3x3) for seam-correct visuals.
 */
class TorusOrthoCamera2D(
    private val torus: Torus2D,
    val zoom: Int = 2,
) {
    init {
        require(zoom > 0) { "zoom must be > 0" }
    }

    val viewW: Fx = Fx(torus.width.raw / zoom)
    val viewH: Fx = Fx(torus.height.raw / zoom)

    fun topLeftForFocus(focus: Vec2Fx): Vec2Fx {
        val halfW = Fx(viewW.raw / 2)
        val halfH = Fx(viewH.raw / 2)
        return torus.wrap(Vec2Fx(focus.x - halfW, focus.y - halfH))
    }
}

