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
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.Edit
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.ExhaustPath
import org.emerge.demo.outofspace.world.machine.exhaustPath
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.DeleteLayer
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first engine you can build: propellant in, exhaust out, ship the other way.
 *
 * Four claims, one per case of [org.emerge.demo.outofspace.world.machine.ExhaustPath], plus the ledgers.
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
        val controller = flying(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))

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
        val controller = flying(cfg, hullWithThruster(cfg.initialGrid, Direction.Right).copy(ang = QUARTER_TURN))

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
        val controller = flying(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))
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
        val controller = flying(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))

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
     * it moves through the pressure field like any other
     * pressure rather than by being handed an impulse.
     */
    @Test
    fun `a blocked thruster dumps its exhaust into the tile before the wall`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        // Inside the box, facing the port wall a few tiles away.
        val tile = grid.tile(HULL_LEFT + 4, BAY_Y)
        val controller = flying(cfg, hullWithThruster(grid, Direction.Left, tile))

        val destination = grid.tile(HULL_LEFT + 1, BAY_Y)
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
     * Bolted bell-first against the wall, it fires into its own bell and cooks itself.
     *
     * The limit case of a blocked motor rather than a rule of its own — a thruster is permeable, so
     * its bell is somewhere gas can be, and the walk always has an answer. It burns propellant,
     * produces nothing, and gets very hot, which is a mistake worth being able to make and see.
     *
     * ⚠️ **The chamber is one tile further inboard than it used to be.** A motor is two tiles long
     * now, so "against the wall" is a bell at `HULL_LEFT + 1` and a chamber behind it; the old
     * fixture put the chamber itself in that tile, which would nose the bell into the hull plate and
     * is a machine this build refuses to stand. Same claim, same geometry, one tile of it renamed.
     */
    @Test
    fun `a thruster against a wall exhausts into its own bell`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        // Chamber two tiles inboard of the port wall, facing it: the bell's outer face *is* the wall.
        val at = grid.tile(HULL_LEFT + 2, BAY_Y)
        val bell = grid.tile(HULL_LEFT + 1, BAY_Y)
        val controller = flying(cfg, hullWithThruster(grid, Direction.Left, tile = at))
        val coldEnough = controller.state.air.kelvinAt(bell)

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.inTransitMass < INITIAL_PROPELLANT, "it did not burn anything, so this proved nothing")
        assertEquals(0L, s.exhaustMomentumX, "exhaust that never left the ship pushed the ship")
        assertTrue(
            s.air.kelvinAt(bell) > coldEnough,
            "the motor is sitting in its own exhaust at ${s.air.kelvinAt(bell)}K, up from ${coldEnough}K",
        )
        assertEquals(0L, s.airBalance, "propellant became gas without the air ledger being told")
    }

    /**
     * The bell is **part of the machine**: it is claimed, it is made of metal, and nothing else may
     * stand there.
     *
     * The whole of what changed when a thruster stopped being one tile. Asserted off the occupancy
     * index rather than off the footprint, because occupancy is what every other system asks — a
     * footprint that claimed two tiles while the index only knew about one would be a motor you
     * could build a warehouse through.
     */
    @Test
    fun `a thruster stands on its bell as well as its chamber`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        val at = grid.tile(HULL_RIGHT - 4, BAY_Y)
        val s = hullWithThruster(grid, Direction.Right, tile = at)
        val bell = grid.tile(HULL_RIGHT - 3, BAY_Y)

        val m = s.deck[at] as Thruster
        assertEquals(bell, m.bell(grid), "the bell is the tile in front of the chamber")
        assertEquals(setOf(at, bell), m.tiles(grid).toSet(), "the footprint is those two tiles")
        assertEquals(at, s.occupancy[bell], "the bell does not point back at the machine standing on it")
        assertTrue(s.deck.stuff.massAt(bell) > 0L, "the bell is made of nothing")
        // And the chamber is still where the propellant is put: the store did not move with the size.
        assertEquals(at, bufferTile(grid, m, at, BufferRole.Input), "the input store left the chamber")
    }

    /**
     * A motor cannot be built with its bell through something, or off the rim.
     *
     * The constraint the second tile buys, and the reason it is worth having: an engine costs the
     * deck space its plume needs. Stated at *placement*, where every other footprint rule is, so a
     * player is told by the build refusing rather than by a machine appearing inside a warehouse.
     */
    @Test
    fun `a thruster is refused when its bell has nowhere to go`() {
        val grid = Grid(12, 12)
        var s = bare(grid)
        val blocker = grid.tile(6, 5)
        s = build(s, blocker, DeckMachineKind.Pump, Direction.Right)
        assertNotNull(s.deck[blocker], "the fixture built no pump")

        // Chamber clear, bell on the pump.
        s = build(s, grid.tile(5, 5), DeckMachineKind.Thruster, Direction.Right)
        assertNull(s.deck[grid.tile(5, 5)], "a motor was nosed through a pump")

        // Chamber on the last column, bell off the rim.
        s = build(s, grid.tile(11, 8), DeckMachineKind.Thruster, Direction.Right)
        assertNull(s.deck[grid.tile(11, 8)], "a motor was nosed off the edge of the world")

        // And the same tile, pointing anywhere with room, goes up.
        s = build(s, grid.tile(11, 8), DeckMachineKind.Thruster, Direction.Left)
        assertNotNull(s.deck[grid.tile(11, 8)], "a motor with room for its bell was refused")
    }

    /**
     * Turned round, a motor claims a different second tile — so a turn can be **refused**.
     *
     * The second machine in the game whose rotation can fail, and a different failure from the
     * first: a bridge turned covers a different pair either side of its middle, a thruster turned
     * swings its whole bell from one neighbour to another.
     */
    @Test
    fun `a thruster cannot turn its bell onto an occupied tile`() {
        val grid = Grid(12, 12)
        var s = bare(grid)
        val at = grid.tile(5, 5)
        s = build(s, at, DeckMachineKind.Thruster, Direction.Right)
        assertNotNull(s.deck[at], "the fixture built no motor")
        // Below it, which is where a clockwise turn would put the bell.
        s = build(s, grid.tile(5, 6), DeckMachineKind.Pump, Direction.Right)
        assertNotNull(s.deck[grid.tile(5, 6)], "nothing was in the way, so this proved nothing")

        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(at))))
        assertEquals(
            Direction.Right, (s.deck[at] as? Thruster)?.facing,
            "it turned its bell onto a tile something else is standing on",
        )

        // With the obstruction gone it turns, and its bell moves with it.
        s = run(s, 1, OutofspaceInput(listOf(Edit.Remove(grid.tile(5, 6), DeleteLayer.Deck))))
        s = run(s, 1, OutofspaceInput(listOf(Edit.Rotate(at))))
        assertEquals(Direction.Down, (s.deck[at] as? Thruster)?.facing, "and now it turns")
        assertTrue(!s.occupancy.isFree(grid.tile(5, 6)), "the tile it swung onto is its now")
        assertTrue(s.occupancy.isFree(grid.tile(6, 5)), "and the one it swung off is free again")
    }

    /**
     * The three cases, straight off the map, with no tick of simulation in the way.
     *
     * ⚠️ Asked of the **machine** and not of a tile. The walk starts at the bell, so a test that
     * handed over the chamber would be measuring a ray one tile longer than the one the engine
     * actually makes — and would agree with the sim about every case except the one that matters.
     */
    @Test
    fun `the exhaust path reads the hull`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        val state = hullWithThruster(grid, Direction.Left, tile = grid.tile(HULL_LEFT + 4, BAY_Y))
        val structure = StructureMap.derive(grid, state.deck)
        fun pathOf(x: Int, facing: Direction): ExhaustPath {
            val at = grid.tile(x, BAY_Y)
            return exhaustPath(grid, structure, Thruster(at, facing = facing))
        }

        val blocked = pathOf(HULL_LEFT + 4, Direction.Left)
        assertEquals(grid.tile(HULL_LEFT, BAY_Y), blocked.blocker, "the wall is the blocker")
        assertEquals(grid.tile(HULL_LEFT + 1, BAY_Y), blocked.destination, "the tile before it takes the exhaust")
        assertTrue(!blocked.isClear, "the wall is between it and the rim")

        // Chamber two tiles off the plate, so the bell is against it and the jet has nowhere to go.
        val cornered = pathOf(HULL_LEFT + 2, Direction.Left)
        assertEquals(grid.tile(HULL_LEFT, BAY_Y), cornered.blocker, "the wall is right there")
        assertEquals(
            grid.tile(HULL_LEFT + 1, BAY_Y),
            cornered.destination,
            "with nowhere else, it exhausts into its own bell",
        )

        // Out through the hull tile the fixture replaced, and away: nothing between it and the rim.
        assertTrue(pathOf(HULL_RIGHT - 1, Direction.Right).isClear, "the rim is not a blocker")
    }

    /** A save keeps the tank and the throttle; the exhaust path is derived and so needs no room. */
    @Test
    fun `a save remembers the propellant`() {
        val cfg = OutofspaceConfig()
        val controller = flying(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))
        repeat(4) { controller.stepOnce() }

        val played = controller.state
        val loaded = Save.read(Save.write(played))
        val here = cfg.initialGrid.tile(HULL_RIGHT, BAY_Y)
        val before = played.deck[here] as? Thruster
        val after = loaded.deck[here] as? Thruster
        assertEquals(before, after, "the thruster did not survive the round trip")
        assertEquals(played.ventedMass, loaded.ventedMass)
        assertEquals(played.exhaustMomentumX, loaded.exhaustMomentumX)
    }

    /**
     * A controller with a hand on the stick, holding the key that asks for the push this motor
     * makes.
     *
     * ⚠️ **Every test here needs one now.** A thruster answers the pilot by default
     * ([org.emerge.demo.outofspace.world.machine.ThrusterControl]), so a fixture that only builds
     * one and steps the world builds an engine nobody is asking anything of — which would leave
     * every claim in this file trivially true against a motor that never fired.
     *
     * The key is derived from the machine rather than passed in, so a test that turns the nozzle
     * round does not also have to remember to turn the hand.
     */
    private fun flying(cfg: OutofspaceConfig, state: VesselState): OutofspaceController {
        val thruster = state.grid.tiles.firstNotNullOf { state.deck[it] as? Thruster }
        val controller = OutofspaceController(cfg, state)
        controller.mode = Mode.Flight
        controller.heldKeys = when (thruster.thrust) {
            Direction.Up -> InputKey.Up
            Direction.Down -> InputKey.Down
            Direction.Left -> InputKey.Left
            Direction.Right -> InputKey.Right
        }.bit
        return controller
    }

    /**
     * A box with a fuelled thruster in it, and no air anywhere.
     *
     * Vacuum on purpose. With an atmosphere aboard the hull also rings, and every claim above about
     * a direction or a total would acquire a tolerance — see `FlightTest`'s vacuum fixture for the
     * same trade. What is being measured here is the engine.
     */
    private fun hullWithThruster(grid: Grid, facing: Direction, tile: TileIndex = TileIndex.NONE): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        // Default: chamber *in* the starboard wall, which is how a motor is actually fitted — and
        // its bell one tile outboard of the hull, hanging in space, which is what a nozzle does.
        val tile = if (tile != TileIndex.NONE) tile else grid.tile(HULL_RIGHT, BAY_Y)
        // Mounted *in* the wall, so the plate it replaces comes out first — a tile carries one
        // deck machine, and the motor is the one that is there. Its bell may need a plate out of the
        // way too, since a motor now claims the tile it fires through.
        deck -= tile
        val motor = Thruster(tile, facing = facing)
        for (part in motor.tiles(grid)) if (part != tile) deck -= part
        deck += motor
        return VesselState(
            grid = grid,
                        deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        ).stocked(tile, Mixture.of(Species.Water to INITIAL_PROPELLANT, energy = 0))
    }

    /** A creative-mode world with nothing in it: the shortest way to a machine standing somewhere. */
    private fun bare(grid: Grid): VesselState = VesselState(
        grid = grid,
        deck = DeckArray(grid),
        air = Stuff.gas(MassArray(grid.size)),
        buffers = BufferLayer.empty(grid.size),
        rail = RailLayer.empty(grid.size),
        creative = true,
    )

    private fun build(state: VesselState, tile: TileIndex, kind: DeckMachineKind, facing: Direction): VesselState =
        run(state, 1, OutofspaceInput(listOf(Edit.Place(tile, Brush.Building(kind), facing))))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
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
