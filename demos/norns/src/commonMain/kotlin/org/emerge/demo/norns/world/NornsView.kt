package org.emerge.demo.norns.world

import kotlin.math.roundToInt

/**
 * The side-scroll camera geometry, shared by the renderer (to build its projection) and by mouse
 * picking (to turn a screen click back into a world spot). Pure + testable so the screen↔world
 * mapping is verified even though the GL drawing that uses it isn't.
 *
 * Vertical extent shows all [floors]; the horizontal extent is derived from the window aspect so
 * world units are square in pixels (blobs stay round). Screen pixels use a top-left origin.
 */
class NornsView(
    val worldWidth: Int,
    val floors: Int,
    val floorSpacing: Float = 3.2f,
    val groundOffset: Float = 1.2f,
) {
    /** World-y of a creature standing on [floor]. */
    fun floorY(floor: Int): Float = floor * floorSpacing + groundOffset

    /** World-y for a continuous floor position (e.g. a creature riding a lift between floors). */
    fun floorYf(floor: Float): Float = floor * floorSpacing + groundOffset

    /** World-units shown vertically (all floors + a little headroom). */
    val verticalUnits: Float get() = floors * floorSpacing + 1.5f

    /** Clip-scale on each axis; sx is derived from aspect so units are square. */
    val sy: Float get() = 2f / verticalUnits
    fun sx(aspect: Float): Float = sy / aspect.coerceAtLeast(0.01f)

    /** World-units shown horizontally for the given window aspect. */
    fun horizontalUnits(aspect: Float): Float = 2f / sx(aspect)

    /** Camera's left world-edge for a desired centre, clamped to the world. */
    fun cameraLeft(centerX: Float, aspect: Float): Float {
        val horiz = horizontalUnits(aspect)
        return (centerX - horiz / 2f).coerceIn(0f, maxOf(0f, worldWidth - horiz))
    }

    /** Turns a screen pixel (top-left origin) into a world spot: (x, floor). */
    fun screenToWorld(px: Float, py: Float, fbW: Float, fbH: Float, centerX: Float, aspect: Float): WorldSpot {
        val left = cameraLeft(centerX, aspect)
        val wx = (left + (px / fbW) * horizontalUnits(aspect)).coerceIn(0f, (worldWidth - 1).toFloat())
        val wy = (1f - py / fbH) * verticalUnits
        val floor = ((wy - groundOffset) / floorSpacing).roundToInt().coerceIn(0, floors - 1)
        return WorldSpot(wx, floor)
    }
}

/** A world location: continuous x on a discrete [floor]. */
data class WorldSpot(val x: Float, val floor: Int)
