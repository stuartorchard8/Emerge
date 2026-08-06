package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Occupancy
import org.emerge.demo.outofspace.world.RockField
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The world starts with rocks in it — increment H4.
 *
 * The whole increment is "there is ore out there to fly to", so what is worth pinning is not the
 * layout but the three things that would make a scattered field a liability: it must not start
 * inside the ship, it must not start the ledgers off balance, and it must be the same field twice.
 */
class RockFieldTest {

    private val grid = Grid(96, 60)

    /**
     * The ledger, which is the reason this is generated into the constructor rather than dropped in
     * as an edit.
     *
     * A rock that was here when the world was made did not *arrive*: it is baseline mass and
     * baseline energy, so `capturedGrams` stays at nought and the identity in [VesselState] holds
     * with every term zero. Dropping the same rocks in through `F6` would be a world that began by
     * admitting twelve rocks it had had all along.
     */
    @Test
    fun `a starting field is baseline mass, not captured mass`() {
        val s = starterVessel(grid)

        assertTrue(s.rocks.isNotEmpty(), "the world started empty, so this proved nothing")
        assertEquals(s.rockGrams, s.baselineRockGrams, "the field is not in the rock baseline")
        assertEquals(0L, s.capturedGrams, "rocks that were always here were booked as arrivals")
        assertEquals(0L, s.extractedGrams, "a rock existing counted as ore extracted")
        assertEquals(
            s.baselineRockGrams + s.capturedGrams - s.extractedGrams, s.rockGrams,
            "the rock ledger did not start balanced",
        )
        assertEquals(s.storedJoules, s.baselineJoules, "the field's heat is not in the solid baseline")
    }

    /**
     * Nothing starts inside the ship.
     *
     * Rocks collide since H2, so a rock overlapping the hull at tick zero is a rock the solver has to
     * dig out of a wall — and the one thing it cannot do is undo a world that began wrong.
     */
    @Test
    fun `no rock overlaps the vessel`() {
        val s = starterVessel(grid)
        val occupancy = Occupancy.derive(s.grid, s.machines)

        for (rock in s.rocks) {
            for (cy in 0 until rock.height) {
                for (cx in 0 until rock.width) {
                    if (!rock.cells[cy * rock.width + cx]) continue
                    val x = (rock.positionX / org.emerge.demo.outofspace.world.Flight.PER_TILE).toInt() + cx
                    val y = (rock.positionY / org.emerge.demo.outofspace.world.Flight.PER_TILE).toInt() + cy
                    // Off the grid is ordinary, not a failure — §8. The vessel fits its own box
                    // now, so the field extends well past it and most rocks start outside.
                    //
                    // ⚠️ `/` truncates toward zero, so a rock at -0.5 tiles reads as tile 0 rather
                    // than -1. Harmless here — column 0 is padding and always free — but it is a
                    // floor that isn't, and `overlapsHull` deliberately does this properly. Do not
                    // copy this arithmetic into anything that decides a collision.
                    if (!s.grid.inBounds(x, y)) continue
                    assertTrue(
                        occupancy.isFree(s.grid.index(x, y)),
                        "a rock started inside the vessel at ($x, $y)",
                    )
                }
            }
        }
    }

    /**
     * The same seed is the same world.
     *
     * Every determinism check in the suite builds two starter vessels and compares them, so a field
     * that varied between them would fail those tests for the one reason that is not a bug.
     */
    @Test
    fun `a seed is a world`() {
        val a = starterVessel(grid).rocks
        val b = starterVessel(grid).rocks
        assertEquals(a, b, "two starter vessels disagreed about where the rocks are")

        val other = starterVessel(grid, rockSeed = RockField.DEFAULT_SEED + 1).rocks
        assertTrue(other != a, "the seed does nothing — every world is the same world")
    }

    /** A fixture that wants an empty sky can say so, and gets one. */
    @Test
    fun `a field can be asked for and refused`() {
        assertEquals(emptyList(), starterVessel(grid, rocks = 0).rocks)
        assertEquals(4, starterVessel(grid, rocks = 4).rocks.size)
    }

    /**
     * A grid with no room gives up rather than hanging or wedging a rock into the hull.
     *
     * The fixtures build small grids, and a world generator that cannot finish on one is a generator
     * no fixture can call.
     */
    @Test
    fun `a crowded grid returns fewer rocks, not worse ones`() {
        val tight = Grid(40, 28)
        val s = starterVessel(tight, rocks = 200)
        assertTrue(s.rocks.size < 200, "200 rocks fitted in a 40x28 grid, which cannot be right")
        assertEquals(s.rockGrams, s.baselineRockGrams, "however many fitted, they are all baseline")
    }
}
