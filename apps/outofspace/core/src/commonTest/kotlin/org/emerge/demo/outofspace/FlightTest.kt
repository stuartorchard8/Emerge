package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The loop closing: a sealed vessel does not depart, a firing one does, and the momentum ledger
 * still balances while it is accelerating.
 *
 * Everything here rests on one identity that was made exact an increment ago — see
 * `ThrustBalanceTest`. The ship's momentum is [VesselState.vesselImpulseX], the gas's is on the
 * faces, and the two are equal and opposite until something goes over the side. Motion is what you
 * get when you divide the first of those by a mass; there is nothing else to it, which is the point.
 *
 * ⚠️ The engine here is a **hole**, and deliberately. A breach in a vertical wall vents along x and
 * pushes along x, which is the axis-aligned engine the plan insists the first one must be —
 * `applyBuoyancy` is the one function permitted to assume gravity lies on an axis, and it has never
 * been run off one. A nozzle that could point anywhere is a scheduled follow-up, not a thing to
 * discover as a bug underneath three subsystems.
 */
class FlightTest {

    /**
     * Sloshing air moves the hull, and that is correct rather than a defect.
     *
     * A sealed ship whose atmosphere is settling recoils from it: the centre of mass of ship-plus-air
     * does not move, so if the air goes one way the hull goes the other. The bare box rings like a
     * drum for as long as you care to watch — nothing damps a sealed atmosphere — so the hull rings
     * with it, at about eight hundredths of a tile.
     *
     * What must not happen is *departure*. A sealed vessel that kept gaining speed would be momentum
     * from nowhere, and the ledger says it cannot: the ship's momentum is minus the gas's, and there
     * is only so hard gas can slosh. So this checks an **envelope** rather than a value, and checks
     * that the envelope does not grow — measured over two halves of a long run, because a slow
     * divergence is exactly what a short one would miss.
     */
    @Test
    fun `a sealed vessel rings and does not depart`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))

        var early = 0L
        var late = 0L
        repeat(LONG_TICKS) { i ->
            controller.stepOnce()
            val s = controller.state
            val excursion = maxOf(abs(s.positionX), abs(s.positionY))
            if (i < LONG_TICKS / 2) early = maxOf(early, excursion) else late = maxOf(late, excursion)
        }

        assertTrue(
            late < Flight.PER_TILE / 5L,
            "a sealed vessel wandered $late, which is more than a fifth of a tile",
        )
        // The envelope, not the position: a standing wave revisits its peak, and there is no tick at
        // which it is meaningful to ask where in the cycle it should be. What is meaningful is that
        // the second half does not swing further than the first.
        assertTrue(
            late <= early * 3L / 2L,
            "the sealed vessel's excursion is growing: $early early, $late late",
        )
    }

    /**
     * A hole in the port wall, and the ship leaves to starboard.
     *
     * The direction is the whole assertion. Gas crosses the rim heading **−x**, so the reaction on
     * the hull is **+x**, so the position grows. A sim that got the sign wrong would still show a
     * moving ship, which is why this checks which way rather than whether.
     *
     * Speed is checked as a trend and not tick by tick, because a blowout is not a steady burn: the
     * plume pulses, the thrust with it, and there are stretches where the ship coasts. What has to
     * be true is that it never goes backwards and ends up much faster than it started.
     */
    @Test
    fun `a breached vessel accelerates away from the hole and keeps going`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))
        controller.remove(cfg.initialGrid.index(HULL_LEFT, BREACH_Y))

        var opening = 0L
        var previousPosition = 0L
        repeat(TICKS) { i ->
            controller.stepOnce()
            val s = controller.state
            if (i == 0) opening = s.velocityX
            assertTrue(s.velocityX >= 0L, "tick ${s.tick}: the ship went backwards at ${s.velocityX}")
            assertTrue(s.positionX >= previousPosition, "tick ${s.tick}: the ship slid back down its track")
            previousPosition = s.positionX
        }

        val s = controller.state
        assertTrue(s.velocityX > opening * 10L, "the ship barely gained on its first tick: $opening to ${s.velocityX}")
        assertTrue(s.positionX > Flight.PER_TILE, "and should have covered a tile by now: ${s.positionX}")
        // The engine is on the x axis and nothing is pushing along y, so the sideways drift is the
        // atmosphere ringing under the plating rather than thrust. It must stay far smaller than the
        // thing being measured, or the "axis-aligned" claim above is not true of this fixture.
        assertTrue(
            abs(s.positionY) * 10L < s.positionX,
            "the breach pushed sideways too: x ${s.positionX}, y ${s.positionY}",
        )
    }

    /**
     * The ship is pushed **before** any gas reaches the rim, which is the load-bearing half of the
     * model this increment was built on.
     *
     * Thrust is Newton's third law from gas pressing unevenly on the inside of the hull, not from
     * gas crossing the edge of the grid. Open a hole and the wall opposite it is instantly
     * unbalanced, so the ship feels it on the very first tick — long before the first gram gets
     * anywhere near the boundary. A model that read thrust off the boundary flux instead would show
     * nothing at all here, and would put the whole plume's flight time between an engine lighting and
     * a ship moving.
     */
    // no thrust between the cut-over and blocked-flux thrust — extraction plan step 6
    @Ignore
    @Test
    fun `thrust arrives before the exhaust does`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))
        controller.remove(cfg.initialGrid.index(HULL_LEFT, BREACH_Y))
        controller.stepOnce()

        val s = controller.state
        assertEquals(0L, s.exhaustMomentumX, "gas had already left, so this proved nothing")
        assertEquals(s.baselineAirGrams, s.atmosphereGrams, "and not a gram of it had gone overboard")
        assertTrue(s.netImpulseX > 0L, "the ship felt nothing on the tick the hull opened")
        assertTrue(s.velocityX > 0L, "and so it was already moving")
    }

    // no thrust between the cut-over and blocked-flux thrust — extraction plan step 6
    @Ignore
    @Test
    fun `an engine is felt inside the ship as a gravity pointing astern`() {
        val cfg = OutofspaceConfig()
        // Run under plating **explicitly**, because plating is no longer what a vessel has by
        // default — see [VesselState.FREEFALL]. The claim being made here is that thrust is *added*
        // to whatever gravity a hull already has and never overwrites it, and that claim needs a
        // non-zero one to be about anything. It reads as a fixture saying what it is testing, which
        // is what dropping the default bought.
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid).copy(gravity = VesselState.PLATING_ONE_G))
        controller.remove(cfg.initialGrid.index(HULL_LEFT, BREACH_Y))

        var leanX = 0L
        var leanY = 0L
        repeat(TICKS) {
            controller.stepOnce()
            leanX += controller.state.feltGravity.x.raw
            leanY += controller.state.feltGravity.y.raw - VesselState.PLATING_ONE_G.y.raw
        }

        assertTrue(leanX < 0L, "an engine pushing +x should be felt as a pull toward -x, not $leanX")
        // Nothing is pushing along y, so what shows up there is the atmosphere leaning on the deck
        // as it rings. A wobble beside the engine rather than a second engine, which is what makes
        // this fixture axis-aligned enough to reason about.
        //
        // ⚠️ It was a few hundredths of the thrust and is now about a tenth, and the ratio moved for
        // a reason rather than drifting: fixing the settling truncation (see [scaleByGravity]) took
        // the double-damping off buoyancy, so a sealed hull now rings roughly twice as hard. The
        // claim being made is unchanged and still comfortably true — an order of magnitude between
        // the engine's axis and the wobble — so the bound is restated rather than the claim weakened.
        assertTrue(
            abs(leanY) * 8L < abs(leanX),
            "the engine is not on an axis: x lean $leanX, y lean $leanY",
        )
        // The plating is a setting and the thrust is added to it, so thrusting never rewrites it.
        assertEquals(VesselState.PLATING_ONE_G, controller.state.gravity)
    }

    /**
     * A save carries where the ship got to and what its plating is set to.
     *
     * Position is the one part of flight that cannot be re-derived — velocity is momentum over mass
     * and momentum is already in the file — so it is the one part a save can lose, and losing it
     * would put a ship that had been under way for an hour back at the origin.
     */
    // no thrust between the cut-over and blocked-flux thrust — extraction plan step 6
    @Ignore
    @Test
    fun `a save remembers the voyage`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))
        controller.remove(cfg.initialGrid.index(HULL_LEFT, BREACH_Y))
        repeat(TICKS) { controller.stepOnce() }

        val played = controller.state
        val loaded = Save.read(Save.write(played))
        assertTrue(played.positionX > 0L, "the ship never went anywhere, so this proved nothing")
        assertEquals(played.positionX, loaded.positionX)
        assertEquals(played.positionY, loaded.positionY)
        assertEquals(played.gravity, loaded.gravity)
        // Last tick's thrust, because the felt gravity is worked out from it: a world that lost it
        // would coast for one tick under the plating alone and never line up again.
        assertEquals(played.netImpulseX, loaded.netImpulseX)
        assertEquals(played.netImpulseY, loaded.netImpulseY)
        assertEquals(played.feltGravity, loaded.feltGravity)
        assertEquals(played.velocityX, loaded.velocityX, "velocity is derived, so it has to survive too")
    }

    /**
     * The debug engine flies the ship, and the ledger stays closed while it cheats.
     *
     * That second half is the whole reason [Edit.Thrust] has a store of its own. `momentumBalance` is
     * the instrument that found §5e's truncation bug, and a key that put momentum into the ship
     * without booking it would make that number non-zero forever — retiring the instrument to buy a
     * shortcut, which is a bad trade at any price. So the identity gains a fifth term and stays an
     * identity, and it is checked on every tick of the burn rather than at the end of it.
     */
    @Test
    fun `the debug engine flies the ship and books what it mints`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))

        controller.thrustX = 1
        repeat(BURN_TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(
                0L,
                s.vesselImpulseX + s.momentum.totalX + s.pipeMomentum.totalX +
                    s.exhaustMomentumX + s.undeliveredImpulseX - s.debugImpulseX,
                "tick ${s.tick}: the debug engine minted ${s.debugImpulseX} and the books do not say so",
            )
        }
        controller.thrustX = 0

        val flying = controller.state
        assertTrue(flying.debugImpulseX > 0L, "nothing was ever minted, so this proved nothing")
        assertTrue(flying.velocityX > 0L, "the engine fired and the ship did not move")
        assertTrue(flying.positionX > Flight.PER_TILE, "it should be a tile clear by now: ${flying.positionX}")

        // Letting go coasts. There is nothing to slow a ship down out here, so the velocity it had
        // when the key came up is the velocity it keeps — and the debug store stops growing, which is
        // what says the engine is off rather than merely quiet.
        val speed = flying.velocityX
        val minted = flying.debugImpulseX
        repeat(BURN_TICKS) { controller.stepOnce() }
        assertEquals(minted, controller.state.debugImpulseX, "the engine kept firing after the key came up")
        assertTrue(
            abs(controller.state.velocityX - speed) * 20L < speed,
            "the ship coasted at ${controller.state.velocityX} having been at $speed",
        )

        // And a save carries the admission. A file that dropped it would load a world whose ledger
        // was permanently out by exactly the amount somebody had cheated, which reads as a leak.
        val loaded = Save.read(Save.write(controller.state))
        assertEquals(controller.state.debugImpulseX, loaded.debugImpulseX)
        assertEquals(controller.state.debugImpulseY, loaded.debugImpulseY)
    }

    /**
     * The engine is stated as an **acceleration**, so a heavy ship needs a bigger push for the same
     * flight — and gets one, without anything having to arrange it.
     *
     * Measured in vacuum, deliberately. With air aboard the two hulls slosh differently, the
     * comparison acquires a tolerance, and a tolerance is where a test stops saying what it means.
     * Empty, the velocity is exactly the debug store over the mass, so both halves are exact: the
     * same speed, out of a strictly larger impulse.
     *
     * This is the property that makes a laden hold a sluggish ship for free once H1 puts rocks in
     * `cargoGrams` — the mass a thrust is divided by is the same walk as the mass the conservation
     * check compares, and it always has been.
     */
    @Test
    fun `the debug engine is an acceleration and not a push`() {
        val cfg = OutofspaceConfig()
        val light = OutofspaceController(cfg, vacuumHull(cfg.initialGrid, ballast = false))
        val heavy = OutofspaceController(cfg, vacuumHull(cfg.initialGrid, ballast = true))

        light.thrustX = 1
        heavy.thrustX = 1
        repeat(BURN_TICKS) { light.stepOnce(); heavy.stepOnce() }

        assertTrue(heavy.state.massGrams > light.state.massGrams, "the ballast weighed nothing")
        assertEquals(
            light.state.velocityX, heavy.state.velocityX,
            "same engine, same acceleration, different mass — and different speeds",
        )
        assertTrue(
            heavy.state.debugImpulseX > light.state.debugImpulseX,
            "the heavy ship reached the same speed without being pushed any harder",
        )
    }

    /** A hull with no air in it, so a burn is arithmetic rather than a measurement. */
    private fun vacuumHull(grid: Grid, ballast: Boolean): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        if (ballast) for (x in HULL_LEFT..HULL_RIGHT) for (y in HULL_TOP + 1 until BREACH_Y) put(x, y)
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            air = AirField.of(LongArray(grid.size * Species.COUNT)),
        )
    }

    /** The mirror-symmetric box `ThrustBalanceTest` uses: a hull, a roomful of air, nothing else. */
    private fun bareHull(grid: Grid): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        return VesselState(grid = grid, machines = machines.toList())
    }

    private fun abs(v: Long): Long = if (v < 0L) -v else v

    private companion object {
        init { RockSpawner.enabled = false }

        /** 120 ticks of a 35×33 fluid solve, which is about three quarters of a second. */
        const val TICKS = 120

        /** Long enough for a burn to be a tile clear of the origin, and short enough to be free. */
        const val BURN_TICKS = 60

        /** Twice that, for the one question — does it diverge? — that a short run cannot answer. */
        const val LONG_TICKS = 240

        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_TOP = 6
        const val HULL_BOTTOM = 26

        /** Midships, so the hole is as far from a corner as it can be. */
        const val BREACH_Y = 16
    }
}
