package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.Thruster
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.exhaustPath
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first engine you can build: propellant in, exhaust out, ship the other way.
 *
 * Four claims, one per case of [org.emerge.demo.outofspace.world.ExhaustPath], plus the ledgers.
 * The ledgers are the reason most of this file exists — a thruster spends solid mass, mints gas,
 * moves atmosphere and hands momentum overboard, which is four of the world's conservation
 * identities touched by one machine, and the whole design turns on none of them noticing.
 */
class ThrusterTest {

    /**
     * A motor in the hull, firing into vacuum: the ship goes the *other* way and keeps going.
     *
     * Direction is the assertion. A sign error here still produces a moving ship, so "which way"
     * is the only version of this question worth asking — the same reason `FlightTest` checks a
     * breach that way.
     */
    @Test
    fun `a clear thruster pushes the ship the other way`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "nothing left the nozzle, so this proved nothing")
        assertTrue(s.velocityX < 0L, "exhaust went +x, so the ship must go −x, not ${s.velocityX}")
        assertTrue(s.positionX < 0L, "and it must have got somewhere: ${s.positionX}")
        assertEquals(0L, s.debugImpulseX, "the debug engine was not supposed to be involved")
    }

    /**
     * The same motor on a ship that has been turned: the push turns with it.
     *
     * A thruster's nozzle is bolted to the hull, so what it produces is a direction *in the ship*.
     * The ship's momentum is a direction *in the world*. Nothing in the grid changes when the ship
     * turns — the exhaust still leaves through the same face of the same tile — so the whole of the
     * difference is the one conversion between those two frames, and this is the test that there is
     * one. Turned a quarter turn clockwise (+y is down, so +ang is clockwise), the ship's +x is the
     * world's +y: an engine that pushed to port now pushes up.
     *
     * The linear half only. Torque is a scalar and reads the same in both frames, which is why the
     * spin was already right and only this was wrong.
     */
    @Test
    fun `a turned ship is pushed the way it is pointing`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(
            cfg,
            hullWithThruster(cfg.initialGrid, Direction.Right).copy(ang = QUARTER_TURN),
        )

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumY != 0L, "nothing left the nozzle, so this proved nothing")
        assertTrue(s.velocityY < 0L, "exhaust went to world +y, so the ship must go −y, not ${s.velocityY}")
        // The unturned run puts everything on x; a quarter turn must leave nothing worth measuring
        // there. Relative, not absolute: what matters is that the axes swapped, not the speed.
        assertTrue(
            abs(s.velocityX) * 20L < abs(s.velocityY),
            "it is still being pushed along the grid: ${s.velocityX} against ${s.velocityY}",
        )
        assertTrue(s.positionY < 0L, "and it must have got somewhere: ${s.positionY}")
    }

    /**
     * And it pays for it. Propellant is spent, and spent propellant is *gone* — booked overboard on
     * the same store a vent uses, so `massBalance` does not move while the engine burns.
     */
    @Test
    fun `firing spends propellant and the mass ledger stays closed`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))
        val before = controller.state.inTransitMass
        // The fixture hands the ship a full tank rather than mining it, so the balance starts at
        // whatever was handed over rather than at zero. What must be true is that it does not
        // *move*: every unit that leaves the tank has to appear overboard on the same tick.
        val opening = controller.state.let { it.inTransitMass + it.ventedMass - it.extractedMass }

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(
                opening,
                s.inTransitMass + s.ventedMass - s.extractedMass,
                "tick ${s.tick}: the mass ledger moved while the engine was burning",
            )
        }

        val s = controller.state
        assertTrue(s.inTransitMass < before, "the tank is as full as it started: $before")
        assertEquals(before - s.inTransitMass, s.ventedMass, "what left the tank is not what went overboard")
    }

    /**
     * The momentum ledger, over a burn.
     *
     * A thruster adds **no term** to it: the `+p` booked to the exhaust is the same number as the
     * `−p` handed to the ship, written from one expression. If that ever stops being true this is
     * where it shows, on the tick it first happens rather than as a slow drift in a thrust readout.
     */
    @Test
    fun `the momentum ledger balances through a burn`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(
                0L,
                s.momentumBalanceX,
                "tick ${s.tick}: ship ${s.vesselImpulseX}, exhaust ${s.exhaustMomentumX}",
            )
        }
        assertTrue(controller.state.exhaustMomentumX > 0L, "nothing was ever thrown, so this proved nothing")
    }

    /**
     * Pointed at your own bulkhead: no direct push, a very hot tile, and the air ledger intact.
     *
     * The exhaust pushes the ship's own wall, so the pair cancels and `exhaustMomentum` stays at
     * zero — the ship still moves, because a tile holding a jet leans on everything around it, but
     * it moves through [org.emerge.demo.outofspace.world.applyPressureForce] like any other
     * pressure rather than by being handed an impulse.
     */
    @Test
    fun `a blocked thruster dumps its exhaust into the tile before the wall`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        // Inside the box, facing the port wall a few tiles away.
        val at = grid.index(HULL_LEFT + 4, BAY_Y)
        val controller = OutofspaceController(cfg, hullWithThruster(grid, Direction.Left, at = at))

        val destination = grid.index(HULL_LEFT + 1, BAY_Y)
        val coldEnough = controller.state.air.kelvinAt(destination)
        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertEquals(0L, s.exhaustMomentumX, "a blocked motor threw something out of the world")
        assertEquals(0L, s.exhaustMomentumY)
        assertTrue(s.inTransitMass < INITIAL_PROPELLANT, "it did not burn anything, so this proved nothing")
        // Measured: about 412 K after twenty ticks, and rising — a rocket firing into a wall is
        // supposed to be catastrophic. Pinned as "much hotter than it was" and not as a number,
        // because the number is [Thruster.EXHAUST_METRES_PER_SECOND] squared and that is a dial.
        assertTrue(
            s.air.kelvinAt(destination) > coldEnough * 4 / 3,
            "the exhaust landed at ${s.air.kelvinAt(destination)}K, having started at ${coldEnough}K",
        )
        assertEquals(0L, s.airBalance, "propellant became gas without the air ledger being told")
    }

    /**
     * Bolted face-first against the wall, it fires into its own tile and cooks itself.
     *
     * The limit case of a blocked motor rather than a rule of its own — a thruster is permeable, so
     * its own tile is somewhere gas can be, and the walk always has an answer. It burns propellant,
     * produces nothing, and gets very hot, which is a mistake worth being able to make and see.
     */
    @Test
    fun `a thruster against a wall exhausts into its own tile`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        // Immediately inboard of the port wall, facing it: the exit face *is* the wall.
        val at = grid.index(HULL_LEFT + 1, BAY_Y)
        val controller = OutofspaceController(cfg, hullWithThruster(grid, Direction.Left, at = at))
        val coldEnough = controller.state.air.kelvinAt(at)

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.inTransitMass < INITIAL_PROPELLANT, "it did not burn anything, so this proved nothing")
        assertEquals(0L, s.exhaustMomentumX, "exhaust that never left the ship pushed the ship")
        assertTrue(
            s.air.kelvinAt(at) > coldEnough,
            "the motor is sitting in its own exhaust at ${s.air.kelvinAt(at)}K, up from ${coldEnough}K",
        )
        assertEquals(0L, s.airBalance, "propellant became gas without the air ledger being told")
    }

    /** The three cases, straight off the map, with no tick of simulation in the way. */
    @Test
    fun `the exhaust path reads the hull`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        val state = hullWithThruster(grid, Direction.Left, at = grid.index(HULL_LEFT + 4, BAY_Y))
        val structure = StructureMap.derive(grid, state.machines)

        val blocked = exhaustPath(grid, structure, grid.index(HULL_LEFT + 4, BAY_Y), Direction.Left)
        assertEquals(grid.index(HULL_LEFT, BAY_Y), blocked.blocker, "the wall is the blocker")
        assertEquals(grid.index(HULL_LEFT + 1, BAY_Y), blocked.destination, "the tile before it takes the exhaust")
        assertTrue(!blocked.isClear, "the wall is between it and the rim")

        val against = grid.index(HULL_LEFT + 1, BAY_Y)
        val cornered = exhaustPath(grid, structure, against, Direction.Left)
        assertEquals(grid.index(HULL_LEFT, BAY_Y), cornered.blocker, "the wall is right there")
        assertEquals(against, cornered.destination, "with nowhere else, it exhausts into itself")

        // Out through the hull tile the fixture replaced, and away: nothing between it and the rim.
        val clear = exhaustPath(grid, structure, grid.index(HULL_RIGHT, BAY_Y), Direction.Right)
        assertTrue(clear.isClear, "outboard of the hull is the rim, and the rim is not a blocker")
    }

    /** A save keeps the tank and the throttle; the exhaust path is derived and so needs no room. */
    @Test
    fun `a save remembers the propellant`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))
        repeat(4) { controller.stepOnce() }

        val played = controller.state
        val loaded = Save.read(Save.write(played))
        val here = cfg.initialGrid.index(HULL_RIGHT, BAY_Y)
        val before = played.machines[here] as Thruster
        val after = loaded.machines[here] as Thruster
        assertEquals(before, after, "the thruster did not survive the round trip")
        assertEquals(played.ventedMass, loaded.ventedMass)
        assertEquals(played.exhaustMomentumX, loaded.exhaustMomentumX)
    }

    /**
     * A box with a fuelled thruster in it, and no air anywhere.
     *
     * Vacuum on purpose. With an atmosphere aboard the hull also rings, and every claim above about
     * a direction or a total would acquire a tolerance — see `FlightTest`'s vacuum fixture for the
     * same trade. What is being measured here is the engine.
     */
    private fun hullWithThruster(grid: Grid, facing: Direction, at: Int = -1): VesselState {
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        // Default: mounted *in* the starboard wall, which is how a motor is actually fitted.
        val tile = if (at >= 0) at else grid.index(HULL_RIGHT, BAY_Y)
        machines[tile] = Thruster(
            facing = facing,
            input = Resource(Form.Ore, Mixture.of(Species.Water to INITIAL_PROPELLANT)),
        )
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            air = AirField.of(LongArray(grid.size * Species.COUNT)),
        )
    }

    private companion object {
        init { RockSpawner.enabled = false }

        /** Twenty ticks is five seconds of burn and about a second of test. */
        const val TICKS = 20

        /** Four belt-loads: enough that no case here can run the tank dry mid-run. */
        val INITIAL_PROPELLANT = 4L * Capacity.PACKET_MASS

        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_TOP = 6
        const val HULL_BOTTOM = 26

        /** Midships, as far from a corner as the box allows. */
        const val BAY_Y = 16

        /** A quarter turn clockwise: a half turn is `Int.MAX_VALUE`, so this is half of that. */
        val QUARTER_TURN = Coord(Int.MAX_VALUE / 2)
    }
}

private fun abs(v: Long): Long = if (v < 0L) -v else v
