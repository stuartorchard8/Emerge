package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlightIntent
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.InputKey
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.ThrusterControl
import org.emerge.demo.outofspace.world.thrusterActivation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flying the ship with the keys, rather than by wiring a motor to a button.
 *
 * The claim under all of this is that **no engine is assigned to an axis anywhere**. Each motor is
 * handed the pilot's intent and its own place on the hull, and works out for itself whether firing
 * would help — so a vessel flies with whatever engines happen to be bolted to it, pointing however
 * they point. That is what makes these tests about geometry and not about a key map.
 */
class FlightControlTest {

    /** The stick, read off the six keys the game already had. */
    @Test
    fun `the held keys are a translation and a spin`() {
        assertEquals(FlightIntent.NONE, FlightIntent.of(0), "an empty hand asks for nothing")
        // Up is forward and y is down, so forward is negative — the same sign the grid uses for
        // everything else.
        assertEquals(-1, FlightIntent.of(InputKey.Up.bit).translateY)
        assertEquals(1, FlightIntent.of(InputKey.Down.bit).translateY)
        assertEquals(-1, FlightIntent.of(InputKey.Left.bit).translateX)
        assertEquals(1, FlightIntent.of(InputKey.Right.bit).translateX)
        // A is counter-clockwise, B is clockwise: clockwise-positive matches the sign a torque
        // takes on a y-down grid.
        assertEquals(-1, FlightIntent.of(InputKey.A.bit).spin)
        assertEquals(1, FlightIntent.of(InputKey.B.bit).spin)
        // Opposing keys cancel rather than fighting, which is what a stick does.
        assertEquals(0, FlightIntent.of(InputKey.Left.bit or InputKey.Right.bit).translateX)
    }

    /**
     * A motor pushing the way the pilot asked runs flat out; one pushing the other way stays shut.
     *
     * Negative and not zero for the wrong-way case, and that matters downstream: an activation is a
     * signed number and the throttle reads anything at or below zero as off, so an engine that would
     * fight the request cannot be dragged back open by a rotation term that happens to like it.
     */
    @Test
    fun `a motor fires for the push it makes and not for the opposite`() {
        val forward = FlightIntent(translateY = -1)
        // On the centre of mass, so nothing but the translation term can be doing anything.
        assertEquals(1000, activation(forward, Direction.Up, 0, 0))
        assertTrue(activation(forward, Direction.Down, 0, 0) < 0, "it fired to push the ship backwards")
        assertEquals(0, activation(forward, Direction.Left, 0, 0), "a beam engine has no view on going forward")
        assertEquals(0, activation(FlightIntent.NONE, Direction.Up, 0, 0), "it fired with nobody at the controls")
    }

    /**
     * A motor off the axis turns the ship, and which way it turns is the whole of what it is asked.
     *
     * Below the centre of mass and pushing to starboard, the lever arm turns the vessel
     * *counter-clockwise*. So Q lights it and E does not — and E's engine is the mirror image of it,
     * which is the arrangement anybody who has built a ship with two side thrusters expects.
     */
    @Test
    fun `an off-axis motor is chosen by which way it turns the ship`() {
        val widdershins = FlightIntent(spin = -1)
        val clockwise = FlightIntent(spin = 1)
        // Four tiles aft of the centre — y is down, so "below" is +4.
        val below = 4 * Rotation.MILLI_TILE

        assertTrue(activation(widdershins, Direction.Right, 0, below) > 0, "it would not help turn to port")
        assertTrue(activation(clockwise, Direction.Right, 0, below) <= 0, "it turned the ship the wrong way")
        // The mirror image, and it must be the mirror image: same place, opposite push.
        assertTrue(activation(clockwise, Direction.Left, 0, below) > 0)
    }

    /**
     * The lever arm is worth something: further out is a bigger share of the same turn.
     *
     * This is the property the port exists for. Without it every engine that could contribute
     * anything to a rotation would contribute the same, and a ship would burn its outriggers and its
     * midships motors at identical rates to turn.
     */
    @Test
    fun `a longer lever arm asks for more thrust`() {
        val spin = FlightIntent(spin = 1)
        val near = activation(spin, Direction.Left, 0, 1 * Rotation.MILLI_TILE)
        val far = activation(spin, Direction.Left, 0, 3 * Rotation.MILLI_TILE)
        assertTrue(near > 0 && far > 0, "neither of them fired, so this compared nothing")
        assertTrue(far > near, "the outrigger is not worth more than the midships motor: $far against $near")
    }

    /**
     * A motor in line with the centre of mass produces nothing worth having, and is left shut.
     *
     * The anti-chatter rule. Its torque is real but tiny, and without a floor every engine on the
     * ship would light for every nudge of the stick and spend propellant cancelling out.
     */
    @Test
    fun `a motor on the spin axis stays shut`() {
        assertEquals(0, activation(FlightIntent(spin = 1), Direction.Left, 0, 0), "a zero lever arm made a torque")
        // Directly ahead and pushing forward: the thrust runs through the centre of mass, so there
        // is no torque in it however far out it sits.
        assertEquals(0, activation(FlightIntent(spin = 1), Direction.Up, 0, -6 * Rotation.MILLI_TILE))
    }

