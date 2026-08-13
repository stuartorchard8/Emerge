package org.emerge.demo.outofspace

import org.emerge.sim.core.physics.primitives.Coord
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The camera's two frames, and the one thing that goes wrong when they are the same frame.
 *
 * The camera is the ship's centre of mass ([Camera.followVessel]) plus the player's drag off it, and
 * every test here is about the drag surviving something the ship does. A [Camera] is untestable
 * through [OutofspaceRenderer] — that needs a GL context — and the bug this suite pins was invisible
 * for exactly that reason: nothing here is wrong on a still ship, and a ship in flight is turning
 * all the time.
 */
class CameraTest {

    /**
     * **The test this class exists for.** With the view panned off the ship, turning the ship must
     * not move the ship on screen.
     *
     * Held in grid tiles, the pan turns with the hull, and the anchor walks a circle of the pan's
     * own radius while the player is holding nothing down. Here the anchor's screen position is
     * computed the way the renderer computes it — offset in tiles, scaled to pixels, then turned by
     * the view — and it has to come out the same at every heading.
     */
    @Test
    fun `turning the ship does not move a panned view`() {
        val cam = Camera()
        cam.followVessel(20f, 12f)
        cam.panByPixels(-140f, 60f) // the player drags the ship down-left to see ahead of it

        val at0 = anchorOnScreen(cam)
        for (angle in ANGLES) {
            turnTo(cam, angle)
            val at = anchorOnScreen(cam)
            assertClose(at0[0], at[0], "@${angle.raw} the anchor slid across the screen")
            assertClose(at0[1], at[1], "@${angle.raw} the anchor slid down the screen")
        }
    }

    /** And the pan is still a pan: it is the drag itself, on screen, whatever the heading. */
    @Test
    fun `a drag moves the view by the pixels dragged`() {
        for (angle in ANGLES) {
            val cam = Camera()
            cam.followVessel(20f, 12f)
            turnTo(cam, angle)

            val before = anchorOnScreen(cam)
            cam.panByPixels(35f, -80f)
            val after = anchorOnScreen(cam)

            assertClose(35f, after[0] - before[0], "@${angle.raw} drag x")
            assertClose(-80f, after[1] - before[1], "@${angle.raw} drag y")
        }
    }

    /**
     * Zooming holds the tile under the cursor still — the property the pan could most easily break,
     * since the pan reaches the camera divided by the zoom it is changing.
     */
    @Test
    fun `zoom keeps the tile under the cursor`() {
        for (angle in ANGLES) {
            val cam = Camera()
            cam.followVessel(20f, 12f)
            turnTo(cam, angle)
            cam.panByPixels(-140f, 60f)

            val cursorX = 1500f
            val cursorY = 250f
            val before = cam.screenToTile(cursorX, cursorY, RES_W, RES_H)
            cam.zoomAtScreen(cursorX, cursorY, 1.6f, RES_W, RES_H)
            val after = cam.screenToTile(cursorX, cursorY, RES_W, RES_H)

            assertClose(before[0], after[0], "@${angle.raw} zoom held x")
            assertClose(before[1], after[1], "@${angle.raw} zoom held y")
        }
    }

    /** A framing request lands the tile it names in the middle, whatever the last drag did. */
    @Test
    fun `looking at a tile puts it in the middle of the screen`() {
        for (angle in ANGLES) {
            val cam = Camera()
            cam.followVessel(20f, 12f)
            turnTo(cam, angle)
            cam.panByPixels(-140f, 60f)
            cam.lookAt(31f, 5f)

            val t = cam.screenToTile(RES_W * 0.5f, RES_H * 0.5f, RES_W, RES_H)
            assertClose(31f, t[0], "@${angle.raw} centre x")
            assertClose(5f, t[1], "@${angle.raw} centre y")
        }
    }

