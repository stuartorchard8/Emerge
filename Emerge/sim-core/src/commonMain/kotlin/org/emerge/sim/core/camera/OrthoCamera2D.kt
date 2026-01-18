package org.emerge.sim.core.camera

import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.Vec2Fx

/**
 * Client-side orthographic camera for 2D worlds.
 *
 * - Pure math (KMP-friendly).
 * - Uses world units (Fx) and clamps the view rectangle to the world bounds.
 *
 * Zoom semantics:
 * - zoom=1 => view shows the full world.
 * - zoom=2 => view shows half the world width/height (2x zoom-in).
 */
class OrthoCamera2D(
    private val worldW: Fx,
    private val worldH: Fx,
    val zoom: Int = 2,
) {
    init {
        require(zoom > 0) { "zoom must be > 0" }
    }

    val viewW: Fx = Fx(worldW.raw / zoom)
    val viewH: Fx = Fx(worldH.raw / zoom)

    /**
     * Returns the top-left world coordinate for a view centered on [focus], clamped to world bounds.
     */
    fun topLeftForFocus(focus: Vec2Fx): Vec2Fx {
        val halfW = Fx(viewW.raw / 2)
        val halfH = Fx(viewH.raw / 2)
        val unclamped = Vec2Fx(focus.x - halfW, focus.y - halfH)

        val maxX = Fx(worldW.raw - viewW.raw)
        val maxY = Fx(worldH.raw - viewH.raw)

        return Vec2Fx(
            x = clamp(unclamped.x, Fx(0), maxX),
            y = clamp(unclamped.y, Fx(0), maxY),
        )
    }

    private fun clamp(v: Fx, min: Fx, max: Fx): Fx =
        when {
            v < min -> min
            v > max -> max
            else -> v
        }
}

