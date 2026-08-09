package org.emerge.demo.outofspace.fluid

import org.emerge.demo.outofspace.OutofspaceConfig
import org.emerge.demo.outofspace.OutofspaceController
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one identity that has to hold before thrust can become motion.
 *
 * The vessel does not move yet, but every ingredient of its motion is already being accumulated:
 * `vesselImpulse` is what the gas has handed to the ship through walls, elbows and pump intakes, and
 * `exhaustMomentum` is what has gone overboard. Nothing has ever checked those two against the gas
 * still aboard, and until something does, a thrust figure is a number rather than a measurement.
 *
 * Momentum is conserved, and the world has exactly four places to keep it:
 *
 *     vesselImpulse + gasAboard + exhaust + undelivered == 0
 *
 * Everything starts at rest, so the total is zero and stays zero. Each term is signed in the same
 * frame: gas gains what nobody took from it, the ship takes what it was pushed by, and what leaves
 * through the rim is gone.
 *
 * The fourth is an admission rather than a place, and it is here because the alternative is worse. A
 * pressure difference across a face with **no gas on it** has no fluid to accelerate and no wall to
 * take the reaction, so the solve declines to deliver it — see [ProjectionResult.undeliveredX] for
 * the two fixes that were built, measured and rejected. Counting it keeps the identity exact, which
 * is what makes it an instrument; hiding it inside one of the other three would make the ledger a
 * tautology, and burying it would leave the identity approximate, which is the same as not having
 * one. Its *size* is the useful output: it says how much of a thrust figure is discretisation.
 *
 * Every pass that removes momentum from the gas books it to one of the other three — [applyDrag],
 * [applyBuoyancy], [applyPressureForce] and [project] to the ship, [advectMomentum] over the side —
 * so the identity is exact rather than approximate, and a failure means some pass is minting or
 * eating momentum without saying so.
 *
 * This is deliberately checked **every tick** rather than at the end. A ledger that closes only at
 * the end can be two errors that cancel, and the tick it first parts company on is most of the
 * diagnosis.
 *
 * The gas in the pipes counts. It is a second field but it is the same world, and momentum crossing
 * between the layers is a transfer rather than a source — see `exchangeLayers`.
 */
class ThrustBalanceTest {

    /**
     * A sealed ship is the strong case, not the weak one.
     *
     * Nothing leaves, so `exhaust` stays zero and the identity reduces to "what the ship gained is
     * what the gas lost". Gravity is on and the atmosphere is settling, so the individual terms are
     * moving the whole time — this is not a test of a still world. It is a test that sloshing is
     * bookkeeping rather than creation, which is exactly the property `MomentumField.totalX` warns
     * about in its own documentation.
     */
    @Test
    fun `a sealed vessel keeps its momentum in the places it can be`() {
        val controller = OutofspaceController(OutofspaceConfig(), starterVessel(OutofspaceConfig().initialGrid))
        repeat(TICKS) {
            controller.stepOnce()
            assertBalanced(controller.state, "sealed, tick ${controller.state.tick}")
        }
    }

    /**
     * A breached hull, which is the case the identity exists for.
     *
     * Gas leaves, so all three terms are live at once and the reaction on the ship has to match what
     * went out of the hole. The bare box is borrowed from `BreachSymmetryTest` for the same reason it
     * uses one: no machines means no refinery running alongside and nothing to attribute a
     * discrepancy to but the fluid.
     *
     * ### What this cost to make true, because it was red for three commits
     *
     * The residual over 120 ticks was **7478** and is now **zero**. Two of the three causes were
     * outright bugs. A bulkhead was erasing the momentum it stopped instead of handing it to the hull
     * (2040, all on the gravity axis — an atmosphere sits on a deck and advects into the floor
     * forever). And the CFL clamp in [applyPressureForce] was discarding momentum to enforce a limit
     * it did not actually enforce (5238); it is gone, and [stepFluid] sub-steps transport instead.
     *
     * The third is [project]'s undelivered impulse, which is counted rather than fixed — see
     * [ProjectionResult.undeliveredX] for the two fixes that were built and measured and cost more
     * than the term does.
     */
    @Test
    fun `a breached vessel accounts for what it threw overboard`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        val controller = OutofspaceController(cfg, bareHull(grid))
        controller.remove(grid.index(BREACH_X, HULL_ROW))

        repeat(TICKS) {
            controller.stepOnce()
            assertBalanced(controller.state, "breached, tick ${controller.state.tick}")
        }

        // The point of the exercise: something actually went out, so the identity above was doing
        // work rather than comparing three zeroes.
        val s = controller.state
        assertTrue(
            s.exhaustMomentumX != 0L || s.exhaustMomentumY != 0L,
            "nothing left through the breach, so this proved nothing",
        )
    }

    private fun assertBalanced(s: VesselState, what: String) {
        val aboardX = s.momentum.totalX + s.pipeMomentum.totalX
        val aboardY = s.momentum.totalY + s.pipeMomentum.totalY
        // The rock term belongs in the identity from H2 onward, and the starter vessel has rocks on
        // its extractor plates from H3 onward — gripped, chewed and handing momentum back — so a
        // sum without it now reads a legitimate exchange as the ship gaining momentum from nowhere.
        // See [VesselState.bodyImpulseX].
        assertEquals(
            0L,
            s.vesselImpulseX + aboardX + s.exhaustMomentumX + s.undeliveredImpulseX + s.bodyImpulseX,
            "$what: x — ship ${s.vesselImpulseX}, aboard $aboardX, exhaust ${s.exhaustMomentumX}, " +
                "undelivered ${s.undeliveredImpulseX}, rock ${s.bodyImpulseX}",
        )
        assertEquals(
            0L,
            s.vesselImpulseY + aboardY + s.exhaustMomentumY + s.undeliveredImpulseY + s.bodyImpulseY,
            "$what: y — ship ${s.vesselImpulseY}, aboard $aboardY, exhaust ${s.exhaustMomentumY}, " +
                "undelivered ${s.undeliveredImpulseY}, rock ${s.bodyImpulseY}",
        )
    }

    /** The mirror-symmetric box from `BreachSymmetryTest`: a hull, a roomful of air, nothing else. */
    private fun bareHull(grid: Grid): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_ROW); put(x, HULL_BOTTOM) }
        for (y in HULL_ROW..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        return VesselState(grid = grid, machines = machines.toList())
    }

    private companion object {
        const val TICKS = 120
        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_ROW = 6
        const val HULL_BOTTOM = 26
        const val BREACH_X = 17
    }
}
