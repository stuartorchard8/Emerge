package org.emerge.demo.outofspace

import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Norm

/**
 * Turning the whole scene on screen, in the two places that have to agree about it.
 *
 * The renderer turns *pixels* — the player is looking at a screen, and a tile has to stay square on
 * it — but the GPU is handed *NDC*, where x and y have different scales. Those are not the same
 * rotation. Writing `R(θ)` straight into NDC shears every rect by the aspect ratio, and the correct
 * matrix is `R` conjugated by the resolution's own scale. That conjugation is the whole content of
 * this file, and it is here rather than inline in [OutofspaceRenderer] for one reason: the renderer
 * cannot be constructed without a GL context, so anything living inside it cannot be tested, and
 * this is precisely the arithmetic that is easy to get backwards and impossible to eyeball.
 *
 * The pixel frame is y-**down** throughout, matching the grid's +y — so a positive angle reads as
 * clockwise on screen, which is what [world.VesselState.ang] means.
 */
object ViewTurn {

    /** `(cos, sin)` of [angle] in pixel axes, via the engine's integer [Norm.fromAngle]. */
    fun cosSin(angle: Coord): FloatArray {
        val n = Norm.fromAngle(angle)
        return floatArrayOf(n.x.toFloat(), n.y.toFloat())
    }

    /**
     * The 2x2 that turns NDC by the pixel-space rotation `(cos, sin)`, row-major into [into].
     *
     * `S⁻¹·R·S` with `S = diag(W/2, −H/2)`, which comes out as `[[c, s/a], [−s·a, c]]` for
     * `a = W/H`. The two off-diagonal terms carry reciprocal aspects; if they ever carry the *same*
     * one, the scene shears instead of turning and a square screen would hide it.
     */
    fun transform(cos: Float, sin: Float, aspect: Float, into: FloatArray) {
        into[0] = cos
        into[1] = sin / aspect
        into[2] = -sin * aspect
        into[3] = cos
    }

    /** A pixel offset from the screen centre, turned **back** — `R(−θ)`, the inverse of [transform]. */
    fun unturnX(cos: Float, sin: Float, dx: Float, dy: Float): Float = cos * dx + sin * dy

    fun unturnY(cos: Float, sin: Float, dx: Float, dy: Float): Float = -sin * dx + cos * dy
}
