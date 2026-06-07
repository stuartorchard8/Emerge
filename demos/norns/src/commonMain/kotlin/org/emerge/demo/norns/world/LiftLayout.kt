package org.emerge.demo.norns.world

/**
 * World-space placement of a lift car's furniture — the box, the per-floor call lamp, and the
 * up/down movement buttons on the carriage. Shared by the render host (to *draw* it) and the input
 * layer (to *hit-test* clicks), so the buttons you see are exactly the buttons you can press. All
 * values are in world units; positions are resolved against a [NornsView] (which owns floor spacing).
 */
object LiftLayout {
    const val HALF_W = 0.85f               // half the car's width
    const val HEIGHT = 2.35f               // the car's height (deck → roof)

    const val CALL_DX = -(HALF_W + 0.42f)  // call lamp: x offset from the shaft column (beside it)
    const val CALL_DY = 0.72f              // call lamp: height above that floor's feet line
    const val CALL_R = 0.24f               // clickable radius (a touch larger than drawn, easy to hit)

    const val MOVE_DX = 0.6f               // up/down buttons: x offset (on the carriage, right side)
    const val UP_DY = 1.5f                 // up button: height above the deck
    const val DOWN_DY = 1.05f              // down button: height above the deck
    const val MOVE_R = 0.22f               // clickable radius

    /** Feet/deck world-y of a (continuous) [floor] — the grass line creatures stand on. */
    fun feetY(view: NornsView, floor: Float): Float = view.floorYf(floor) - view.groundOffset

    /** The call lamp beside the shaft at [floor]. */
    fun callPos(view: NornsView, lift: Lift, floor: Int): WorldPoint =
        WorldPoint(lift.column + CALL_DX, feetY(view, floor.toFloat()) + CALL_DY)

    /** The up movement button on the carriage, at the car's current position. */
    fun upPos(view: NornsView, lift: Lift): WorldPoint =
        WorldPoint(lift.column + MOVE_DX, feetY(view, lift.carPos) + UP_DY)

    /** The down movement button on the carriage, at the car's current position. */
    fun downPos(view: NornsView, lift: Lift): WorldPoint =
        WorldPoint(lift.column + MOVE_DX, feetY(view, lift.carPos) + DOWN_DY)

    /** True if a (continuous) world point is within radius [r] of centre [c]. */
    fun hit(p: WorldPoint, c: WorldPoint, r: Float): Boolean {
        val dx = p.x - c.x; val dy = p.y - c.y
        return dx * dx + dy * dy <= r * r
    }
}
