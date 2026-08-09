package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.VesselState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rock hits the ship and bounces off it — increment H2.
 *
 * Three claims, and they are separable on purpose. **It stops**: a rock cannot be inside a wall, at
 * any speed, which is the sweep. **It ricochets**: it leaves at half the speed it arrived, which is
 * the restitution and is a tuning decision rather than a measurement. **The ship feels it**: `−J`
 * against the rock's `+J`, which is what makes this physics rather than an animation, and is checked
 * here by the momentum ledger closing while a rock is banging around inside the hull.
 *
 * ⚠️ The fixtures state their gravity. A vessel has none — the plating went in H1b — so a test that
 * wants a rock to *land* has to switch the plating on and say so, and a test that wants the game's
 * actual regime says `FREEFALL` and throws the rock instead.
 */
class RockContactTest {
    init { RockSpawner.enabled = false }

    /**
     * The whole increment in one assertion: a rock thrown at a wall comes back.
     *
     * In freefall, so nothing but the wall is acting on it, which makes the arithmetic exact enough
     * to state: it leaves at half the speed it arrived, and the sign has flipped.
     */
    @Test
    fun `a rock thrown at the hull ricochets at half its speed`() {
        val speed = Flight.PER_TILE / 4L
        val thrown = rockAt(x = 26, y = 16, velocityX = speed)
        val controller = OutofspaceController(CFG, vacuumHull().copy(rocks = listOf(thrown)))

        var hit = false
        repeat(TICKS) {
            controller.stepOnce()
            if (controller.state.rocks.single().impulseX < 0L) hit = true
        }

        val rock = controller.state.rocks.single()
        assertTrue(hit, "the rock never touched the wall: it is at ${rock.centreX / Flight.PER_TILE}")
        // ⚠️ Against the **ship**, not against the world. `e` is a statement about a closing speed,
        // and the wall it closed on is bolted to a 208kg box that the rock has just shoved: the
        // rock leaves at a seventh of a tile a tick and the ship at a third of one, and it is their
        // difference that is exactly half the approach. Asserting the rock's own velocity here would
        // be asserting that the ship is infinitely heavy, which is the thing reduced mass is for.
        val closing = rock.velocityX - controller.state.velocityX
        assertTrue(
            abs(closing + speed / 2L) < Flight.PER_TILE / 1000L,
            "the bounce is $closing against an approach of $speed",
        )
        assertEquals(0L, rock.velocityY, "a frictionless normal impulse moved it sideways")
    }

    /**
     * It cannot be *inside* the wall, and it cannot have got past it.
     *
     * Sixteen tiles a tick is far more than a rock will ever do under a quarter-g engine, and that is
     * the point: a jump-then-test would put this rock cleanly on the far side of a one-tile hull with
     * nothing to report, and the sweep is what makes the speed irrelevant. H4 is an increment about
     * flying at things fast.
     */
    @Test
    fun `a rock cannot tunnel through a bulkhead`() {
        val fast = rockAt(x = 20, y = 16, velocityX = 16L * Flight.PER_TILE)
        val controller = OutofspaceController(CFG, vacuumHull().copy(rocks = listOf(fast)))

        repeat(TICKS) { controller.stepOnce() }

        val rock = controller.state.rocks.single()
        assertTrue(
            rock.centreX < WALL_X * Flight.PER_TILE,
            "the rock is at ${rock.centreX / Flight.PER_TILE}, on the far side of a wall at $WALL_X",
        )
        // Still in the room, and slower: forty ticks at this speed is a great many bounces off both
        // walls, so *which way* it ends up going is not a claim worth making. That it has lost most
        // of its speed to them is.
        assertTrue(rock.centreX > Flight.PER_TILE, "it left through the port wall instead")
        assertTrue(
            abs(rock.velocityX) < 8L * Flight.PER_TILE,
            "sixteen tiles a tick survived every bounce: ${rock.velocityX}",
        )
    }

    /**
     * A rock that has landed stays landed, and does not buzz.
     *
     * The threshold that makes this true is derived from the gravity rather than picked — see
     * `RockContact.restingSpeed`. Under a plating that re-accelerates it every tick, a rock with no
     * resting rule bounces forever at a fraction of a tile, which reads on screen as a vibrating
     * boulder. The first version of the rule got the factor wrong and produced a *perfect* limit
     * cycle: two velocities, two heights a third of a tile apart, alternating forever, with every
     * conserved quantity exactly right. This test is what a stationary position looks like.
     *
     * ⚠️ The assertion is on the **position**, and it has to be. A rock lying on the deck of a moving
     * ship has the ship's velocity, and it also has a per-tick sawtooth in it — the plating pushes at
     * the end of one tick and the deck cancels it at the start of the next, which is the same
     * one-tick explicitness the whole sim is written with. What "at rest" means is that it does not
     * move relative to the ship, and that is what a grid position measures.
     */
    @Test
    fun `a rock that lands on the deck settles and stays put`() {
        val controller = OutofspaceController(CFG, vacuumHull().copy(gravity = VesselState.PLATING_ONE_G))
        controller.dropRock(18f, 12f)

        repeat(TICKS) { controller.stepOnce() }
        val landed = controller.state.rocks.single()
        repeat(TICKS) { controller.stepOnce() }
        val later = controller.state.rocks.single()

        assertTrue(landed.positionY > 12L * Flight.PER_TILE, "the rock never fell: it is at $landed")
        assertEquals(landed.positionY, later.positionY, "a settled rock is still moving")
        assertEquals(landed.positionX, later.positionX, "and it is wandering sideways, unpushed")
    }

