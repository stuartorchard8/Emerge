package org.emerge.demo.outofspace

/**
 * Where the view is looking, in the two frames that have to disagree about it.
 *
 * The camera is the ship's centre of mass plus the player's drag off it, and those two are in
 * different frames on purpose:
 *
 * - the **anchor** is a tile in the *grid* frame, and the player never moves it. Flying, it follows
 *   the vessel's centre of mass ([followVessel]) — the point the hull turns about, so a turning ship
 *   turns *in place* on screen instead of swinging about some arbitrary corner of itself. Building,
 *   it follows nothing and simply stays where it is, because a workbench that drifts every time a
 *   machine is placed is worse than one that sits still; the only thing that moves it there is
 *   [shiftAnchor], for a grid that grew under it.
 * - the **pan** is the player's drag off that anchor, in *screen pixels*, and is the only camera
 *   movement the player controls. A drag is a statement about the screen — "put the ship down here,
 *   I want to see ahead of it" — and a ship that then turns has not changed where the player wants
 *   to be looking.
 *
 * Held in one frame they are wrong together. With the pan in grid tiles, as it first was, the offset
 * turns with the hull and walks the ship round a circle about the point it should be sitting still
 * at; with the anchor in screen pixels the view stops following the ship at all.
 *
 * [camX]/[camY] is the single camera everything downstream is written against — the cull window,
 * [OutofspaceRenderer.screenToTile], the zoom. Rather than translating the view matrix after the
 * rotation, which would move the picture and leave every one of those reading the old centre, the
 * pan is turned **back** into grid axes here. It is the same composition, taken as a pre-image, and
 * it keeps the camera one number.
 *
 * Testable on purpose: the renderer cannot be constructed without a GL context, and this is exactly
 * the arithmetic that is easy to get backwards and impossible to eyeball.
 */
class Camera {

    var anchorX = 0f
        private set
    var anchorY = 0f
        private set

    var panPxX = 0f
        private set
    var panPxY = 0f
        private set

    var tilePx = DEFAULT_TILE_PX
        private set

    /** `(cos, sin)` of the view angle in pixel axes, y down — [ViewTurn.cosSin]. */
    private var cos = 1f
    private var sin = 0f

    fun setViewCosSin(cos: Float, sin: Float) {
        this.cos = cos
        this.sin = sin
    }

    /** [cos],[sin] forwards — a grid-axis offset as the screen offset it draws to. */
    private fun turnX(dx: Float, dy: Float): Float = cos * dx - sin * dy
    private fun turnY(dx: Float, dy: Float): Float = sin * dx + cos * dy

    /** The tile at the centre of the screen: the anchor, plus the pan turned back into grid axes. */
    val camX: Float get() = anchorX + ViewTurn.unturnX(cos, sin, panPxX, panPxY) / tilePx
    val camY: Float get() = anchorY + ViewTurn.unturnY(cos, sin, panPxX, panPxY) / tilePx

    /**
     * The ship's centre of mass, in tiles — what the anchor follows in flight.
     *
     * It needs no [shiftAnchor] of its own: a centre of mass is measured in the grid the frame is
     * drawn from, so a grid that grew on a near edge reports a centre that has already moved with
     * it. A vessel with no mass at all should leave the anchor where it is — snapping the view to
     * the grid's corner because the last machine was deleted is a worse answer than not moving —
     * which is the caller's call to make, since only it can tell the difference.
     */
    fun followVessel(comTileX: Float, comTileY: Float) {
        anchorX = comTileX
        anchorY = comTileY
    }

    /**
     * The grid grew on a near edge, so the tile the anchor names has moved — see [FrameShift].
     *
     * Only Build mode needs this, and only because that is where the anchor stands still long
     * enough for the grid to move out from under it.
     */
    fun shiftAnchor(dx: Float, dy: Float) {
        anchorX += dx
        anchorY += dy
    }

    /**
     * Put [tileX],[tileY] at the centre of the screen *now*, by panning there.
     *
     * Not by moving the anchor: the anchor is the ship's, and a scripted or startup framing is the
     * same kind of statement a drag is. It therefore behaves like one too — the ship turning under
     * it will not drag the view around, and the next [followVessel] holds it exactly as steady.
     */
    fun lookAt(tileX: Float, tileY: Float) {
        val offX = (tileX - anchorX) * tilePx
        val offY = (tileY - anchorY) * tilePx
        panPxX = turnX(offX, offY)
        panPxY = turnY(offX, offY)
    }

    /** Back to the ship, wherever the player had dragged to. */
    fun dropPan() {
        panPxX = 0f
        panPxY = 0f
    }

    /** A drag, and it stays in the frame it was made in. */
    fun panByPixels(dxPixels: Float, dyPixels: Float) {
        panPxX -= dxPixels
        panPxY -= dyPixels
    }

    fun setZoom(pixelsPerTile: Float) {
        tilePx = pixelsPerTile.coerceIn(MIN_TILE_PX, MAX_TILE_PX)
    }

    /**
     * Zoom about a framebuffer pixel, keeping the tile under it where it is.
     *
     * The correction lands on the pan, because the pan is now the only part of the camera that is
     * the player's to move. A pixel offset `d` from the screen centre reads the tile at
     * `anchor + R⁻¹(pan + d) / tilePx`, so holding that tile still across a zoom of `k` is exactly
     * `pan' = k·(pan + d) − d` — no probe-and-correct, and exact at the zoom limits too, where the
     * clamp means the zoom the camera took is not the zoom it was asked for.
     */
    fun zoomAtScreen(px: Float, py: Float, factor: Float, resW: Float, resH: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val dx = px - resW * 0.5f
        val dy = py - resH * 0.5f
        val was = tilePx
        setZoom(tilePx * factor)
        val k = tilePx / was
        panPxX = k * (panPxX + dx) - dx
        panPxY = k * (panPxY + dy) - dy
    }

    /**
     * Framebuffer pixel → fractional tile coordinates.
     *
     * The offset from the screen centre is turned *back* before it is scaled, which is what keeps
     * building honest in a rotated view: the tile the player clicks is the tile they see under the
     * cursor, and a pipe dragged along a tilted hull follows the hull.
     */
    fun screenToTile(px: Float, py: Float, resW: Float, resH: Float): FloatArray {
        val dx = px - resW * 0.5f
        val dy = py - resH * 0.5f
        return floatArrayOf(
            camX + ViewTurn.unturnX(cos, sin, dx, dy) / tilePx,
            camY + ViewTurn.unturnY(cos, sin, dx, dy) / tilePx,
        )
    }

    companion object {
        const val DEFAULT_TILE_PX = 34f
        const val MIN_TILE_PX = 6f
        const val MAX_TILE_PX = 64f
    }
}
