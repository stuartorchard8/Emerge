package org.emerge.render.torus

import org.emerge.sim.core.physics.primitives.Coord2

/**
 * Linear-RGB color for instance tints and edge indicators. A color whose channels
 * are all zero is treated by the body shaders as "no custom tint"; they then fall
 * back to a deterministic hash of the per-instance primary id.
 */
data class RgbColor(val r: Float, val g: Float, val b: Float) {
    val isTransparent: Boolean get() = r == 0f && g == 0f && b == 0f

    companion object {
        val Transparent = RgbColor(0f, 0f, 0f)
    }
}

/**
 * An off-screen marker projected onto the viewport edge. Used by demos that want to
 * show players where notable entities are when those entities aren't currently
 * visible. The renderer projects [worldPos] to the nearest viewport edge, draws a
 * small triangle there in [color], and modulates by [alpha] (which the caller has
 * already faded by distance / team relationship / whatever rule it likes).
 */
data class EdgeIndicator(
    val worldPos: Coord2,
    val color: RgbColor,
    val alpha: Float,
)