    /**
     * The ship gets what the rock loses, and the ledger says so throughout.
     *
     * `momentumBalance` is the instrument that found §5e's truncation bug and it only stays worth
     * reading if every store is named — so the contact has one. Checked *every tick*, because a
     * balance that is zero at the end can be zero by cancelling two mistakes.
     */
    @Test
    fun `the ship feels the rock and the momentum ledger closes`() {
        val thrown = rockAt(x = 26, y = 16, velocityX = Flight.PER_TILE / 4L)
        val controller = OutofspaceController(CFG, vacuumHull().copy(rocks = listOf(thrown)))

        repeat(TICKS) {
            controller.stepOnce()
            val s = controller.state
            assertEquals(0L, balance(s), "the momentum ledger broke on tick ${s.tick}")
        }

        val s = controller.state
        assertTrue(s.rockImpulseX < 0L, "nothing was ever handed to a rock, so this proved nothing")
        // The ship got the opposite, and it is moving because of it: the rock pushed off the wall and
        // the wall is bolted to the ship.
        assertEquals(-s.rockImpulseX, s.vesselImpulseX, "the ship did not get what the rock lost")
        assertTrue(s.velocityX > 0L, "and it is not moving: ${s.velocityX}")
    }

    /**
     * ⚠️ A rock resting on the deck is **not** a thruster.
     *
     * The version of this that took only the contact into the ledger — and left the plating free —
     * balanced perfectly and flew the ship: the field pushes a rock down for nothing, the deck pushes
     * it back up with a reaction, and the ship climbs forever with a boulder sitting on the floor.
     * A field the vessel makes is a force the vessel exerts, so it is charged for it, and once the
     * rock is at rest the two cancel exactly. See [VesselState.rockImpulseX].
     */
    @Test
    fun `a rock resting on the deck does not push the ship`() {
        val controller = OutofspaceController(CFG, vacuumHull().copy(gravity = VesselState.PLATING_ONE_G))
        controller.dropRock(18f, 12f)

        repeat(TICKS) { controller.stepOnce() }
        val settled = controller.state.vesselImpulseY
        repeat(TICKS) { controller.stepOnce() }
        val s = controller.state

        assertTrue(s.rocks.single().positionY > 12L * Flight.PER_TILE, "the rock never landed")
        // Within one tick's worth of the rock's own weight, which is the size of the sawtooth: the
        // plating charges the ship at the end of a tick and the deck refunds it at the start of the
        // next, so the two are never in the same sample. A pump would be forty of these and growing.
        val weight = s.rocks.single().massGrams
        assertTrue(
            abs(s.vesselImpulseY - settled) <= weight,
            "the ship is being flown by a rock lying on the floor: $settled then ${s.vesselImpulseY}",
        )
        assertEquals(0L, balance(s), "and the ledger did not notice, which is the worse half")
    }

    /**
     * The whole identity as one number — the same sum `momentumBalance` is in the agent harness.
     *
     * Both axes at once, because a term dropped on one axis and doubled on the other is exactly the
     * kind of thing a per-axis check would let through.
     */
    private fun balance(s: VesselState): Long =
        s.vesselImpulseX + s.momentum.totalX + s.pipeMomentum.totalX + s.exhaustMomentumX +
            s.undeliveredImpulseX + s.rockImpulseX - s.debugImpulseX +
            s.vesselImpulseY + s.momentum.totalY + s.pipeMomentum.totalY + s.exhaustMomentumY +
            s.undeliveredImpulseY + s.rockImpulseY - s.debugImpulseY

    /** A rock centred on a tile, given a velocity in the world frame rather than an impulse. */
    private fun rockAt(x: Int, y: Int, velocityX: Long = 0L, velocityY: Long = 0L): Rock {
        val blank = Rock.blob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = 0L, positionY = 0L,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        val half = (Edit.DEFAULT_ROCK_RADIUS * 2 + 1) * Flight.PER_TILE / 2L
        return Rock.blob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = x * Flight.PER_TILE - half,
            positionY = y * Flight.PER_TILE - half,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
            impulseX = blank.massGrams * velocityX / Flight.PER_TILE,
            impulseY = blank.massGrams * velocityY / Flight.PER_TILE,
        )
    }

    /**
     * A box with a wall down each side and no air in it.
     *
     * Vacuum, so the only momentum in the world is the rock's and the ship's: a sealed hull's
     * atmosphere rings, the hull recoils from it, and every "the ship is moving *because of the
     * rock*" claim below would be measuring the weather instead.
     */
    private fun vacuumHull(): VesselState {
        val grid = CFG.initialGrid
        val machines = arrayOfNulls<Machine>(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y)) machines[grid.index(x, y)] = Hull() }
        for (x in 1..WALL_X) { put(x, 6); put(x, 26) }
        for (y in 6..26) { put(1, y); put(WALL_X, y) }
        val state = VesselState(grid = grid, machines = machines.toList(), gravity = VesselState.FREEFALL)
        return state.copy(air = AirField.of(LongArray(grid.size * Species.COUNT)))
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
