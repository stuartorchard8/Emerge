package org.emerge.demo.outofspace

import org.emerge.render.torus.Mat4
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 3 of `PLAN_trig_free_rotation.md`: the camera has an orientation, and the two frames that
 * orientation is expressed in have to agree.
 *
 * The whole risk of this step lives in one place. The renderer thinks in pixels, where a tile is
 * square and a turn is a turn; the GPU is handed NDC, where x and y have different scales and the
 * same matrix is a shear. Everything here is about the seam between those two, because it is the
 * one part that cannot be checked by looking: a sheared scene at a shallow angle on a 16:9 display
 * looks like a scene that is merely turned.
 */
class ViewTurnTest {

    /**
     * **The test the whole step rests on.** Turning a point in NDC through [ViewTurn.transform] must
     * land where turning the same point in *pixels* lands.
     *
     * Checked on a wide screen and again on a tall one, because the naive matrix — `R` written
     * straight into NDC, or the conjugation applied the wrong way round — is *correct* at 1:1 and
     * wrong everywhere else. A square test resolution would pass all three implementations.
     */
    @Test
    fun `turning in NDC is turning in pixels`() {
        for ((w, h) in RESOLUTIONS) {
            for (angle in ANGLES) {
                val cs = ViewTurn.cosSin(angle)
                ViewTurn.transform(cs[0], cs[1], w / h, m)

                for ((px, py) in PIXELS) {
                    // The same offset, taken round both ways: NDC then turn, versus turn then NDC.
                    val ndc = ndc(px, py, w, h)
                    val viaNdc = floatArrayOf(
                        m00 * ndc[0] + m01 * ndc[1],
                        m10 * ndc[0] + m11 * ndc[1],
                    )
                    val turned = turnPixels(cs[0], cs[1], px, py)
                    val viaPixels = ndc(turned[0], turned[1], w, h)

                    assertClose(viaPixels[0], viaNdc[0], "${w}x$h @${angle.raw} x")
                    assertClose(viaPixels[1], viaNdc[1], "${w}x$h @${angle.raw} y")
                }
            }
        }
    }

    /**
     * A turn that shears is a turn that changes lengths. Pixel distance from the screen centre is
     * what a player reads as "the ship is the same size", so it must survive the turn exactly.
     */
    @Test
    fun `a turn preserves pixel distance from the screen centre`() {
        for (angle in ANGLES) {
            val cs = ViewTurn.cosSin(angle)
            for ((px, py) in PIXELS) {
                val t = turnPixels(cs[0], cs[1], px, py)
                assertClose(px * px + py * py, t[0] * t[0] + t[1] * t[1], "@${angle.raw} radius²")
            }
        }
    }

    /** Un-turning is turning backwards, or the tile under the cursor is not the tile clicked. */
    @Test
    fun `unturn inverts the turn`() {
        for (angle in ANGLES) {
            val cs = ViewTurn.cosSin(angle)
            for ((px, py) in PIXELS) {
                val t = turnPixels(cs[0], cs[1], px, py)
                assertClose(px, ViewTurn.unturnX(cs[0], cs[1], t[0], t[1]), "@${angle.raw} x")
                assertClose(py, ViewTurn.unturnY(cs[0], cs[1], t[0], t[1]), "@${angle.raw} y")
            }
        }
    }

    /**
     * A quarter turn clockwise on screen, spelled out — the one case worth pinning to a direction
     * rather than to a relation, since every property above holds just as well for a turn that goes
     * the wrong way.
     *
     * +y is down, so a positive [Coord] angle is clockwise: the point to the right of centre must go
     * to below centre, and not above it.
     */
    @Test
    fun `a positive angle turns the scene clockwise`() {
        val cs = ViewTurn.cosSin(Coord(Int.MAX_VALUE / 2)) // +π/2
        val t = turnPixels(cs[0], cs[1], 100f, 0f)

        assertClose(0f, t[0], "a quarter turn leaves nothing on the x axis")
        assertTrue(t[1] > 90f, "the right of the screen must swing to the bottom, not the top: ${t[1]}")
    }

    /** Not turning is not moving: the identity matrix, so a Build-mode view is bit-for-bit as before. */
    @Test
    fun `a zero angle is exactly the identity`() {
        val cs = ViewTurn.cosSin(Coord(0))
        for ((w, h) in RESOLUTIONS) {
            ViewTurn.transform(cs[0], cs[1], w / h, m)

            // Numeric comparison rather than a list equality: the off-diagonals with a zero sine are
            // negative zero, which is a different *value* to `List.equals` and the same *number* to
            // everything else, the GPU included.
            //
            // Exact, and every resolution, because the conjugation multiplies the diagonal by the
            // aspect and then by its reciprocal: a float pair that failed to round-trip would leave
            // a still view very slightly scaled. It holds for these; the assertion is here so a
            // future change of scale factors cannot lose it quietly.
            assertTrue(
                m00 == 1f && m01 == 0f && m10 == 0f && m11 == 1f,
                "not identity at ${w}x$h: ${m.m.toList()}",
            )
        }
    }

    /** Build holds the grid still and Flight holds the world still — see [Mode.camera]. */
    @Test
    fun `the mode chooses the frame`() {
        assertEquals(CameraFrame.Grid, Mode.Build.camera)
        assertEquals(CameraFrame.World, Mode.Flight.camera)
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private val m = Mat4.scratch()

    // The 2x2 the turn actually is, read out of the column-major 4x4: column c, row r is `m[c*4+r]`.
    private val m00 get() = m.m[0]
    private val m01 get() = m.m[4]
    private val m10 get() = m.m[1]
    private val m11 get() = m.m[5]

    /** A pixel offset from the screen centre, as an NDC offset — the renderer's own conversion. */
    private fun ndc(px: Float, py: Float, w: Float, h: Float) =
        floatArrayOf(px / w * 2f, -py / h * 2f)

    /** `R(θ)` in pixel axes, y down: what the turn is *supposed* to be, stated independently. */
    private fun turnPixels(cos: Float, sin: Float, px: Float, py: Float) =
        floatArrayOf(cos * px - sin * py, sin * px + cos * py)

    private fun assertClose(expected: Float, actual: Float, what: String) {
        // Relative, because the radius² test compares numbers around 10⁵ while the NDC tests compare
        // numbers around 1, and one absolute epsilon cannot be honest about both.
        val tolerance = TOLERANCE * maxOf(1f, abs(expected))
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")
    }

    private companion object {
        /**
         * Float, and the values run to 10⁵, so this is loose by the standards of `TrigTest`. It is
         * not measuring [org.emerge.sim.core.physics.primitives.Trig] — that is pinned exactly in
         * its own suite — it is measuring that two float paths agree, and the camera is the one
         * place in this app where float is the right answer.
         */
        const val TOLERANCE = 1e-4f

        /** Wide, tall, and square — the naive matrix is correct at 1:1 and wrong either side of it. */
        val RESOLUTIONS = listOf(1920f to 1080f, 600f to 900f, 512f to 512f)

        /** A turn's worth of headings, deliberately including the axes and both branch cuts. */
        val ANGLES = listOf(0, 1, Int.MAX_VALUE / 4, Int.MAX_VALUE / 2, Int.MAX_VALUE, -Int.MAX_VALUE / 3, -7)
            .map { Coord(it) }

        /** Pixel offsets from the screen centre: the axes, a diagonal, and a far corner. */
        val PIXELS = listOf(
            100f to 0f, 0f to 100f, -250f to 0f, 0f to -60f, 300f to 400f, -960f to 540f,
        )
    }
}
