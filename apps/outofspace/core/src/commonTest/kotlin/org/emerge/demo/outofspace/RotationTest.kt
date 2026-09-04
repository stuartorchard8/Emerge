package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.angularVelocity
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.tileCentre
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.demo.outofspace.world.torqueAbout
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.world.spinSpeed
import org.emerge.demo.outofspace.num.scaledRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.starterWorld

/**
 * Step 2 of `PLAN_trig_free_rotation.md`: the vessel has an orientation, and what pushes it off
 * centre turns it.
 *
 * The three burn cases are the ones the plan named, and the order matters. A wrong lever arm still
 * spins an unbalanced ship and still leaves a balanced one straight — both of those pass on an
 * implementation that books torque about the *grid origin* instead of the centre of mass. The
 * centreline case is the one that does not: it is zero only if the arm is measured from the point
 * the ship actually turns about.
 *
 * Vacuum throughout, for the reason `ThrusterTest` gives. With air aboard the hull rings and every
 * exact zero here becomes a tolerance.
 */
class RotationTest {

    // ── The mass distribution the torques are measured against ────────────────

    /**
     * A symmetric box has its centre of mass in the middle of itself, exactly.
     *
     * Exactly, not nearly: everything below that asserts a zero torque is really asserting this,
     * and a centre that was half a millitile out would turn all of them into near-misses that a
     * tolerance would then be invented to hide.
     */
    @Test
    fun `a symmetric hull has its centre of mass at its centre`() {
        val grid = OutofspaceConfig().initialGrid
        val d = box(grid).distribution

        assertEquals(tileCentre((HULL_LEFT + HULL_RIGHT) / 2), d.comMilliX, "centre of mass, x")
        assertEquals(tileCentre((HULL_TOP + HULL_BOTTOM) / 2), d.comMilliY, "centre of mass, y")
        assertTrue(d.mass > 0L, "an empty hull cannot be measured")
        // A hollow box's mass is at its walls, so the radius of gyration is a good fraction of the
        // box. Bounded either side rather than pinned, because the exact figure is a consequence of
        // the fill fractions and would be a magic number the moment one of those moved.
        val kSq = d.gyrationSq / Rotation.GYRATION_SCALE
        assertTrue(kSq in 50L..400L, "a 33x21 box should have k² of order a hundred tile², not $kSq")
    }

    /**
     * The centre of mass as a **position** agrees with it as a **radius**, and carries more digits.
     *
     * Both halves matter. Agreement is what makes it one centre and not two: the grid is placed
     * about [MassDistribution.comX] and every torque is booked about [MassDistribution.comMilliX],
     * so a disagreement bigger than the millitile they differ by would be the ship turning about a
     * point it is not drawn about.
     *
     * The extra digits are the whole reason the field exists. Measured on the starter vessel, one
     * 100 kg packet moving one tile shifts the centre by 1.04 millitiles — so a grid hung off the
     * radius would move in whole-millitile snaps and lose everything finer. If this ever comes back
     * exactly `comMilliX · PER_MILLI_TILE` on a real vessel then the precision is decorative and
     * `PLAN_com_anchored_frames.md` has lost its premise.
     */
    @Test
    fun `the centre of mass is one point at two scales`() {
        val d = starterWorld(OutofspaceConfig().initialGrid).distribution

        assertEquals(d.comMilliX, d.comX / Rotation.PER_MILLI_TILE, "the two x centres disagree")
        assertEquals(d.comMilliY, d.comY / Rotation.PER_MILLI_TILE, "the two y centres disagree")

        // Sub-millitile digits on at least one axis — a centre that landed exactly on a millitile
        // boundary on both would be a coincidence, and on a real vessel it is not one that happens.
        val subX = d.comX % Rotation.PER_MILLI_TILE
        val subY = d.comY % Rotation.PER_MILLI_TILE
        assertTrue(
            subX != 0L || subY != 0L,
            "the position centre carries no digits the radius does not: $subX, $subY",
        )
    }

