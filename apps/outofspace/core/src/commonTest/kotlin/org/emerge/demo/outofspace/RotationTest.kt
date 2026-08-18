package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.angularVelocity
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.tileCentre
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.demo.outofspace.world.torqueAbout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        assertEquals(tileCentre((HULL_LEFT + HULL_RIGHT) / 2), d.comX, "centre of mass, x")
        assertEquals(tileCentre((HULL_TOP + HULL_BOTTOM) / 2), d.comY, "centre of mass, y")
        assertTrue(d.mass > 0L, "an empty hull cannot be measured")
        // A hollow box's mass is at its walls, so the radius of gyration is a good fraction of the
        // box. Bounded either side rather than pinned, because the exact figure is a consequence of
        // the fill fractions and would be a magic number the moment one of those moved.
        val kSq = d.gyrationSq / Rotation.GYRATION_SCALE
        assertTrue(kSq in 50L..400L, "a 33x21 box should have k² of order a hundred tile², not $kSq")
    }

    /** `τ = rₓF_y − r_yF_x`, with the arm measured from the centre and not from anywhere else. */
    @Test
    fun `torque is the arm crossed with the force`() {
        val about = MassDistribution(mass = 1_000L, comX = 10_000L, comY = 10_000L, gyrationSq = 0L)

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

    // ── Fixture ───────────────────────────────────────────────────────────────

    /**
     * The same vacuum box `ThrusterTest` uses, with a motor in the starboard wall at each of [bays].
     *
     * Symmetric about both axes with no bays at all, which is what makes the centre of mass land on
     * a known point and the zeros above exact.
     */
    private fun box(grid: Grid, vararg bays: Int): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        for (y in bays) {
            // The bay replaces the plate that was there — see `ThrusterTest`'s fixture.
            deck -= grid.tile(HULL_RIGHT, y)
            machines[grid.tile(HULL_RIGHT, y).index] = Thruster(facing = Direction.Right)
        }
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forMachines(grid, machines.toList()), rail = RailLayer.empty(grid.size),
        ).also { state ->
            // Propellant is a store now, so it is put in after the state stands the stores up.
            for (y in bays) state.stocked(
                grid.tile(HULL_RIGHT, y),
                Resource(Form.Ore, Mixture.of(Species.Water to INITIAL_PROPELLANT, energy = 0)),
            )
        }
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
