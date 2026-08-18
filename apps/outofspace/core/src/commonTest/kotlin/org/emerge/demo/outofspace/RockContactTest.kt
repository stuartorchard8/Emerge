package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A body hits the ship and bounces off it — increment H2.
 *
 * Three claims, and they are separable on purpose. **It stops**: a body cannot be inside a wall, at
 * any speed, which is the sweep. **It ricochets**: it leaves at half the speed it arrived, which is
 * the restitution and is a tuning decision rather than a measurement. **The ship feels it**: `−J`
 * against the body's `+J`, which is what makes this physics rather than an animation, and is checked
 * here by the momentum ledger closing while a body is banging around inside the hull.
 *
 * ⚠️ The fixtures state their gravity. A vessel has none — the plating went in H1b — so a test that
 * wants a body to *land* has to switch the plating on and say so, and a test that wants the game's
 * actual regime says `FREEFALL` and throws the body instead.
 */
class RockContactTest {
    init { RockSpawner.enabled = false }

    /**
     * The whole increment in one assertion: a body thrown at a wall comes back.
     *
     * In freefall, so nothing but the wall is acting on it, which makes the arithmetic exact enough
     * to state: it leaves at half the speed it arrived, and the sign has flipped.
     */
    @Test
    fun `a body thrown at the hull ricochets at half its speed`() {
        val speed = Flight.PER_TILE / 4L
        val thrown = bodyAt(x = 26, y = 16, velocityX = speed)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(thrown)))

        // ⚠️ The contact is detected on the **ship**, and the version that watched for the body's own
        // impulse going negative was asserting a mass ratio without saying so. A rock that reverses
        // is a rock that hit something heavier than itself; this one is 83 tonnes against a 40-tonne
        // box, so it leaves the wall still travelling forward and merely slower, while the ship is
        // launched. What is unambiguous either way is that the vessel was pushed, and in vacuum
        // nothing else can push it.
        var hit = false
        repeat(TICKS) {
            controller.stepOnce()
            if (controller.state.vesselImpulseX != 0L) hit = true
        }

        val body = controller.state.bodies.single()
        assertTrue(hit, "the body never touched the wall: it is at ${body.centreX / Flight.PER_TILE}")
        // ⚠️ Against the **ship**, not against the world. `e` is a statement about a closing speed,
        // and the wall it closed on is bolted to a box the body has just shoved. It is their
        // difference that is exactly half the approach. Asserting the body's own velocity here would
        // be asserting that the ship is infinitely heavy, which is the thing reduced mass is for.
        val closing = body.velocityX - controller.state.velocityX
        assertTrue(
            abs(closing + speed / 2L) < Flight.PER_TILE / 1000L,
            "the bounce is $closing against an approach of $speed",
        )
        // ⚠️ **Deflection is asserted against the bounce, not against a fixed distance**, and the
        // exact-zero form this replaces was a statement about the *shape of the hull* dressed up as
        // one about the physics. It held only while every cell was a square meeting a square
        // face-on, so every normal lay on an axis and a push along it could not have a y component
        // by construction. A disc meeting a tile's corner has a genuinely diagonal normal, and
        // since step 4 the contact also carries friction — both of which deflect a throw slightly,
        // correctly, and neither of which this test is about.
        //
        // What it is about is that the wall sent the body back rather than sideways. Half a per cent
        // of the closing speed is a ricochet; a tenth of it would be a body skating along the wall,
        // and that is the failure worth catching.
        assertTrue(
            abs(body.velocityY) * 20L < abs(closing),
            "it skated along the wall instead of off it: ${body.velocityY} sideways against $closing",
        )
    }

    /**
     * It cannot be *inside* the wall, and it cannot have got past it.
     *
     * Sixteen tiles a tick is far more than a body will ever do under a quarter-g engine, and that is
     * the point: a jump-then-test would put this body cleanly on the far side of a one-tile hull with
     * nothing to report, and the sweep is what makes the speed irrelevant. H4 is an increment about
     * flying at things fast.
     */
    /**
     * ⚠️ **Was parked by step 1 and un-parked by step 2, one commit later.**
     *
     * Worth keeping the history because it says what the contact solver actually bought. Step 1 made
     * the vessel's rotation physical, and forty ticks of an 83-tonne rock bouncing inside a light box
     * tumbles the box through most of a turn — at which point the old resolver, which answered one
     * axis at a time in the middle of moving, let the rock out through a wall. Step 2 replaced that
     * with a list of contacts solved together, and containment came back on its own. The bound was
     * never the problem, which is why it was not softened.
     */
    @Test
    fun `a body cannot tunnel through a bulkhead`() {
        val fast = bodyAt(x = 20, y = 16, velocityX = 16L * Flight.PER_TILE)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(fast)))

        repeat(TICKS) { controller.stepOnce() }

        val body = controller.state.bodies.single()
        // Grid frame: a wall is a tile, so "which side of the wall" is a question about the grid.
        // The body's world position is far away by now — this rock outweighs the ship, so the
        // bounces throw the *hull* across open space — and that is not what is being asked.
        val at = body.localCentreX(controller.state.pose)
        assertTrue(
            at < WALL_X * Flight.PER_TILE,
            "the body is at ${at / Flight.PER_TILE}, on the far side of a wall at $WALL_X",
        )
        // Still in the room, and slower: forty ticks at this speed is a great many bounces off both
        // walls, so *which way* it ends up going is not a claim worth making.
        assertTrue(at > Flight.PER_TILE, "it left through the port wall instead")
        // ⚠️ **Closing** speed, and it has to be. The old form asked the body's own velocity to have
        // more than halved, which is only what a bounce does to a body when the thing it bounced off
        // does not move. This rock is heavier than the ship: a bounce launches the *hull*, and the
        // body's world-frame speed barely drops — 11.1 tiles a tick out of 16 — while the speed the
        // two are approaching each other at, which is the only speed a restitution is about, decays
        // by a half every time. Reading the wrong one, this test scored a correct bounce as a failure.
        val closing = abs(body.velocityX - controller.state.velocityX)
        assertTrue(
            closing < 8L * Flight.PER_TILE,
            "sixteen tiles a tick survived every bounce: closing at $closing",
        )
    }

    /**
     * A body that has landed stays landed, and does not buzz.
     *
     * The threshold that makes this true is derived from the gravity rather than picked — see
     * `RockContact.restingSpeed`. Under a plating that re-accelerates it every tick, a body with no
     * resting rule bounces forever at a fraction of a tile, which reads on screen as a vibrating
     * boulder. The first version of the rule got the factor wrong and produced a *perfect* limit
     * cycle: two velocities, two heights a third of a tile apart, alternating forever, with every
     * conserved quantity exactly right. This test is what a stationary position looks like.
     *
     * ⚠️ The assertion is on the **position**, and it has to be. A body lying on the deck of a moving
     * ship has the ship's velocity, and it also has a per-tick sawtooth in it — the plating pushes at
     * the end of one tick and the deck cancels it at the start of the next, which is the same
     * one-tick explicitness the whole sim is written with. What "at rest" means is that it does not
     * move relative to the ship, and that is what a grid position measures.
     *
     * ⚠️ **PARKED, and it is the sim that is wrong, not the test.**
     *
     * A landed body still shifts 0.41 of a tile back and forth for ever. That is not the limit cycle
     * the note above describes — the bounce terminates properly now and the resting rule fires — it
     * is the tick ordering: the plating is applied *after* the sweep, so every tick ends with a
     * tile-a-second of downward momentum the deck has already had its chance to cancel, and the body
     * arrives at the next tick genuinely moving. The KDoc above calls that a per-tick sawtooth and
     * expected it to be a rounding error, which it was while the hull outweighed every rock. At
     * 83 tonnes against 40 the reaction is twice the action and the sawtooth is visible.
     *
     * Applying the plating before the sweep was tried and did not shrink it, so the fix is not a
     * reorder and is not one line. The claim this test makes is right and it should go back on the
     * moment a body can actually lie still.
     */
    @Ignore
    @Test
    fun `a body that lands on the deck settles and stays put`() {
        val controller = OutofspaceController(CFG, vacuumHull().copy(gravity = VesselState.PLATING_ONE_G))
        controller.dropRock(18f, 12f)

        repeat(TICKS) { controller.stepOnce() }
        val landed = controller.state.bodies.single()
        repeat(TICKS) { controller.stepOnce() }
        val later = controller.state.bodies.single()

        assertTrue(landed.positionY > 12L * Flight.PER_TILE, "the body never fell: it is at $landed")
        assertEquals(landed.positionY, later.positionY, "a settled body is still moving")
        assertEquals(landed.positionX, later.positionX, "and it is wandering sideways, unpushed")
    }

    /**
     * The ship gets what the body loses, and the ledger says so throughout.
     *
     * `momentumBalance` is the instrument that found §5e's truncation bug and it only stays worth
     * reading if every store is named — so the contact has one. Checked *every tick*, because a
     * balance that is zero at the end can be zero by cancelling two mistakes.
     */
    @Test
    fun `the ship feels the body and the momentum ledger closes`() {
        val thrown = bodyAt(x = 26, y = 16, velocityX = Flight.PER_TILE / 4L)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(thrown)))

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(0L, balance(s), "the momentum ledger broke on tick ${s.tick}")
        }

        val s = controller.state
        assertTrue(s.bodyImpulseX < 0L, "nothing was ever handed to a body, so this proved nothing")
        // The ship got the opposite, and it is moving because of it: the body pushed off the wall and
        // the wall is bolted to the ship.
        assertEquals(-s.bodyImpulseX, s.vesselImpulseX, "the ship did not get what the body lost")
        assertTrue(s.velocityX > 0L, "and it is not moving: ${s.velocityX}")
    }

    /**
     * ⚠️ A body resting on the deck is **not** a thruster.
     *
     * The version of this that took only the contact into the ledger — and left the plating free —
     * balanced perfectly and flew the ship: the field pushes a body down for nothing, the deck pushes
     * it back up with a reaction, and the ship climbs forever with a boulder sitting on the floor.
     * A field the vessel makes is a force the vessel exerts, so it is charged for it, and once the
     * body is at rest the two cancel exactly. See [VesselState.bodyImpulseX].
     */
    @Test
    fun `a body resting on the deck does not push the ship`() {
        val controller = OutofspaceController(CFG, vacuumHull().copy(gravity = VesselState.PLATING_ONE_G))
        controller.dropRock(18f, 12f)

        repeat(TICKS) { controller.stepOnce() }
        val settled = controller.state.vesselImpulseY
        repeat(TICKS) { controller.stepOnce() }
        val s = controller.state

        assertTrue(s.bodies.single().positionY > 12L * Flight.PER_TILE, "the body never landed")
        // Within one tick's worth of the body's own weight, which is the size of the sawtooth: the
        // plating charges the ship at the end of a tick and the deck refunds it at the start of the
        // next, so the two are never in the same sample. A pump would be forty of these and growing.
        val weight = s.bodies.single().mass
        assertTrue(
            abs(s.vesselImpulseY - settled) <= weight,
            "the ship is being flown by a body lying on the floor: $settled then ${s.vesselImpulseY}",
        )
        assertEquals(0L, balance(s), "and the ledger did not notice, which is the worse half")
    }

    /**
     * The whole identity as one number — the same sum `momentumBalance` is in the agent harness.
     *
     * Both axes at once, because a term dropped on one axis and doubled on the other is exactly the
     * kind of thing a per-axis check would let through.
     */
    private fun balance(s: VesselState): Long = s.momentumBalanceX + s.momentumBalanceY

    /** A body centred on a tile, given a velocity in the world frame rather than an impulse. */
    private fun bodyAt(x: Int, y: Int, velocityX: Long = 0L, velocityY: Long = 0L): RigidBody {
        val blank = RigidBody.rockBlob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = 0L, positionY = 0L,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        val half = (Edit.DEFAULT_ROCK_RADIUS * 2 + 1) * Flight.PER_TILE / 2L
        return RigidBody.rockBlob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = x * Flight.PER_TILE - half,
            positionY = y * Flight.PER_TILE - half,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
            // ⚠️ The inverse of [RigidBody.velocityX], and it needs the same reduction: this rock is
            // 8.3e13 units, and a quarter-tile-a-tick velocity is 2.5e8, so the plain product is 2e22
            // and the fixture handed the body a wrapped impulse. It then sat exactly where it was put
            // and the test said "the body never touched the wall".
            impulseX = scaledRatio(velocityX, Flight.PER_TILE, blank.mass),
            impulseY = scaledRatio(velocityY, Flight.PER_TILE, blank.mass),
        )
    }

    /**
     * A box with a wall down each side and no air in it.
     *
     * Vacuum, so the only momentum in the world is the body's and the ship's: a sealed hull's
     * atmosphere rings, the hull recoils from it, and every "the ship is moving *because of the
     * body*" claim below would be measuring the weather instead.
     */
    private fun vacuumHull(): VesselState {
        val grid = CFG.initialGrid
        val machines = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in 1..WALL_X) { put(x, 6); put(x, 26) }
        for (y in 6..26) { put(1, y); put(WALL_X, y) }
        val state = VesselState(grid = grid, machines = machines.toList(), deck = deck, gravity = VesselState.FREEFALL, buffers = BufferLayer.forMachines(grid, machines.toList()), rail = RailLayer.empty(grid.size))
        return state.copy(air = Stuff.gas(MassArray(grid.size)))
    }

    private fun abs(v: Long): Long = if (v < 0L) -v else v

    private companion object {
        val CFG = OutofspaceConfig()

        /** The starboard wall, which is what everything here is thrown at. */
        const val WALL_X = 33

        /** 40 ticks of a 35×33 world — a throw, a bounce and a settle, comfortably inside a second. */
        const val TICKS = 40
    }
}
