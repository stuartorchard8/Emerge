package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BodyKind
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.machine.TileEnergy
import org.emerge.demo.outofspace.world.machine.reachableCell
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [reachableCell] against a body that is **turned**, which is the case nothing covered.
 *
 * A long body lying across the plate is over it whichever way round it is: turn it a quarter and its
 * cells sweep the same tiles back the other way. The extractor has to see that, because a rock that
 * arrives spinning is the normal case and not the exotic one.
 */
class ExtractorReachTest {

    /** A 1x5 bar, so "which way is it lying" is the only thing that can change the answer. */
    private fun bar(x: Long, y: Long, ang: Coord) = RigidBody(
        kind = BodyKind.ROCK,
        width = 1, height = 5, cells = BooleanArray(5) { true },
        positionX = x, positionY = y, impulseX = 0L, impulseY = 0L,
        ang = ang,
        oreComposition = OutofspaceReducer.DEFAULT_ORE_BODY,
        energy = TileEnergy.uniform(5, 0L),
    )

    @Test
    fun `turning a body changes which of its cells are over the plate`() {
        // A 5x5 plate at tiles [10,14]x[10,14]. The bar's corner sits on the plate's bottom row, so
        // standing up it hangs off the bottom edge and only its own first cell is over the plate.
        // Lying down it sweeps along that row instead, and a different cell is nearest the centre.
        val ship = Pose.IDENTITY
        val corner = 14L * Flight.PER_TILE

        val upright = reachableCell(bar(corner, corner, Coord(0)), ship, 10, 10, 14, 14)
        assertTrue(upright == 0, "standing up, only the corner cell is on the plate: got $upright")

        // Whichever way a quarter turn goes, the bar now lies along the plate's bottom row and
        // reaches back toward the centre — so the nearest cell cannot still be the corner one.
        val left = reachableCell(bar(corner, corner, Coord(1, 4)), ship, 10, 10, 14, 14)
        val right = reachableCell(bar(corner, corner, Coord(-1, 4)), ship, 10, 10, 14, 14)
        assertTrue(
            left > 0 || right > 0,
            "a turned bar reads exactly like an upright one: quarter turns gave $left and $right",
        )
    }
}