    /**
     * The view follows the ship, and the pan is what it follows it *at*: a ship under way keeps the
     * screen offset the player dragged out, rather than sliding out of frame or snapping back.
     */
    @Test
    fun `a ship under way stays where the player put it`() {
        for (angle in ANGLES) {
            val cam = Camera()
            cam.followVessel(20f, 12f)
            turnTo(cam, angle)
            cam.panByPixels(-140f, 60f)

            val before = anchorOnScreen(cam)
            cam.followVessel(48f, -13f) // the ship has travelled, and turned on the way
            turnTo(cam, Coord(angle.raw / 2))
            val after = anchorOnScreen(cam)

            assertClose(before[0], after[0], "@${angle.raw} the ship slid across the screen")
            assertClose(before[1], after[1], "@${angle.raw} the ship slid down the screen")
        }
    }

    /** And the ship is what the camera is on: with no pan, the centre of mass is the centre. */
    @Test
    fun `with no pan the centre of mass is the centre of the screen`() {
        val cam = Camera()
        cam.followVessel(20f, 12f)
        turnTo(cam, Coord(Int.MAX_VALUE / 3))
        cam.panByPixels(-140f, 60f)
        cam.dropPan()

        val t = cam.screenToTile(RES_W * 0.5f, RES_H * 0.5f, RES_W, RES_H)
        assertClose(20f, t[0], "centre x")
        assertClose(12f, t[1], "centre y")
    }

    /**
     * What Build mode rests on: nothing the player does moves the anchor, so a view told to follow
     * nothing follows nothing. Building would otherwise drift a little every time the centre of mass
     * moved under a newly placed machine.
     */
    @Test
    fun `only the vessel and the grid move the anchor`() {
        val cam = Camera()
        cam.followVessel(20f, 12f)
        turnTo(cam, Coord(Int.MAX_VALUE / 3))

        cam.panByPixels(-140f, 60f)
        cam.zoomAtScreen(1500f, 250f, 1.6f, RES_W, RES_H)
        cam.lookAt(31f, 5f)
        cam.dropPan()

        assertClose(20f, cam.anchorX, "anchor x")
        assertClose(12f, cam.anchorY, "anchor y")

        // And the one thing that does move it while the ship is parked: the grid grew on a near
        // edge, so the same place is two tiles further along both axes than it was.
        cam.shiftAnchor(2f, 2f)
        assertClose(22f, cam.anchorX, "anchor x after a near-edge growth")
        assertClose(14f, cam.anchorY, "anchor y after a near-edge growth")
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private fun turnTo(cam: Camera, angle: Coord) {
        val cs = ViewTurn.cosSin(angle)
        cam.setViewCosSin(cs[0], cs[1])
        turnCos = cs[0]
        turnSin = cs[1]
    }

    private var turnCos = 1f
    private var turnSin = 0f

    /**
     * Where the camera's anchor tile lands on screen, in pixels from the screen centre — the
     * renderer's own composition: a world offset in tiles, scaled by the zoom, then turned by the
     * view. The inverse of [Camera.screenToTile], stated forwards so the two cannot agree by both
     * being wrong.
     */
    private fun anchorOnScreen(cam: Camera): FloatArray {
        val dx = (cam.anchorX - cam.camX) * cam.tilePx
        val dy = (cam.anchorY - cam.camY) * cam.tilePx
        return floatArrayOf(
            turnCos * dx - turnSin * dy,
            turnSin * dx + turnCos * dy,
        )
    }

    private fun assertClose(expected: Float, actual: Float, what: String) {
        val tolerance = TOLERANCE * maxOf(1f, abs(expected))
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, got $actual")
    }

    private companion object {
        const val RES_W = 1920f
        const val RES_H = 1080f
        const val TOLERANCE = 1e-3f

        /** A turn's worth of headings, including the axes and both branch cuts. */
        val ANGLES = listOf(0, 1, Int.MAX_VALUE / 4, Int.MAX_VALUE / 2, Int.MAX_VALUE, -Int.MAX_VALUE / 3, -7)
            .map { Coord(it) }
    }
}