    /** `τ = rₓF_y − r_yF_x`, with the arm measured from the centre and not from anywhere else. */
    @Test
    fun `torque is the arm crossed with the force`() {
        val about = MassDistribution(mass = 1_000L, comMilliX = 10_000L, comMilliY = 10_000L, gyrationSq = 0L)

        // Two tiles to starboard of centre, pushed sternward: a positive (clockwise, +y down) twist.
        assertEquals(2L * 7L, torqueAbout(about, atX = 12_000L, atY = 10_000L, impulseX = 0L, impulseY = 7L))
        // The same push applied *at* the centre does nothing at all, whatever its size.
        assertEquals(0L, torqueAbout(about, atX = 10_000L, atY = 10_000L, impulseX = 0L, impulseY = 7L))
        // And a force pointing straight at the centre is all arm and no lever.
        assertEquals(0L, torqueAbout(about, atX = 12_000L, atY = 10_000L, impulseX = 5L, impulseY = 0L))
    }

    /** `ω = L/I`, and in particular a ship with no spin has no spin. */
    @Test
    fun `angular velocity is the angular momentum over the inertia`() {
        val d = box(OutofspaceConfig().initialGrid).distribution

        assertEquals(0L, angularVelocity(0L, d), "no angular momentum is no rotation")
        assertEquals(0L, angularVelocity(1_000L, MassDistribution.EMPTY), "nothing cannot spin")

        // Sign follows the torque, and twice the momentum is twice the rate. Stated as a relation
        // rather than a figure: the figure depends on the fill fractions, which are a dial.
        val one = angularVelocity(ONE_SPIN, d)
        assertTrue(one > 0L, "a positive angular momentum must give a positive rate, not $one")
        assertEquals(-one, angularVelocity(-ONE_SPIN, d), "the two directions must be mirror images")
        assertTrue(angularVelocity(2L * ONE_SPIN, d) > one, "twice the momentum must spin faster")
    }

    // ── The three burn cases ──────────────────────────────────────────────────

