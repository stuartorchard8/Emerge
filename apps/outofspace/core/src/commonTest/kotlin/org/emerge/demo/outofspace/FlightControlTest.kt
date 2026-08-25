package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlightIntent
import org.emerge.demo.outofspace.world.Motor
import org.emerge.demo.outofspace.world.Sas
import org.emerge.demo.outofspace.world.flightActivations
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
        assertEquals(-FlightIntent.FULL, FlightIntent.of(InputKey.A.bit).spin)
        assertEquals(FlightIntent.FULL, FlightIntent.of(InputKey.B.bit).spin)
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
        val widdershins = FlightIntent(spin = -FlightIntent.FULL)
        val clockwise = FlightIntent(spin = FlightIntent.FULL)
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
        val spin = FlightIntent(spin = FlightIntent.FULL)
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
        assertEquals(0, activation(FlightIntent(spin = FlightIntent.FULL), Direction.Left, 0, 0), "a zero lever arm made a torque")
        // Directly ahead and pushing forward: the thrust runs through the centre of mass, so there
        // is no torque in it however far out it sits.
        assertEquals(0, activation(FlightIntent(spin = FlightIntent.FULL), Direction.Up, 0, -6 * Rotation.MILLI_TILE))
    }

    // ── The whole-ship balance ────────────────────────────────────────────────

    /**
     * **The claim this feature is for.** An asymmetric ship asked to go forward fires the two
     * rearward motors and nothing else, at whatever pair of throttles keeps it straight.
     *
     * Three engines: two pushing forward, one either side of the centre of mass at *different* lever
     * arms, and one pushing to port well aft. The desired motion is available from the forward pair
     * alone, so the answer must be the forward pair alone — the lateral engine stays cold, because
     * every gram it burned would be a gram spent pushing the ship sideways so that it could be
     * pushed back.
     *
     * And the pair must be at **different** throttles. Equal ones would twist the ship: the far
     * motor has the longer arm, so it is the one throttled back, to exactly the ratio that cancels
     * the near one's torque.
     */
    @Test
    fun `a forward burn uses only the motors that can make it, at matched throttles`() {
        val near = Motor(Direction.Up, leverX = 2 * Rotation.MILLI_TILE, leverY = 0, massPerTick = RATE)
        val far = Motor(Direction.Up, leverX = -5 * Rotation.MILLI_TILE, leverY = 0, massPerTick = RATE)
        val lateral = Motor(Direction.Left, leverX = 0, leverY = 7 * Rotation.MILLI_TILE, massPerTick = RATE)

        val plan = flightActivations(FlightIntent(translateY = -1), listOf(near, far, lateral))

        assertTrue(plan[0] > 0 && plan[1] > 0, "a forward motor sat out a forward burn: ${plan.toList()}")
        assertEquals(0, plan[2], "a beam engine burned propellant on a straight-ahead request")
        assertTrue(plan[0] != plan[1], "both forward motors ran at ${plan[0]}, which twists the ship")
        // Zero net torque is the actual requirement; the throttles are how it is met.
        assertEquals(
            0L,
            plan[0].toLong() * near.cross + plan[1].toLong() * far.cross,
            "the burn is not straight: ${plan.toList()}",
        )
        // The motor with the longer arm is the one that gives way, since it has the more authority.
        assertTrue(plan[1] < plan[0], "the outrigger was not the one throttled back: ${plan.toList()}")
    }

    /**
     * A ship whose forward motors are already balanced is not throttled at all.
     *
     * The balance must be a *repair* and not a tax. Two engines symmetric about the centre of mass
     * make no net torque between them, so both run flat out and the ship gets everything it has.
     */
    @Test
    fun `a symmetric pair is left alone`() {
        val port = Motor(Direction.Up, leverX = -4 * Rotation.MILLI_TILE, leverY = 0, massPerTick = RATE)
        val starboard = Motor(Direction.Up, leverX = 4 * Rotation.MILLI_TILE, leverY = 0, massPerTick = RATE)

        val plan = flightActivations(FlightIntent(translateY = -1), listOf(port, starboard))

        assertEquals(FlightIntent.FULL, plan[0])
        assertEquals(FlightIntent.FULL, plan[1])
    }

    /**
     * ⚠️ A lone off-axis motor fires anyway, and wallows.
     *
     * There is nothing to balance it against, so the choice is between a ship that translates while
     * turning and a ship that cannot translate. It translates. [Sas] is what cleans up afterwards,
     * and only if the player has switched it on.
     */
    @Test
    fun `an unbalanceable motor still flies the ship`() {
        val lonely = Motor(Direction.Up, leverX = 3 * Rotation.MILLI_TILE, leverY = 0, massPerTick = RATE)
        val plan = flightActivations(FlightIntent(translateY = -1), listOf(lonely))
        assertEquals(FlightIntent.FULL, plan[0], "it refused to move the ship at all")
    }

    /** A turn is a request for net torque, so the balance must keep its hands off it. */
    @Test
    fun `a rotation request is not balanced away`() {
        val port = Motor(Direction.Up, leverX = -4 * Rotation.MILLI_TILE, leverY = 0, massPerTick = RATE)
        val starboard = Motor(Direction.Up, leverX = 4 * Rotation.MILLI_TILE, leverY = 0, massPerTick = RATE)

        val plan = flightActivations(FlightIntent(spin = FlightIntent.FULL), listOf(port, starboard))

        assertTrue(plan[0] != plan[1], "the two motors cancelled, so the ship cannot turn")
        assertTrue(plan[0] > 0 || plan[1] > 0, "neither motor fired for the turn")
    }

    /**
     * The same claim again, but through the reducer on a ship that exists: an asymmetric hull with
     * two rearward motors at unequal arms and one beam motor, asked to go forward.
     *
     * The unit test above proves the arithmetic; this proves the arithmetic is what the game runs.
     * The measurement is **propellant actually gone from each motor's tank**, which is the only
     * version of "no fuel is wasted" a player can check.
     */
    @Test
    fun `an asymmetric ship goes forward on its rearward motors alone`() {
        val cfg = OutofspaceConfig()
        val grid = cfg.initialGrid
        val near = grid.tile(MIDSHIPS + 2, HULL_BOTTOM)
        val far = grid.tile(MIDSHIPS - 5, HULL_BOTTOM)
        val beam = grid.tile(HULL_RIGHT, BAY_Y + 4)
        val controller = OutofspaceController(cfg, threeMotorShip(grid, near, far, beam))
        controller.mode = Mode.Flight
        controller.heldKeys = InputKey.Up.bit

        val before = listOf(near, far, beam).map { tank(controller.state, it) }
        repeat(TICKS) { controller.stepOnce() }
        val after = listOf(near, far, beam).map { tank(controller.state, it) }
        val burned = before.zip(after) { b, a -> b - a }

        assertTrue(burned[0] > 0L && burned[1] > 0L, "a rearward motor sat out a forward burn: $burned")
        assertEquals(0L, burned[2], "the beam motor burned $burned[2] on a straight-ahead request")
        assertTrue(burned[0] != burned[1], "both ran at one throttle, which twists the ship: $burned")
        assertTrue(burned[1] < burned[0], "the outrigger was not the one throttled back: $burned")
        // Straight enough that the autopilot would not even wake up for it.
        assertTrue(
            abs(controller.state.angVel) <= Sas.DEADBAND,
            "the burn twisted the ship to ${controller.state.angVel}, past a deadband of ${Sas.DEADBAND}",
        )
        assertTrue(controller.state.velocityY < 0L, "and after all that it did not go forward")
    }

    // ── The autopilot ─────────────────────────────────────────────────────────

    /** SAS leans on the stick against the way the ship is already turning, and lets go when it stops. */
    @Test
    fun `the autopilot asks for the opposite of the spin it sees`() {
        assertEquals(0, Sas.correction(0L), "it fought a ship that was not turning")
        assertEquals(0, Sas.correction(Sas.DEADBAND), "it burned propellant inside the deadband")
        assertTrue(Sas.correction(Sas.FULL_AUTHORITY / 2L) < 0, "a clockwise drift needs a widdershins push")
        assertTrue(Sas.correction(-Sas.FULL_AUTHORITY / 2L) > 0)
        // Proportional below full authority, saturated above it — a tumble gets everything there is.
        assertTrue(Sas.correction(-Sas.FULL_AUTHORITY / 4L) < Sas.correction(-Sas.FULL_AUTHORITY / 2L))
        assertEquals(FlightIntent.FULL, Sas.correction(-Sas.FULL_AUTHORITY * 9L))
    }

    /**
     * End to end: a ship left spinning is brought to a stop and then let alone.
     *
     * Both halves matter. A controller that stops the spin and goes on burning is worse than none —
     * it is a fuel leak that looks like a feature — so the settled state is asserted as well as the
     * arrival at it.
     */
    @Test
    fun `the autopilot stops a spin and then stops burning`() {
        val cfg = OutofspaceConfig()
        val spun = turningShip(cfg.initialGrid)
        val controller = OutofspaceController(cfg, spun.copy(sas = true))
        controller.mode = Mode.Flight

        val opening = abs(controller.state.angVel)
        repeat(200) { controller.stepOnce() }
        val settled = abs(controller.state.angVel)
        assertTrue(settled <= Sas.DEADBAND, "it was still turning at $settled, having started at $opening")
        // Into the deadband and not *past* it: a controller that overshot would be swinging the ship
        // back and forth and burning propellant at both ends of every swing.
        assertTrue(settled < opening, "it did not slow down at all")

        val spentBySettling = controller.state.ventedMass
        repeat(200) { controller.stepOnce() }
        assertEquals(
            spentBySettling,
            controller.state.ventedMass,
            "the autopilot went on burning after the ship was still",
        )
    }

    /** And with it off, the same ship goes on turning. */
    @Test
    fun `a spin stands until somebody stops it`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, turningShip(cfg.initialGrid))
        controller.mode = Mode.Flight

        val opening = abs(controller.state.angVel)
        repeat(200) { controller.stepOnce() }

        assertEquals(opening, abs(controller.state.angVel), "something stopped the ship with SAS off")
        assertEquals(0L, controller.state.ventedMass, "and it burned propellant to do it")
    }

    /**
     * The switch survives the tick that follows it.
     *
     * ⚠️ **Its own test, because the save round-trip below cannot see this.** The reducer works on a
     * scratch copy of the world and writes back a named list of fields; a switch missing from that
     * list is set, used for the rest of the tick, and silently thrown away at the end of it. Caught
     * exactly that way — the toggle read as working right up until the next tick undid it.
     */
    @Test
    fun `the autopilot switch survives the tick`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid))
        assertTrue(!controller.state.sas, "it started on")

        controller.toggleSas()
        repeat(4) { controller.stepOnce() }
        assertTrue(controller.state.sas, "the switch was thrown away by the tick that applied it")

        controller.toggleSas()
        repeat(4) { controller.stepOnce() }
        assertTrue(!controller.state.sas, "and it would not switch back off")
    }

    /** The switch is a fact about the vessel, so it has to survive the file. */
    @Test
    fun `a save remembers the autopilot`() {
        val cfg = OutofspaceConfig()
        val on = hullWithThruster(cfg.initialGrid).copy(sas = true)
        assertTrue(Save.read(Save.write(on)).sas)
        assertTrue(!Save.read(Save.write(hullWithThruster(cfg.initialGrid))).sas)
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
     * Eight tiles above midships and pushing to port, this engine's lever arm swings the vessel
     * *counter-clockwise* — so it is Q that lights it, and the ship's angular momentum must come out
     * negative. Nothing here binds a key to a motor; the geometry does all of it.
     *
     * ⚠️ **It used to be six, and six is no longer far enough off the axis.** A motor's lever arm is
     * measured to its bell, which moved a tile outboard when a thruster grew one — so this engine is
     * now very slightly *more* nearly in line with the centre of mass than it was, and at six tiles
     * that put it a fifth of a percent the wrong side of [rotationTerm]'s anti-chatter floor
     * (`3|cross| > lever`). The floor is doing exactly its job; the fixture was sitting on it. Moved
     * out to eight so the test measures the claim in its title rather than the threshold.
     */
    @Test
    fun `an off-axis motor turns the ship on the rotate key`() {
        val cfg = OutofspaceConfig()
        val controller = OutofspaceController(cfg, hullWithThruster(cfg.initialGrid, row = BAY_Y - 8))
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
        // Two tiles long: the chamber in the starboard plate and the bell one step outboard of it,
        // hanging in space. The plate under the chamber comes out; there is never one under a bell.
        deck += Thruster(tile, facing = Direction.Right, control = control)
        return VesselState(
            grid = grid,
            deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(tile, Mixture.of(Species.Water to 4L * Capacity.PACKET_MASS, energy = 0))
    }

    /**
     * The same box with motors either side of midships, left turning: what the autopilot is for.
     *
     * Wound up by hand through `angImpulse` rather than by an off-balance burn, so the test starts
     * from a known spin and measures only the recovery.
     */
    private fun turningShip(grid: Grid): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        val bays = listOf(BAY_Y - 7, BAY_Y + 7)
        for (y in bays) {
            deck -= grid.tile(HULL_RIGHT, y)
            deck += Thruster(grid.tile(HULL_RIGHT, y), facing = Direction.Right)
        }
        val state = VesselState(
            grid = grid,
            deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        for (y in bays) state.stocked(
            grid.tile(HULL_RIGHT, y),
            Mixture.of(Species.Water to 40L * Capacity.PACKET_MASS, energy = 0),
        )
        return state.copy(angImpulse = SPUN)
    }

    /** What is left in one motor's propellant tank. */
    private fun tank(state: VesselState, tile: TileIndex): Long =
        state.inStore(tile, BufferRole.Input)?.total ?: 0L

    /** A box with two motors in the stern wall at unequal arms, and one in the starboard wall. */
    private fun threeMotorShip(grid: Grid, near: TileIndex, far: TileIndex, beam: TileIndex): VesselState {
        val deck = DeckArray(grid)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in HULL_LEFT..HULL_RIGHT) { put(x, HULL_TOP); put(x, HULL_BOTTOM) }
        for (y in HULL_TOP..HULL_BOTTOM) { put(HULL_LEFT, y); put(HULL_RIGHT, y) }
        // Sternward motors exhaust down, so they push the ship up the screen: forward.
        for (tile in listOf(near, far)) { deck -= tile; deck += Thruster(tile, facing = Direction.Down) }
        deck -= beam
        deck += Thruster(beam, facing = Direction.Right)
        val state = VesselState(
            grid = grid,
            deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        for (tile in listOf(near, far, beam)) state.stocked(
            tile,
            Mixture.of(Species.Water to 8L * Capacity.PACKET_MASS, energy = 0),
        )
        return state
    }

    private companion object {
        init { RockSpawner.enabled = false }

        /** Every motor in the unit tests throws the same, so only geometry decides the balance. */
        val RATE = Capacity.PACKET_MASS / 30L

        /**
         * A brisk but recoverable tumble to hand the autopilot.
         *
         * Measured: this ship's two motors null it into the deadband in about 140 ticks, monotonically
         * and without ever overshooting into a spin the other way.
         */
        const val SPUN = 100_000_000_000_000L

        const val TICKS = 20
        const val HULL_LEFT = 1
        const val HULL_RIGHT = 33
        const val HULL_TOP = 6
        const val HULL_BOTTOM = 26
        const val BAY_Y = 16

        /** The column the hull is symmetric about, so a motor's offset from it is its lever arm. */
        const val MIDSHIPS = (HULL_LEFT + HULL_RIGHT) / 2
    }
}

private fun abs(v: Long): Long = if (v < 0L) -v else v
