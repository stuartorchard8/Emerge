package org.emerge.demo.fluidlab

import org.emerge.demo.fluidlab.chem.Species
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lab's own contract — that the box around the solver is honest.
 *
 * These are not tests of the solver: that is what the copied `fluid/` and `chem/` suites are for.
 * These check the three things the extraction could plausibly have broken while wiring it into a new
 * app — that a sealed world conserves what it holds, that the ledger accounts for what leaves, and
 * that the hull reaction is zero exactly when it should be.
 *
 * Tick counts are small on purpose: the whole file runs in well under a second. Where a number is
 * asserted it is derived (a total against its own parts), never a pinned literal — a literal here
 * would be pinning today's discretisation and would turn every future tuning change into a test fight.
 */
class FluidlabSimTest {

    private val cfg = FluidlabConfig(width = 16, height = 12)

    @Test
    fun `a sealed room holds every gram and every joule`() {
        val start = FluidlabState.sealedRoom(cfg)
        val controller = FluidlabController(cfg, start)

        val end = controller.stepTicks(40)

        assertEquals(start.totalGrams(), end.totalGrams(), "mass changed in a sealed room")
        assertEquals(start.totalJoules(), end.totalJoules(), "energy changed in a sealed room")
        assertEquals(0L, end.totalVentedGrams, "a sealed room vented")
    }

    @Test
    fun `a sealed room pushes itself nowhere`() {
        val controller = FluidlabController(cfg, FluidlabState.sealedRoom(cfg))

        val end = controller.stepTicks(40)

        // The telescoping property `applyPressureForce` documents: internal pressure terms cancel
        // exactly, so a hull with no hole in it cannot accelerate itself. This is the invariant worth
        // keeping when Out of Space drops the solver — a ship that thrusts from nothing reads as a
        // mystery bug rather than as something needing tuning.
        assertEquals(0L, end.report.vesselX, "a sealed room developed sideways thrust")
        assertEquals(0L, end.report.vesselY, "a sealed room developed vertical thrust")
    }

    @Test
    fun `what leaves through a breach is what the ledger says left`() {
        val start = FluidlabState.sealedRoom(cfg)
        val controller = FluidlabController(cfg, start)
        // A hole in the ceiling, one tile wide.
        controller.setWall(start.grid.index(8, 0), false)

        val end = controller.stepTicks(40)

        assertTrue(end.totalVentedGrams > 0L, "a breached room vented nothing")
        assertEquals(
            start.totalGrams(),
            end.totalGrams() + end.totalVentedGrams,
            "mass is neither in the room nor on the ledger",
        )
        assertEquals(
            start.totalJoules(),
            end.totalJoules() + end.totalVentedJoules,
            "energy is neither in the room nor on the ledger",
        )
    }

    @Test
    fun `a breach pushes the hull away from the hole`() {
        val start = FluidlabState.sealedRoom(cfg)
        val controller = FluidlabController(cfg, start)
        controller.setWall(start.grid.index(8, 0), false)

        val end = controller.stepTicks(20)

        // Hole in the ceiling (-y), so the reaction is downward (+y is screen-down). Asserting the
        // sign and not the size: the magnitude is a discretisation, the direction is physics.
        assertTrue(end.report.vesselY > 0L, "venting upward did not push the hull down (${end.report.vesselY})")
    }

    @Test
    fun `injected gas arrives at the temperature it was asked for`() {
        val start = FluidlabState.sealedRoom(cfg)
        val controller = FluidlabController(cfg, start)
        val tile = start.grid.index(8, 6)

        controller.inject(tile, Species.Water, mass = 4_000, kelvin = 500)
        val end = controller.stepTicks(1)

        assertTrue(end.air.gramsOf(tile, Species.Water) > 0L, "the water never arrived")
        // One tick of transport moves heat around, so this is a neighbourhood, not an equality: the
        // point is that it landed hot rather than at whatever the tile's prior energy implied.
        assertTrue(end.air.kelvinAt(tile) > AMBIENT_KELVIN, "injected gas arrived cold")
    }

    @Test
    fun `an edit made while paused still lands`() {
        val start = FluidlabState.sealedRoom(cfg)
        val controller = FluidlabController(cfg, start)
        controller.paused = true
        val tile = start.grid.index(4, 4)

        controller.setWall(tile, true)
        controller.tick(1f)

        assertTrue(controller.state.walls[tile] != null, "a wall placed while paused never appeared")
    }
}