    /**
     * **The case a wrong arm still passes every other test on.** A single motor on the centreline
     * pushes the ship dead ahead and does not turn it — at any throttle, on any tick of the burn.
     *
     * Asserted every tick rather than at the end, because the throttle is what varies: the tank
     * drains, the chunk the thruster gets changes size, and the cargo aboard shifts the centre of
     * mass with it. A torque that is zero only for one chunk size is not zero.
     */
    @Test
    fun `a thruster on the centreline never turns the ship`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, box(cfg.initialGrid, BAY_Y))

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(0L, s.netTorque, "tick ${s.tick}: a centreline motor twisted the ship")
            assertEquals(0L, s.angImpulse, "tick ${s.tick}: and the twist accumulated")
            assertEquals(0, s.ang.raw, "tick ${s.tick}: and it turned")
        }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "nothing was ever fired, so this proved nothing")
        assertTrue(s.velocityX < 0L, "and it did have to go somewhere: ${s.velocityX}")
    }

    /** A motor bolted well off the centreline spins the ship, in the direction its arm says. */
    @Test
        fun `an off-centre thruster spins the ship`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, box(cfg.initialGrid, BAY_HIGH))

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "nothing was ever fired, so this proved nothing")
        // The nozzle is *above* the centre and throws to +x, so the ship keeps −x there: a push
        // sternward applied high is an anticlockwise twist with +y pointing down the screen.
        assertTrue(s.netTorque < 0L, "an off-centre burn booked no twist: ${s.netTorque}")
        assertTrue(s.angImpulse < 0L, "and none of it accumulated: ${s.angImpulse}")
        assertTrue(s.angVel < 0L, "and the ship is not turning: ${s.angVel}")
        assertTrue(s.ang.raw < 0, "and it never got anywhere: ${s.ang.raw}")
    }

    /**
     * Two of the same motor, straddling the centreline, cancel — exactly, and not merely nearly.
     *
     * This is the pair that makes the feature worth having: linearly they are indistinguishable
     * from one motor of twice the size, and rotationally they are the difference between a ship
     * that flies straight and one that cartwheels.
     */
    @Test
    fun `a balanced pair of thrusters does not spin the ship`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, box(cfg.initialGrid, BAY_HIGH, BAY_LOW))

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(0L, s.netTorque, "tick ${s.tick}: a balanced pair twisted the ship")
            assertEquals(0, s.ang.raw, "tick ${s.tick}: and it turned")
        }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "neither motor ever fired, so this proved nothing")
        assertEquals(0L, s.angVel, "a ship with no angular momentum cannot be turning")
    }

    // ── The angular ledger ────────────────────────────────────────────────────

    /**
     * **The tripwire.** A burn spins the ship and the exhaust carries off exactly the counterpart,
     * so the angular identity closes — every tick, exactly, not nearly.
     *
     * This is the angular twin of `momentumBalance` and deliberately **not** built the same way.
     * The linear ledger carries a term for the gas aboard, and because that field is spent by no
     * physics, counting it lets the identity close over momentum that can never move anything:
     * measured, a sealed starter vessel with a pressure pocket and nothing vented accelerates from
     * 0.0058 to 0.0142 tiles/tick while `momentumBalance` reads zero throughout. So
     * [VesselState.angularBalance] states the identity over the ship alone. See its note.
     *
     * Vacuum, like everything else here — which is what makes the zero exact and is also precisely
     * why this passes while the sealed-with-air case below does not.
     */
    @Test
    fun `a burn leaves the angular ledger closed`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, box(cfg.initialGrid, BAY_HIGH))

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(0L, s.angularBalance, "tick ${s.tick}: the ship was spun by nothing")
        }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "nothing was ever fired, so this proved nothing")
        assertTrue(s.angImpulse < 0L, "and the ship never span, so the zero above was trivial")
        assertEquals(
            -s.angImpulse, s.exhaustAngImpulse,
            "the plume must be carrying exactly what the ship kept",
        )
    }

    /**
     * A balanced pair spins nothing and so throws nothing away either — both halves zero, which is
     * a different statement from the two cancelling and worth its own line.
     */
    @Test
    fun `a balanced pair leaves both angular stores empty`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, box(cfg.initialGrid, BAY_HIGH, BAY_LOW))
        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "neither motor ever fired, so this proved nothing")
        assertEquals(0L, s.angImpulse, "a balanced pair turned the ship")
        assertEquals(0L, s.exhaustAngImpulse, "and its plume carried a twist off with it")
        assertEquals(0L, s.angularBalance)
    }

    /** The two stores are a save's business like every other ledger term. */
    @Test
    fun `the angular stores survive a save round trip`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, box(cfg.initialGrid, BAY_HIGH))
        repeat(TICKS) { controller.stepOnce() }

        val played = controller.state
        assertTrue(played.exhaustAngImpulse != 0L, "the fixture threw nothing, so this proves nothing")

        val loaded = Save.read(Save.write(played))
        assertEquals(played.exhaustAngImpulse, loaded.exhaustAngImpulse, "what the exhaust took")
        assertEquals(played.bodyAngImpulse, loaded.bodyAngImpulse, "what the rocks took")
        assertEquals(played.angularBalance, loaded.angularBalance, "and so the identity itself")
    }

    /** A save written before the angular ledger existed loads with both stores empty. */
    @Test
    fun `a save with no angular stores line loads with empty stores`() {
        val cfg = OutofspaceConfig()
        val text = Save.write(box(cfg.initialGrid, BAY_HIGH))
        val without = text.lineSequence().filterNot { it.startsWith("angularstores ") }.joinToString("\n")
        assertTrue(without.length < text.length, "the line was never written, so nothing was removed")

        val loaded = Save.read(without)
        assertEquals(0L, loaded.exhaustAngImpulse)
        assertEquals(0L, loaded.bodyAngImpulse)
    }

    /**
     * ⛔ **PARKED, and red on today's code — this is the bug the ledger above was built to see.**
     *
     * A ship with air aboard and **no way out for any of it** spins itself up, for free, for ever.
     * `applyPressureForce` hands the hull its share of every
     * blocked face and writes the gas's share into `momentum`, a field spent by no physics. The
     * hull's shares telescope to zero as a *force* and do **not** as a torque — equal pushes on
     * opposite bulkheads cancel as force and add as twist, because they act at different points.
     *
     * Diagnosed off a live save turning at 3.75 rev/s on an empty tank: 13,235,440 booked every
     * eighth tick, same sign, `sas` off, and 4.6x the ship's entire rotational authority so no
     * amount of propellant could have held attitude against it.
     *
     * Returned by the step that stops booking the hull for exchanges that never happen — hull
     * reaction only where mass genuinely crosses the vessel boundary. See `PLAN_grid_vs_
     * continuous.md` and [VesselState.angularBalance].
     */
    @Test
    fun `a sealed ship does not spin itself up on its own air`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, sealedWithPocket(cfg.initialGrid))

        repeat(200) { controller.stepOnce() }

        val s = controller.state
        assertEquals(0L, s.airVentedMass, "gas left the ship, so this is not the sealed case")
        assertEquals(0L, s.exhaustAngImpulse, "a motor fired, so this is not the sealed case")
        assertEquals(0L, s.angularBalance, "a sealed ship was spun by its own atmosphere")
    }

    /** An orientation and a spin that did not survive a save is a ship that straightens up on load. */
    @Test
    fun `rotation survives a save round trip`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, box(cfg.initialGrid, BAY_HIGH))
        repeat(TICKS) { controller.stepOnce() }

        val played = controller.state
        assertTrue(played.ang.raw != 0, "the fixture never turned, so the round trip proves nothing")

        val loaded = Save.read(Save.write(played))
        assertEquals(played.ang, loaded.ang, "orientation")
        assertEquals(played.angImpulse, loaded.angImpulse, "angular momentum")
        assertEquals(played.netTorque, loaded.netTorque, "this tick's torque")
    }

    /**
     * A **body's** orientation survives a save too — step 3, and the reason for save v16.
     *
     * Written as its own test rather than folded into the one above because it is a different pair
     * of fields on a different object, and because a body's two new columns sit at the end of a line
     * that already had ten: an off-by-one there reads a shape as an angle and would show up as a
     * rock that loads spinning, which is exactly the kind of thing a round trip is for.
     */
    @Test
    fun `a body's orientation survives a save round trip`() {
        val cfg = OutofspaceConfig()
        val turning = RigidBody.rockBlob(
            radius = 2,
            positionX = 12L * Flight.PER_TILE, positionY = 9L * Flight.PER_TILE,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        ).copy(ang = Coord(123_456_789), angImpulse = -987_654_321_000L)

        val loaded = Save.read(Save.write(box(cfg.initialGrid, BAY_HIGH).copy(bodies = listOf(turning))))

        val body = loaded.bodies.single()
        assertEquals(turning.ang, body.ang, "which way the rock is facing")
        assertEquals(turning.angImpulse, body.angImpulse, "and how fast it is turning")
    }

    /** A save written before rotation existed loads as a ship pointing forward and not turning. */
    @Test
    fun `a save with no rotation line loads straight and still`() {
        val cfg = OutofspaceConfig()
        val text = Save.write(box(cfg.initialGrid, BAY_HIGH))
        val without = text.lineSequence().filterNot { it.startsWith("rotation ") }.joinToString("\n")
        assertTrue(without.length < text.length, "the line was never written, so nothing was removed")

        val loaded = Save.read(without)
        assertEquals(0, loaded.ang.raw)
        assertEquals(0L, loaded.angImpulse)
        assertEquals(0L, loaded.netTorque)
    }

    // ── Centrifugal, which nothing implements ────────────────────────────────

    /**
     * **A body aboard a spinning ship drifts outward, and there is no centrifugal force anywhere.**
     *
     * This is what artificial gravity by rotation costs to build: nothing. A body's position and
     * momentum are both written in the **world**, and the world frame is inertial — so a rock that
     * nothing is pushing travels in a straight line while the grid turns underneath it, and *that*
     * is the outward spiral. Centrifugal and Coriolis are what you need when you insist on writing
     * the motion in the rotating frame; written in the world they are not omitted, they never arise.
     *
     * ⛔ **So a centrifugal term must never be added for bodies.** It would double-count against the
     * spiral this asserts, which is the same mistake as
     * `PLAN_trig_free_rotation.md`'s rejected `platingGravity.rotateBy(forward)`. What genuinely
     * does need one is the **gas**, because gas is addressed by tile and so really does live in the
     * rotating frame.
     *
     * Freefall, so the plating cannot be what moves it.
     */
    @Test
    fun `a body aboard a spinning ship drifts outward on its own`() {
        val cfg = OutofspaceConfig()
        val turning = box(cfg.initialGrid).copy(angImpulse = ONE_SPIN * 40L)
        val axisX = turning.distribution.comMilliX * (Flight.PER_TILE / Rotation.MILLI_TILE)
        val axisY = turning.distribution.comMilliY * (Flight.PER_TILE / Rotation.MILLI_TILE)
        val placed = RigidBody.rockBlob(
            radius = 1,
            positionX = 24L * Flight.PER_TILE, positionY = 16L * Flight.PER_TILE,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        // ⚠️ **At rest relative to the DECK, not to the world**, and that is the whole setup. A body
        // at rest in the *world* seen from a turning grid goes round the axis at a constant radius —
        // it has no radial speed, so of course it does not climb. What falls outward in a centrifuge
        // is what was already going round with it: give the rock the deck's own `ω × r` and then let
        // go, and a straight line in the world is a line that leaves the circle it started on.
        val armX = placed.centreX - axisX
        val armY = placed.centreY - axisY
        val spin = angularVelocity(turning.angImpulse, turning.distribution)
        // ⚠️ Through [scaledRatio], not `v * mass / PER_TILE`. An 83-tonne rock at a hundredth of a
        // tile a tick is 5.8e19 written the obvious way round and a `Long` stops at 9.2e18, so the
        // product wraps and the body comes out barely moving — which reads as "the fixture is fine
        // and the sim is broken". The rescale's standing lesson, met once more.
        fun impulseFor(velocity: Long): Long {
            val magnitude = scaledRatio(placed.mass, Flight.PER_TILE, if (velocity < 0L) -velocity else velocity)
            return if (velocity < 0L) -magnitude else magnitude
        }
        val spun = turning.copy(
            bodies = listOf(
                placed.copy(
                    impulseX = impulseFor(-spinSpeed(spin, armY)),
                    impulseY = impulseFor(spinSpeed(spin, armX)),
                ),
            ),
        )
        val controller = OutofspaceController(cfg, spun)

        // ⚠️ Reduced to millitiles before squaring. `localCentreX` is at [Flight.PER_TILE] to the
        // tile, so an arm of four tiles squares to 1.6e19 and a `Long` stops at 9.2e18 — the trap
        // `PLAN_rigid_bodies.md` §5 names, met here on the first attempt.
        fun radius(): Long {
            val s = controller.state
            val body = s.bodies.single()
            val perMilli = Flight.PER_TILE / Rotation.MILLI_TILE
            val dx = body.localCentreX(s.pose) / perMilli - s.distribution.comMilliX
            val dy = body.localCentreY(s.pose) / perMilli - s.distribution.comMilliY
            return isqrt(dx * dx + dy * dy)
        }

        val started = radius()
        assertTrue(controller.state.angVel != 0L, "the ship is not turning, so this proves nothing")
        val perMilli = Flight.PER_TILE / Rotation.MILLI_TILE
        repeat(200) { controller.stepOnce() }

        assertTrue(
            controller.state.bodies.size == 1,
            "the body left the world, so the radius below means nothing",
        )
        // ⚠️ **Against the closed form, not against "bigger".** A body released from a circle
        // travels a straight line, so after `t` ticks its radius is `r·√(1 + (ωt)²)` exactly — and
        // that is worth asserting rather than a direction, because "it moved outward" also passes
        // on a body being flung out by a bug. `ω` here is the ship's spin in radians per tick.
        val turned = controller.state.ang.raw.toLong() - 0L
        val omegaT = turned.toDouble() / Rotation.RAW_PER_RADIAN
        val predicted = (started.toDouble() * kotlin.math.sqrt(1.0 + omegaT * omegaT)).toLong()
        val ended = radius()
        assertTrue(
            ended > started,
            "a body at rest on the deck of a spinning ship stayed at the same radius " +
                "($started -> $ended) — nothing in this game will ever spin for gravity",
        )
        val slack = predicted / 100L
        assertTrue(
            ended > predicted - slack && ended < predicted + slack,
            "the body climbed, but not the way a released one does: $started -> $ended against a " +
                "predicted $predicted after $omegaT radians of ship rotation",
        )
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    /**
     * The same vacuum box `ThrusterTest` uses, with a motor in the starboard wall at each of [bays].
     *
     * Symmetric about both axes with no bays at all, which is what makes the centre of mass land on
     * a known point and the zeros above exact.
     */
    private fun box(grid: Grid, vararg bays: Int): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        for (y in bays) {
            // The bay replaces the plate that was there — see `ThrusterTest`'s fixture.
            deck -= grid.tile(HULL_RIGHT, y)
            // ⚠️ **On the wire, not the stick.** What is measured here is the torque a burn books,
            // so the motors have to burn unconditionally — a flight-controlled engine would first
            // ask whether firing serves the pilot, and these tests have no pilot. See
            // [ThrusterControl].
            deck += Thruster(grid.tile(HULL_RIGHT, y), facing = Direction.Right, control = ThrusterControl.Wire)
        }
        return VesselState(
            grid = grid,
                        deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).also { state ->
            for (y in bays) state.stocked(
                grid.tile(HULL_RIGHT, y),
                Mixture.of(Species.Water to INITIAL_PROPELLANT, energy = 0).atAmbient(),
            )
        }
    }

    /**
     * The same box, sealed, with the air piled into one corner instead of spread evenly.
     *
     * The pile is the whole fixture: uniform air books no torque at all, because the pushes on
     * opposite bulkheads are then equal *and* symmetrically placed. It takes a gradient to
     * separate them, and a gradient is what any ship that has ever run a machine has.
     */
    private fun sealedWithPocket(grid: Grid): VesselState {
        val masses = MassArray(grid.size)
        for (y in HULL_TOP + 1 until HULL_BOTTOM) {
            for (x in HULL_LEFT + 1 until HULL_RIGHT) {
                // Twenty tiles' worth crammed into the one corner tile, ambient everywhere else.
                val share = if (x == HULL_LEFT + 1 && y == HULL_TOP + 1) 20L else 1L
                for (f in Fluid.ALL) masses[MassIndex(grid.tile(x, y), f)] = Stuff.AMBIENT_AIR[f.species] * share
            }
        }
        return box(grid).copy(air = Stuff.gas(masses))
    }

    private companion object {
        init { RockSpawner.enabled = false }

        const val TICKS = 20

        val INITIAL_PROPELLANT = 4L * Capacity.PACKET_MASS

        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_TOP = 6
        const val HULL_BOTTOM = 26

        /** Midships: the row the centre of mass sits on, so a motor here has no arm. */
        const val BAY_Y = 16

        /** Five rows either side of midships — far enough that a truncated arm could not hide it. */
        const val BAY_HIGH = 11
        const val BAY_LOW = 21

        /**
         * An angular momentum big enough that the divisions in [angularVelocity] do not round it
         * away, chosen against the hull's own mass rather than as a bare literal: a tile of hull
         * carried one tile² per tick.
         */
        const val ONE_SPIN = 400_000_000_000L
    }
}
