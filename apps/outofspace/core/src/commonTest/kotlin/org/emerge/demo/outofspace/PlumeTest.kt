package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.Thruster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a burning engine tells the renderer and the speakers — see
 * [org.emerge.demo.outofspace.world.Plume].
 *
 * It is presentation, so none of it can be checked by a conservation identity; what can be checked
 * is that it describes **the burn that actually happened** and only that. Three ways for it to be
 * wrong, one test each: reporting a burn that did not occur, reporting the wrong burn, and going on
 * reporting one after the motor shut.
 */
class PlumeTest {

    /** A motor throwing into open space: one plume, at the nozzle, pointed the way the gas went. */
    @Test
    fun `a firing thruster reports a plume at its bell`() {
        val cfg = OutofspaceConfig()
        val state = hullWithThruster(cfg.initialGrid, Direction.Right)
        val controller = flying(cfg, state)

        controller.stepOnce()

        val plume = controller.state.plumes.single()
        val motor = state.grid.tiles.firstNotNullOf { state.deck[it] as? Thruster }
        assertEquals(motor.bell(state.grid), plume.bell, "a plume starts at the nozzle, not the chamber")
        assertEquals(Direction.Right, plume.facing, "the exhaust goes the way the motor faces")
        assertTrue(plume.clear, "the bell hangs outboard of the hull, so this one reaches the rim")
        assertTrue(plume.mass > 0L, "a plume with no mass in it is a burn that did not happen")
    }

    /**
     * The numbers on it are the ones the *impulse* was booked from.
     *
     * ⚠️ This is the assertion with something to say. A plume that merely appeared where a motor was
     * firing would satisfy the test above while carrying an arbitrary velocity, and every visual and
     * every noise downstream is a function of these three numbers. So each is pinned against the
     * thing the sim computed it from rather than against a number typed here: the speed against
     * [Thruster.exhaustVelocity] on the propellant that was loaded, and the mass against what the
     * motor's own rate allows.
     */
    @Test
    fun `the plume carries the velocity the propellant was priced at`() {
        val cfg = OutofspaceConfig()
        val loaded = Mixture.of(Species.Water to INITIAL_PROPELLANT, energy = 0).atAmbient()
        val state = hullWithThruster(cfg.initialGrid, Direction.Right)
        val motor = state.grid.tiles.firstNotNullOf { state.deck[it] as? Thruster }
        val controller = flying(cfg, state)

        controller.stepOnce()

        val plume = controller.state.plumes.single()
        assertEquals(
            Thruster.exhaustVelocity(loaded),
            plume.metresPerSecond,
            "the plume is priced off some other parcel than the one in the chamber",
        )
        assertEquals(
            Species.Water,
            plume.mixture.dominant,
            "the exhaust is made of what was loaded, and nothing else was",
        )
        // In vacuum there is nothing to entrain, so what left is exactly what the motor threw: its
        // rate at full activation, and no more. A plume reporting `massPerTick` regardless of what
        // the store held would pass every other line here.
        assertEquals(
            motor.massPerTick,
            plume.mass,
            "a full-throttle tick throws the motor's rate, and a vacuum adds nothing to it",
        )
        assertTrue(plume.kelvin > 0, "propellant at no temperature has no exhaust velocity either")
    }

    /**
     * And it stops. A plume is one tick's worth, so the tick after the pilot lets go has none —
     * otherwise a ship that had ever burned would roar for the rest of the session.
     */
    @Test
    fun `letting go clears the plume`() {
        val cfg = OutofspaceConfig()
        val controller = flying(cfg, hullWithThruster(cfg.initialGrid, Direction.Right))
        controller.stepOnce()
        assertTrue(controller.state.plumes.isNotEmpty(), "nothing was burning, so this proves nothing")

        controller.heldKeys = 0
        controller.stepOnce()

        assertTrue(controller.state.plumes.isEmpty(), "the motor is shut and the exhaust is still there")
    }

    /**
     * A motor bolted bell-first against a wall still plumes, and says it is blocked.
     *
     * ⛔ **Not silence.** It burns, it makes no thrust, and it cooks the tile in front of it — which
     * is a thing a player has to be able to see they have built. [org.emerge.demo.outofspace.world.Plume.clear]
     * is how a picture of it knows to be a stub rather than a jet.
     */
    @Test
    fun `a blocked motor plumes with no reach`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        // Two tiles in from the port wall, facing it: chamber at HULL_LEFT+2, bell at HULL_LEFT+1,
        // and the plate at HULL_LEFT directly in front of the nozzle. Nothing is added to the
        // fixture — the box it is already in is the wall.
        val controller = flying(cfg, hullWithThruster(grid, Direction.Left, tile = grid.tile(HULL_LEFT + 2, BAY_Y)))

        controller.stepOnce()

        val plume = controller.state.plumes.single()
        assertTrue(!plume.clear, "the jet has a wall one tile in front of it")
        assertEquals(0, plume.reach, "nothing was crossed: the blocker is against the nozzle")
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────
    //
    // ThrusterTest's, narrowed: a box in vacuum with one fuelled motor in its starboard wall. Vacuum
    // for the reason that file gives — with air aboard the jet entrains the room and `mass` acquires
    // a tolerance, which is exactly the number being pinned above.

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

    private fun hullWithThruster(grid: Grid, facing: Direction, tile: TileIndex = TileIndex.NONE): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        val at = if (tile != TileIndex.NONE) tile else grid.tile(HULL_RIGHT, BAY_Y)
        deck -= at
        val motor = Thruster(at, facing = facing)
        for (part in motor.tiles(grid)) if (part != at) deck -= part
        deck += motor
        return VesselState(
            grid = grid,
            deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(at, Mixture.of(Species.Water to INITIAL_PROPELLANT, energy = 0).atAmbient())
    }

    private companion object {
        init { RockSpawner.enabled = false }

        val INITIAL_PROPELLANT = 4L * Capacity.PACKET_MASS

        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_TOP = 6
        const val HULL_BOTTOM = 26
        const val BAY_Y = 16
    }
}
