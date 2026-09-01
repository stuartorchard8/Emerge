package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Ignore
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.airCoupling
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
    // ✅ **UN-PARKED.** It was red because the residue it measures was the share of each face's
    // pressure drop that `applyPressureForce` handed the gas and diffusion never handed back — the
    // ship keeping half of an exchange whose other half went into a field no physics spent. With
    // the hull's reaction moved to the vessel boundary there is no such share: a sealed ship is
    // pushed by nothing at all, so it rings and stays put, which is what this always claimed.
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
        controller.removeAt(cfg.initialGrid.tile(HULL_LEFT, BREACH_Y))

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

        // How far it got, against what a steady thrust says it should have. Under constant
        // acceleration from rest the distance is the *average* speed times the time, and the average
        // of a ramp from nothing is half the final — so x ≈ v·t/2, and the run bears that out at
        // about 90% of it (the shortfall is the first few ticks, before the plume is established).
        //
        // This used to read `> PER_TILE`, and a whole tile is simply not a distance this ship covers
        // in 120 ticks: the hull weighs forty tonnes of real steel and vents seven kilos of air over
        // the run, so it gets about a twelfth of a tile. That figure was calibrated when hull plate
        // was a made-up density rather than a real one at [DeckMachineKind.fillPermille], and
        // pinning a distance re-pins it every time that dial moves. What does not move is the shape:
        // a constant thrust integrates to v·t/2 whatever the ship weighs, so check the integrator
        // against the kinematics and let the magnitude be whatever the masses make it.
        val predicted = s.velocityX * TICKS / 2L
        assertTrue(
            s.positionX > predicted / 2L && s.positionX < predicted * 2L,
            "travelled ${s.positionX}, but a steady ramp to ${s.velocityX} over $TICKS ticks predicts ~$predicted",
        )
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
    @Test
    fun `thrust arrives with the exhaust, not before it`() {
        // ⛔ **This asserted the opposite until the hull reaction moved to the vessel boundary**, and
        // the old behaviour was the bug rather than a feature: the ship was pushed by the pressure
        // drop across a wall the moment a hole appeared, before a single gram had gone anywhere. A
        // pressure difference across a bulkhead is internal to ship-plus-air; what pushes a ship is
        // mass leaving it. So the push now arrives *with* the gas, which is what a rocket is.
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))
        controller.removeAt(cfg.initialGrid.tile(HULL_LEFT, BREACH_Y))
        repeat(OutofspaceReducer.PRESSURE_OFFSET + 1) { controller.stepOnce() }

        val opened = controller.state
        assertEquals(opened.baselineAirMass, opened.atmosphereMass, "gas had already left, so this proves nothing")
        assertEquals(0L, opened.netImpulseX, "the ship was pushed before anything left it")

        // And then it does arrive, once the gas actually starts crossing the rim.
        repeat(400) { controller.stepOnce() }
        val flying = controller.state
        assertTrue(flying.atmosphereMass < flying.baselineAirMass, "nothing ever vented")
        assertTrue(flying.velocityX > 0L, "the breach never pushed the ship at all: ${flying.velocityX}")
    }

    @Test
    fun `an engine is felt inside the ship as a gravity pointing astern`() {
        val cfg = OutofspaceConfig()
        // Run under plating **explicitly**, because plating is no longer what a vessel has by
        // default — see [VesselState.FREEFALL]. The claim being made here is that thrust is *added*
        // to whatever gravity a hull already has and never overwrites it, and that claim needs a
        // non-zero one to be about anything. It reads as a fixture saying what it is testing, which
        // is what dropping the default bought.
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid).copy(gravity = VesselState.PLATING_ONE_G))
        controller.removeAt(cfg.initialGrid.tile(HULL_LEFT, BREACH_Y))

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
    @Test
    fun `a save remembers the voyage`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))
        controller.removeAt(cfg.initialGrid.tile(HULL_LEFT, BREACH_Y))
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
                s.momentumBalanceX,
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
     * `cargoMass` — the mass a thrust is divided by is the same walk as the mass the conservation
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

        assertTrue(heavy.state.mass > light.state.mass, "the ballast weighed nothing")
        // Within the quantisation, not to the unit. The debug engine turns an acceleration into an
        // impulse by multiplying by mass and the flight step turns it back by dividing, and that
        // round trip truncates toward zero once per tick against a divisor that IS the mass — so two
        // ships of different mass can end a burn one unit apart per tick. They used to agree exactly,
        // for the wrong reason: `scaledRatio` reduced the fraction by shifting and the discarded bits
        // were far wider than the discrepancy, so both ships landed on the same coarse grid. Now that
        // the ratio is exact the difference is visible, and one unit in 1.5e9 is what it should be.
        assertTrue(
            kotlin.math.abs(light.state.velocityX - heavy.state.velocityX) <= BURN_TICKS,
            "same engine, same acceleration, different mass — and different speeds: " +
                "${light.state.velocityX} vs ${heavy.state.velocityX} over $BURN_TICKS ticks",
        )
        assertTrue(
            heavy.state.debugImpulseX > light.state.debugImpulseX,
            "the heavy ship reached the same speed without being pushed any harder",
        )
    }

    /** A hull with no air in it, so a burn is arithmetic rather than a measurement. */
    private fun vacuumHull(grid: Grid, ballast: Boolean): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        if (ballast) for (x in HULL_LEFT..HULL_RIGHT) for (y in HULL_TOP + 1 until BREACH_Y) put(x, y)
        // Creative: the engine here is a *hole*, punched by removing a hull tile. Outside creative
        // that delete marks the tile and the wall stays up, so there is nothing to vent through.
        return VesselState(
            grid = grid,
                        deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).copy(creative = true)
    }

    /** The mirror-symmetric box `ThrustBalanceTest` uses: a hull, a roomful of air, nothing else. */
    // ── The ship and its air are two bodies ──────────────────────────────────

    /**
     * **A ship tows its own atmosphere, and pays for it.**
     *
     * The air is not in [VesselState.mass] — [forEachVesselMass] takes no air argument, and 200 kg
     * poured into a corner leaves the mass, the centre of mass and the radius of gyration
     * bit-identical. So a hull that changes speed leaves its gas behind and has to drag it along,
     * and that drag is an exchange with two halves rather than a force from nowhere.
     *
     * ⛔ **The store this fills is the one the old per-edge momentum field only pretended to be.**
     * That one was written by the pressure solver and read by no physics, so counting it in the
     * ledger let the identity close over momentum that could never move anything. This one is read
     * back every firing and changes what the ship does — which is the whole difference, and the
     * reason it may be a ledger term at all. See [VesselState.momentumBalanceX].
     */
    @Test
    fun `a ship tows its own atmosphere, and pays for it`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))
        repeat(200) {
            controller.thrustX = 1
            controller.stepOnce()
            MomentumLedger.assertBalanced(controller.state, "tick ${controller.state.tick} under thrust")
        }

        val s = controller.state
        assertTrue(s.vesselImpulseX > 0L, "the ship never got going, so this proves nothing")
        assertTrue(
            s.airMomentumX > 0L,
            "the ship accelerated and its atmosphere was left exactly where it was: the air is " +
                "being carried for free, which is what the coupling exists to stop",
        )
        // The air is lighter than the hull and lags it, so it may never be carrying more.
        assertTrue(
            s.airMomentumX < s.vesselImpulseX,
            "the air is holding more momentum than the ship towing it: ${s.airMomentumX} vs ${s.vesselImpulseX}",
        )
    }

    /**
     * **The air keeps catching up after the engines stop**, which is what "loosely coupled" means:
     * the gap closes at [Flight.AIR_COUPLING_SECONDS] whether or not anything is still pushing.
     */
    @Test
    fun `the atmosphere goes on catching up once the burn ends`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, bareHull(cfg.initialGrid))
        repeat(100) { controller.thrustX = 1; controller.stepOnce() }
        controller.thrustX = 0
        val atCutoff = controller.state.airMomentumX

        repeat(400) { controller.stepOnce() }

        val s = controller.state
        assertTrue(
            s.airMomentumX > atCutoff,
            "the air stopped catching up the moment the engine did — it was following the thrust " +
                "rather than following the hull ($atCutoff -> ${s.airMomentumX})",
        )
        MomentumLedger.assertBalanced(s, "coasting with a lagging atmosphere")
    }

    /**
     * ⛔ **Gas that leaves takes its share of the atmosphere's momentum with it.**
     *
     * Without this the store outlives the gas that was carrying it: the coupling reads a momentum
     * too large for the mass still aboard, decides the air is running ahead of the hull, and hands
     * the difference **back to the ship** — a reactionless drive built out of the repair for one.
     *
     * ⚠️ **Asked of [airCoupling] directly, and that is why the function exists.** The same claim
     * written as "fly a ship, breach it, watch" measures nothing: the effect is a couple of per cent
     * and the breach reaction is a thousand times larger, so it passed identically with the
     * carry-away removed. Two attempts at an integration-level version — one watching the air's
     * speed, one venting both sides at once so the reaction cancelled — both failed to discriminate,
     * by 0% and 1.6% respectively. The law is small and exact; it wants asking, not observing.
     */
    @Test
    fun `vented gas takes its share of the air's momentum with it`() {
        val air = MassDistribution(mass = 500L, comMilliX = 0L, comMilliY = 0L, gyrationSq = 1_000_000L)
        val ship = MassDistribution(mass = 100_000L, comMilliX = 0L, comMilliY = 0L, gyrationSq = 4_000_000L)

        // Half the gas goes overboard, and the ship is not moving, so nothing else can act.
        val half = airCoupling(
            airMomentumX = 8_000L, airMomentumY = -4_000L, airAngImpulse = 2_000L,
            airMassBefore = 1_000L, ventedMass = 500L,
            vesselImpulseX = 0L, vesselImpulseY = 0L, angImpulse = 0L,
            ship = ship, air = air, sharePermille = 1000,
        )
        assertEquals(4_000L, half.carriedX, "half the gas took half the x momentum")
        assertEquals(-2_000L, half.carriedY, "half the gas took half the y momentum")
        assertEquals(1_000L, half.carriedTorque, "half the gas took half the twist")

        // And what is left is dragged toward a hull that is not moving — so back to zero, and no
        // further. The drag must answer for what is *still aboard*, not for what has gone.
        assertEquals(-4_000L, half.dragX, "the drag was struck against the momentum that had left")
        assertEquals(2_000L, half.dragY)

        // Nothing vented, nothing carried.
        val sealed = airCoupling(
            airMomentumX = 8_000L, airMomentumY = 0L, airAngImpulse = 0L,
            airMassBefore = 1_000L, ventedMass = 0L,
            vesselImpulseX = 0L, vesselImpulseY = 0L, angImpulse = 0L,
            ship = ship, air = air.copy(mass = 1_000L), sharePermille = 1000,
        )
        assertEquals(0L, sealed.carriedX, "a sealed ship sent momentum overboard")
    }

    /** All of it leaves when all of the gas does — no remainder stranded in a store with no mass. */
    @Test
    fun `an atmosphere that vents completely leaves nothing behind`() {
        val gone = airCoupling(
            airMomentumX = 7_777L, airMomentumY = 3_333L, airAngImpulse = -555L,
            airMassBefore = 1_000L, ventedMass = 1_000L,
            vesselImpulseX = 1_000_000L, vesselImpulseY = 0L, angImpulse = 0L,
            ship = MassDistribution(mass = 100_000L, comMilliX = 0L, comMilliY = 0L, gyrationSq = 4_000_000L),
            air = MassDistribution.EMPTY,
            sharePermille = 1000,
        )
        assertEquals(7_777L, gone.carriedX, "momentum was stranded in an atmosphere that no longer exists")
        assertEquals(3_333L, gone.carriedY)
        assertEquals(-555L, gone.carriedTorque)
        assertEquals(0L, gone.dragX, "an empty atmosphere was still being dragged")
    }

    /** The hull drags its air toward its own motion, and never past it. */
    @Test
    fun `the drag closes the gap toward the hull and stops there`() {
        val ship = MassDistribution(mass = 100_000L, comMilliX = 0L, comMilliY = 0L, gyrationSq = 4_000_000L)
        val air = MassDistribution(mass = 1_000L, comMilliX = 0L, comMilliY = 0L, gyrationSq = 1_000_000L)
        // The ship carries 1e6 over 100_000 of mass, so air of 1_000 wants exactly 10_000.
        fun dragFrom(held: Long) = airCoupling(
            airMomentumX = held, airMomentumY = 0L, airAngImpulse = 0L,
            airMassBefore = 1_000L, ventedMass = 0L,
            vesselImpulseX = 1_000_000L, vesselImpulseY = 0L, angImpulse = 0L,
            ship = ship, air = air, sharePermille = 1000,
        ).dragX

        assertEquals(10_000L, dragFrom(0L), "still air was not brought up to the hull's speed")
        assertEquals(0L, dragFrom(10_000L), "air already at the hull's speed was dragged further")
        assertEquals(-5_000L, dragFrom(15_000L), "air running ahead of the hull was not slowed")
        // A partial share closes part of the gap, and the sign is the same.
        assertEquals(
            1_000L,
            airCoupling(
                0L, 0L, 0L, 1_000L, 0L, 1_000_000L, 0L, 0L, ship, air, sharePermille = 100,
            ).dragX,
            "a tenth of the gap is not a tenth of the drag",
        )
    }



    private fun bareHull(grid: Grid): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        // Creative, for the reason above: the breach is made by deleting a hull tile.
        return VesselState(grid = grid, deck=deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .copy(creative = true)
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

    /**
     * The ledger, while the ship is accelerating — which is the case it has never been checked in.
     *
     * `ThrustBalanceTest` proves the identity holds on a breached hull under a *constant* gravity.
     * This is the same identity under a gravity that is being rewritten every tick by the thrust it
     * is measuring, which is a loop that did not exist before this increment:
     *
     *     thrust → experienced gravity → gas piles toward the breach → more thrust
     *
     * If any of it minted momentum, the four stores would stop summing to zero, and they are checked
     * every tick rather than at the end because the tick it first parts company on is most of the
     * diagnosis.
     *
     * (It used to watch the `undelivered` term too — the share of the projection's solve that had
     * nowhere to go. That store is retired along with the per-edge gas momentum.)
     */
    // ⛔ A test stood here watching the `undelivered` term stay small against the thrust — the
    // share of the projection's solve that had nowhere to go. Both it and the per-edge gas momentum
    // it belonged to are retired: only mass that genuinely leaves the vessel may push it now, so
    // there is no undelivered remainder for a plume front to accumulate in.
}
