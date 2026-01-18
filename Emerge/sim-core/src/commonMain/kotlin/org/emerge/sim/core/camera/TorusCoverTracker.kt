package org.emerge.sim.core.camera

import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.core.space.Torus2D

/**
 * Tracks a focus point in "cover space" (unwrapped), so camera motion is continuous even when the
 * wrapped focus crosses a seam.
 *
 * This is useful for shader-like rendering where the viewport can span multiple repeated tiles.
 */
class TorusCoverTracker(
    private val torus: Torus2D,
    initialFocusWrapped: Vec2Fx,
) {
    private var lastWrapped: Vec2Fx = initialFocusWrapped
    private var focusCover: Vec2Fx = initialFocusWrapped // cover space starts aligned

    fun update(focusWrapped: Vec2Fx): Vec2Fx {
        val d = torus.delta(focusWrapped, lastWrapped) // shortest torus delta
        focusCover = Vec2Fx(focusCover.x + d.x, focusCover.y + d.y)
        lastWrapped = focusWrapped
        return focusCover
    }

    fun current(): Vec2Fx = focusCover
}

