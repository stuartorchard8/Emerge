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
 * Momentum is conserved, and the world has exactly three places to keep it:
 *
 *     vesselImpulse + gasAboard + exhaust == 0
 *
 * Everything starts at rest, so the total is zero and stays zero. Each term is signed in the same
 * frame: gas gains what nobody took from it, the ship takes what it was pushed by, and what leaves
 * through the rim is gone. Every pass in the solver that removes momentum from the gas books it to
 * one of the other two — [applyDrag], [applyBuoyancy], [applyPressureForce] and [project] to the
 * ship, [advectMomentum] over the side — so the identity is exact rather than approximate, and a
 * failure means some pass is minting or eating momentum without saying so.
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
    fun `a sealed vessel keeps its momentum in the three places it can be`() {
        val controller = OutofspaceController(OutofspaceConfig(), starterVessel(OutofspaceConfig().grid))
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
     * ### ⚠️ This one still fails, on one known term, and the term needs a decision rather than a fix
     *
     * The residual over 120 ticks was 7478 and is now **238**, out of a vessel impulse of 25310. Two
     * of the three causes are gone and both were bugs: a bulkhead was erasing the momentum it stopped
     * instead of keeping it (2040), and the CFL clamp in `applyPressureForce` was discarding momentum
     * to enforce a limit it did not actually enforce (5238, removed in favour of sub-stepping).
     *
     * What is left is [project]. Where a face is **open but has no gas on it**, the solved pressure
     * difference across it is real and there is nothing to give it to — no fluid to accelerate and no
     * wall to take the reaction — so the impulses stop telescoping and the shortfall is momentum the
     * scheme declines to deliver. It is a plume-front effect: at tick 1 it is 136 and by tick 3 it is
     * 3, and it never grows.
     *
     * Two fixes were tried and **both were measured and rejected**, so neither is worth retrying blind:
     *
     * - **Pin `p = 0` on tiles holding no gas** — the textbook free-surface boundary condition, and
     *   the principled answer. It very nearly closes the ledger (down to 2 by tick 10) and it stops
     *   blowout dead: `ProjectionTest` reports a room that does not decompress at all and a breach
     *   that does not push the ship. The vacuum side of the interface needs a solved pressure for the
     *   gradient that drives the vent to exist.
     * - **Give the impulse to the massless faces anyway**, and let the existing stranded-momentum
     *   sweep book it as exhaust. This closes the ledger *exactly*, on both axes. It also injects
     *   momentum into vacuum: the midships plume lean goes from 1% to 6%, the bow's from 5% to 19%,
     *   and `PumpTest` fails. It closes the books by manufacturing exhaust.
     *
     * So the remaining choice is the one this was always heading for: name the term. It is momentum
     * the discretisation dropped, it is small, it does not accumulate, and a named ledger entry makes
     * the identity exact while measuring how much of the thrust figure is numerical.
     */
    @Test
    fun `a breached vessel accounts for what it threw overboard`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.grid
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
        assertEquals(
            0L,
            s.vesselImpulseX + aboardX + s.exhaustMomentumX,
            "$what: x — ship ${s.vesselImpulseX}, aboard $aboardX, exhaust ${s.exhaustMomentumX}",
        )
        assertEquals(
            0L,
            s.vesselImpulseY + aboardY + s.exhaustMomentumY,
            "$what: y — ship ${s.vesselImpulseY}, aboard $aboardY, exhaust ${s.exhaustMomentumY}",
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
