package org.emerge.demo.outofspace

import org.emerge.render.torus.Mat4
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
     * The matrix that turns NDC by the pixel-space rotation `(cos, sin)`, written into [into].
     *
     * `S⁻¹·R·S` with `S = diag(a, −1)` — the pixel→NDC scale with its common factor dropped, since
     * only the ratio `a = W/H` survives the conjugation. Spelled as that product rather than as the
     * four entries it multiplies out to, so the thing the file is *about* is the thing the code
     * says; the engine's [Mat4] does the multiplying and the y flip is part of `S`, not a sign
     * hand-carried through the algebra.
     *
     * The two off-diagonal terms end up carrying reciprocal aspects. If they ever carry the *same*
     * one, the scene shears instead of turning, and a square screen would hide it.
     */
    fun transform(cos: Float, sin: Float, aspect: Float, into: Mat4) {
        val s = scale.setScale(aspect, -1f)
        val r = rotation.setRotationZ(cos, sin)
        // `into` may alias neither operand of a single setProduct, and does not: it is only ever the
        // left operand's *result*, computed after both scratch matrices are filled.
        into.setProduct(scaleInv.setScale(1f / aspect, -1f), product.setProduct(r, s))
    }

    /** Scratch for [transform]; the renderer calls it every frame, and a frame allocates nothing. */
    private val scale = Mat4.scratch()
    private val scaleInv = Mat4.scratch()
    private val rotation = Mat4.scratch()
    private val product = Mat4.scratch()

    /** A pixel offset from the screen centre, turned **back** — `R(−θ)`, the inverse of [transform]. */
    fun unturnX(cos: Float, sin: Float, dx: Float, dy: Float): Float = cos * dx + sin * dy

    fun unturnY(cos: Float, sin: Float, dx: Float, dy: Float): Float = -sin * dx + cos * dy
}