    /** Hands off the keys is engines off — the behaviour that used to be "always on". */
    @Test
    fun `a thruster with nobody at the controls burns nothing`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid))
        val before = controller.state.inTransitMass

        repeat(TICKS) { controller.stepOnce() }

        assertEquals(before, controller.state.inTransitMass, "it burned propellant with nobody flying")
        assertEquals(0L, controller.state.exhaustMomentumX, "and it threw something out of the nozzle")
    }

    /** And a hand on the key is engines on, with no wiring done by anybody. */
    @Test
    fun `holding the key flies the ship with no wiring at all`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid))
        controller.mode = Mode.Flight
        // The motor exhausts to starboard, so it pushes to port: this is the key that asks for that.
        controller.heldKeys = InputKey.Left.bit

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "the nozzle never fired")
        assertTrue(s.velocityX < 0L, "the ship went the way the exhaust did: ${s.velocityX}")
        assertEquals(0L, s.momentumBalanceX, "the flight controls minted momentum")
    }

    /**
     * ⚠️ **The keys only reach the vessel in flight mode.** In build mode the same physical keys pan
     * the camera, and a motor that answered them anyway would fire every time the player scrolled
     * across their own ship.
     */
    @Test
    fun `the stick is dead while building`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid))
        controller.mode = Mode.Build
        controller.heldKeys = InputKey.Left.bit

        repeat(TICKS) { controller.stepOnce() }

        assertEquals(0L, controller.state.exhaustMomentumX, "a motor fired while the player was building")
    }

    /**
     * Switched to the wire, a motor stops listening to the pilot and goes back to being a machine.
     *
     * Both halves are the claim: the stick no longer reaches it, *and* its own wiring — which is
     * still the always-on default every machine is placed with — runs it regardless.
     */
    @Test
    fun `a wire-driven motor ignores the stick and obeys its wiring`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid, ThrusterControl.Wire))
        // Asking for the opposite of what this motor makes, to be sure it is not being heard at all.
        controller.mode = Mode.Flight
        controller.heldKeys = InputKey.Right.bit

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "an ALWAYS-wired motor did not run")
        assertTrue(s.velocityX < 0L, "and it was pushed the wrong way: ${s.velocityX}")
    }

    /**
     * The whole point, end to end: a motor nobody wired, bolted well off the axis, turns the ship
     * when the pilot asks for a turn.
     *
     * Six tiles above midships and pushing to port, this engine's lever arm swings the vessel
     * *counter-clockwise* — so it is Q that lights it, and the ship's angular momentum must come out
     * negative. Nothing here binds a key to a motor; the geometry does all of it.
     */
    @Test
    fun `an off-axis motor turns the ship on the rotate key`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid, row = BAY_Y - 6))
        controller.mode = Mode.Flight
        controller.heldKeys = InputKey.A.bit

        repeat(TICKS) { controller.stepOnce() }

        val s = controller.state
        assertTrue(s.exhaustMomentumX > 0L, "the motor never fired, so this proved nothing")
        assertTrue(s.angImpulse < 0L, "asked to turn to port, the ship turned ${s.angImpulse}")
    }

    /** The mode is a setting, so it has to survive the file. */
    @Test
    fun `a save remembers which motors the pilot flies`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        val wired = Save.read(Save.write(hullWithThruster(grid, ThrusterControl.Wire)))
        val flown = Save.read(Save.write(hullWithThruster(grid, ThrusterControl.Flight)))
        val here = grid.tile(HULL_RIGHT, BAY_Y)
        assertEquals(ThrusterControl.Wire, (wired.deck[here] as Thruster).control)
        assertEquals(ThrusterControl.Flight, (flown.deck[here] as Thruster).control)
    }

    /** [thrusterActivation] with the centre of mass at the origin and the motor placed relative to it. */
    private fun activation(intent: FlightIntent, thrust: Direction, x: Long, y: Long): Int =
        thrusterActivation(intent, thrust, x, y, comX = 0L, comY = 0L)

    /**
     * The same vacuum box `ThrusterTest` flies, with one motor in the starboard wall exhausting out
     * of it — so the ship is pushed to port, and [InputKey.Left] is the key that asks for that.
     */
    private fun hullWithThruster(
        grid: Grid,
        control: ThrusterControl = ThrusterControl.Flight,
        row: Int = BAY_Y,
    ): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        val tile: TileIndex = grid.tile(HULL_RIGHT, row)
        deck -= tile
        deck += Thruster(tile, facing = Direction.Right, control = control)
        return VesselState(
            grid = grid,
            deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(tile, Mixture.of(Species.Water to 4L * Capacity.PACKET_MASS, energy = 0))
    }

    private companion object {
        init { RockSpawner.enabled = false }

        const val TICKS = 20
        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_TOP = 6
        const val HULL_BOTTOM = 26
        const val BAY_Y = 16
    }
}
