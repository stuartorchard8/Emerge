package org.emerge.demo.norns.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-verification harness for the camera geometry (G11 mouse-picking side): a screen click maps
 * back to the world spot the camera is showing. The GL that uses this isn't verifiable here, but
 * the screen↔world math is.
 */
class NornsViewTest {

    private val view = NornsView(worldWidth = 120, floors = 3)

    @Test
    fun centreClickMapsToCameraCentre() {
        val centerX = 60f
        val aspect = 1000f / 620f
        val spot = view.screenToWorld(px = 500f, py = 310f, fbW = 1000f, fbH = 620f, centerX = centerX, aspect = aspect)
        // horizontal centre of the screen → horizontal centre of the camera window
        val expectedX = view.cameraLeft(centerX, aspect) + view.horizontalUnits(aspect) / 2f
        assertTrue(kotlin.math.abs(spot.x - expectedX) < 0.5f, "centre x: got ${spot.x}, want ~$expectedX")
    }

    @Test
    fun clickingHighOnScreenSelectsAHigherFloor() {
        val aspect = 1000f / 620f
        val low = view.screenToWorld(500f, 590f, 1000f, 620f, 60f, aspect)   // near the bottom
        val high = view.screenToWorld(500f, 60f, 1000f, 620f, 60f, aspect)    // near the top
        assertTrue(high.floor >= low.floor, "higher on screen → same/higher floor (low=${low.floor} high=${high.floor})")
        assertEquals(0, low.floor, "the very bottom of the screen is the ground floor")
    }

    @Test
    fun clicksStayInWorldBounds() {
        val aspect = 2.0f
        for (px in floatArrayOf(-50f, 0f, 500f, 1000f, 2000f)) {
            val s = view.screenToWorld(px, 100f, 1000f, 500f, centerX = 10f, aspect = aspect)
            assertTrue(s.x in 0f..(view.worldWidth - 1).toFloat(), "x clamped: ${s.x}")
            assertTrue(s.floor in 0 until view.floors, "floor clamped: ${s.floor}")
        }
    }

    @Test
    fun creatureNearFindsTheClosestOnTheFloor() {
        val w = NornsWorld(NornsConfig(), seed = 4)
        repeat(30) { w.step() }
        val target = w.creatures.first()
        val found = w.creatureNear(target.floor, target.x, radius = 1.5f)
        assertTrue(found != null && found.floor == target.floor, "should find a creature near a known one")
        // far-away empty spot finds nothing
        assertEquals(null, w.creatureNear(target.floor, target.x + 1000f, radius = 1.5f))
    }
}
