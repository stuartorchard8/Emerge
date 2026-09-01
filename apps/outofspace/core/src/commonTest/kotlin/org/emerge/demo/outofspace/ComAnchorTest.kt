package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.num.Budget
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What anchoring a vessel on its centre of mass actually buys — `PLAN_com_anchored_frames.md`.
 *
 * Both of these failed before the anchor flipped, and neither is a statement about rounding: they
 * are exact, because the quantity each one holds still is the quantity that is *stored*, and holding
 * a stored number still is the absence of arithmetic rather than the success of some.
 */
class ComAnchorTest {

    /**
     * **The one the plan is for.** Cargo moving aboard moves the hull, not the centre of mass.
     *
     * A ship in free space with an ingot sliding down a rail does not go anywhere: no force acts, so
     * its centre of mass cannot move, and the hull recoils instead. With the grid origin stored the
     * sim had this exactly backwards — the hull stayed nailed down and the centre drifted through
     * open space unpushed, which is momentum conservation stated in reverse.
     *
     * So the assertion is in two halves, and the second is the one with teeth: the centre does not
     * move (⚠️ which alone would also pass if *nothing* moved), and every tile of the grid shifts by
     * exactly the amount the centre shifted within it, the other way.
     */
    @Test
    fun `moving cargo aboard moves the hull and not the centre of mass`() {
        val ore = Mixture.of(Species.Iron to 900L * Budget.KILOGRAM, energy = 0)
        val before = twoStores().stocked(GRID.tile(4, 4), ore)
        val after = twoStores().stocked(GRID.tile(28, 20), ore)

        assertEquals(before.positionX, after.positionX, "the stored centre moved when cargo did")
        assertEquals(before.positionY, after.positionY, "the stored centre moved when cargo did")

        val shifted = after.distribution.comX - before.distribution.comX
        assertTrue(shifted != 0L, "the fixture moved no mass, so this proved nothing")

        // Any tile will do — the whole grid moves together, which is what "rigid" means.
        val probeX = 9L * Flight.PER_TILE
        val probeY = 7L * Flight.PER_TILE
        val hullMoved = after.pose.toWorldX(probeX, probeY) - before.pose.toWorldX(probeX, probeY)
        assertEquals(
            -shifted, hullMoved,
            "the centre went $shifted through the grid, so the hull owed exactly ${-shifted}",
        )
    }

    /**
     * A vessel spinning in place stays in place — exactly, and for every angle of a whole turn.
     *
     * ⚠️ This is the bug that started the whole plan. The nav readout printed the stored position
     * and it traced a circle whenever the ship rotated, because the stored point was tile (0,0)'s
     * corner and a corner *does* orbit the centre of mass. Nothing was wrong with the flight model;
     * the number on the panel was a frame-internal quantity being read as a place.
     */
    @Test
    fun `a spinning vessel does not go anywhere`() {
        val spinning = twoStores().copy(angImpulse = 2_000_000_000_000L)
        val controller = OutofspaceController(OutofspaceConfig(), spinning)

        val startX = controller.state.positionX
        val startY = controller.state.positionY
        var turned = false
        repeat(120) {
            controller.stepOnce()
            if (controller.state.ang.raw != 0) turned = true
            assertEquals(startX, controller.state.positionX, "the ship drifted on x while spinning")
            assertEquals(startY, controller.state.positionY, "the ship drifted on y while spinning")
        }
        assertTrue(turned, "the ship never actually turned, so this proved nothing")
        // And far enough round that a corner would have been all the way about the centre.
        assertTrue(controller.state.ang.raw != 0, "the spin stopped")
    }

    /** A sealed vacuum box with a store at each end, so cargo has somewhere to be moved between. */
    private fun twoStores(): VesselState {
        val deck = DeckArray(GRID)
        fun put(x: Int, y: Int) {
            if (GRID.inBounds(x, y) && deck[GRID.tile(x, y)] == null) deck += Hull(GRID.tile(x, y))
        }
        for (x in 2..30) { put(x, 2); put(x, 22) }
        for (y in 2..22) { put(2, y); put(30, y) }
        deck += fixtureStorage(GRID.tile(4, 4), org.emerge.demo.outofspace.world.Direction.Right)
        deck += fixtureStorage(GRID.tile(28, 20), org.emerge.demo.outofspace.world.Direction.Right)
        val s = VesselState(
            grid = GRID,
            deck = deck,
            buffers = BufferLayer.forDeck(GRID, deck),
            rail = RailLayer.empty(GRID.size),
        )
        // Vacuum: sloshing air is a real force and would move the centre for real, which is a
        // different claim from the one being made here.
        return s.copy(air = Stuff.gas(MassArray(s.grid.size)))
    }

    private companion object {
        val GRID = OutofspaceConfig().initialGrid
    }
}
