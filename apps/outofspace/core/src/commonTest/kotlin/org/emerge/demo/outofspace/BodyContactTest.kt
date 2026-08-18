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
import org.emerge.demo.outofspace.world.RockContact
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Rock against rock** — step 5 of `PLAN_rigid_bodies.md`, and the first collision in the game the
 * ship is not a party to.
 *
 * Every one of these fails at the previous commit by the same mechanism, and it is worth naming
 * because it is not the one the code looks like it would fail by: Disc-vs-Disc has been in the
 * narrow phase since step 4 and is correct. What was missing is the *shape of the tick*. Bodies were
 * swept one at a time, so by the time the second rock was stepped the first had already spent its
 * whole tick and was not there to be hit — the two passed through each other at every speed, at
 * every angle, with a working narrow phase sitting unused.
 *
 * ⚠️ The fixtures are **vacuum and freefall**, which is doing real work here rather than tidying.
 * The claims below are about momentum staying inside the pair, and a sealed hull's atmosphere rings
 * against the walls for the whole run — every one of them would be measuring the weather.
 */
class BodyContactTest {
    init { RockSpawner.enabled = false }

    /**
     * ⚠️ [RockContact.separateAlongCentres] is a global, and two of the tests below set it.
     *
     * Put back afterwards rather than left where the last test happened to leave it: a switch read
     * by the sim and written by a test is an order dependency between test classes, which is the
     * kind of thing that fails once in a hundred runs on whichever machine shuffles them.
     */
    @AfterTest
    fun restoreSeparationRule() { RockContact.separateAlongCentres = false }

    /**
     * The whole increment in one assertion: two rocks thrown at each other come apart again.
     *
     * Stated as a **closing** speed that reverses, not as either body's own velocity, for the same
     * reason `RockContactTest` states its ricochet that way — a bounce is a claim about a pair, and
     * these two are the same mass, so each ends up carrying half of an answer that is only whole
     * between them.
     */
    @Test
    fun `two rocks thrown at each other bounce apart`() {
        val speed = Flight.PER_TILE / 4L
        val left = bodyAt(x = 12, y = 16, velocityX = speed)
        val right = bodyAt(x = 22, y = 16, velocityX = -speed)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(left, right)))

        var touched = false
        repeat(TICKS) {
            controller.stepOnce()
            val (a, b) = controller.state.bodies
            if (a.velocityX - b.velocityX > -speed * 2L + speed / 100L) touched = true
        }

        val (a, b) = controller.state.bodies
        assertTrue(touched, "they passed straight through each other")
        // Closing while they approached, separating now: the sign of the relative velocity along the
        // line between them has flipped, which is the one thing a collision must do and a
        // pass-through cannot.
        assertTrue(
            a.velocityX - b.velocityX < 0L,
            "they are still closing at ${a.velocityX - b.velocityX} after touching",
        )
        // And they are on the sides they started on. A pair that swapped places would satisfy the
        // velocity claim above just as well — that is what tunnelling through each other and then
        // separating looks like — and it is the failure a sweep sized on one body at a time gives.
        assertTrue(a.centreX < b.centreX, "they swapped sides: ${a.centreX} against ${b.centreX}")
    }

    /**
     * ⚠️ **The ship is not a party to it.** Two rocks hitting each other in mid-air must not move
     * the vessel, and the momentum they exchange must stay inside the pair.
     *
     * This is the ledger claim, and it is the one that catches the tempting wrong implementation.
     * The obvious way to make a body-body contact conserve is to book it the way a hull contact is
     * booked — through [VesselState.bodyImpulseX], which is the only channel that existed — and it
     * balances perfectly while flying the ship on rocks bumping into each other in the hold.
     */
    @Test
    fun `two rocks bouncing off each other leave the ship alone`() {
        val speed = Flight.PER_TILE / 4L
        val left = bodyAt(x = 12, y = 16, velocityX = speed)
        val right = bodyAt(x = 22, y = 16, velocityX = -speed)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(left, right)))
        val before = left.impulseX + right.impulseX

        repeat(TICKS) {
            controller.stepOnce()
            assertEquals(0L, balance(controller.state), "the ledger broke on tick ${controller.state.tick}")
        }

        val s = controller.state
        assertEquals(0L, s.vesselImpulseX, "a pair of rocks flew the ship")
        assertEquals(0L, s.velocityX, "and it is moving because of it")
        // Momentum inside the pair, exactly: in vacuum and freefall nothing else can add to it, and
        // the impulses of a contact are equal and opposite between two entries of the same sum.
        val after = s.bodies.sumOf { it.impulseX }
        assertEquals(before, after, "the pair gained momentum from nowhere")
    }

    /**
     * A rock cannot be pushed **through** another rock into a wall — the chain, which is what
     * stacking is once the deck can hold anything up.
     *
     * Three contacts in one line, two of them on the middle body: hull-against-parked and
     * parked-against-thrown. Answered in sequence — a body at a time, which is what the tick used to
     * do — the thrown rock is given its support before the parked one knows it is being leaned on,
     * and it sinks into it by a whole penetration every tick. Answered from one frozen state they
     * converge, which is the property §2 of the plan said a per-body single-pass sweep could never
     * have.
     */
    @Test
    fun `a rock driven onto a rock against the wall does not sink into it`() {
        // Parked with its flank against the starboard bulkhead, and thrown at hard from port.
        val parked = bodyAt(x = WALL_X - 3, y = 16)
        val thrown = bodyAt(x = WALL_X - 11, y = 16, velocityX = 2L * Flight.PER_TILE)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(thrown, parked)))

        repeat(TICKS) {
            controller.stepOnce()
            val (a, b) = controller.state.bodies
            // ⚠️ Checked **every tick**, not at the end. A body that sinks in and comes back out is
            // a body that spent the run inside another one, and an end-state assertion scores that
            // as a pass.
            val gap = b.centreX - a.centreX
            assertTrue(
                gap > 4L * Flight.PER_TILE,
                "the thrown rock is ${gap / Flight.PER_TILE} tiles from the parked one on tick " +
                    "${controller.state.tick}, and they are five tiles wide",
            )
        }

        val (a, b) = controller.state.bodies
        assertTrue(a.centreX < b.centreX, "the thrown rock ended up on the far side of the parked one")
    }

    /**
     * Two rocks **placed** overlapping ease apart, and are not fired apart.
     *
     * A placement is not an impact — the editor can drop one rock on another, and an extractor can
     * free one from inside a seam — so the pair has no closing speed to reverse and asks for no
     * impulse. What separates them is the position push, and the bound on it is what stops "eased"
     * becoming "launched": a tenth of a tile a tick, halved between the two of them, against an
     * overlap that is most of a body.
     */
    @Test
    fun `two rocks placed on top of each other are eased apart, not fired apart`() {
        // The line-of-centres rule, which is not the default — see the test below for what the
        // default does with the same fixture, and [RockContact.separateAlongCentres] for why.
        RockContact.separateAlongCentres = true
        // ⚠️ **Overlapping on one flank, not concentrically**, and the two fixtures this replaces
        // are both worth keeping in mind because each asserts that nothing happens:
        //
        // - A **whole tile** apart, every cell disc of one body exactly touches its opposite number
        //   and not one of them overlaps, because a disc is inscribed in its cell. The pair is
        //   interpenetrating as bounding boxes and merely touching as geometry, and the narrow phase
        //   correctly emits nothing.
        // - Exactly **half** a tile apart, it emits plenty — and they cancel. Every cell of one body
        //   sits midway between two cells of the other, so each is overlapped by 0.5 of a tile on
        //   its left and 0.5 on its right, and the deepest-push-per-direction rule subtracts one
        //   from the other and arrives at nothing. It is the one fraction that does this: at 0.3 the
        //   two sides are 0.7 and 0.3 deep and the body goes the way of the deeper. A symmetric
        //   interlock has no separating direction, which is a statement about the geometry rather
        //   than a defect in the push — but it is a real configuration and it is written up in
        //   `PLAN_rigid_bodies.md`, because a pile that settles into it stays in it.
        val one = bodyAt(x = 17, y = 16)
        val two = bodyAt(x = 17, y = 16, offsetX = 33L * Flight.PER_TILE / 10L)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(one, two)))

        repeat(TICKS) { controller.stepOnce() }

        val (a, b) = controller.state.bodies
        // They came apart: the pair started 3.3 tiles from centre to centre and five is where two
        // five-wide blobs of inscribed discs stop touching.
        val gap = b.centreX - a.centreX
        assertTrue(
            gap > 9L * Flight.PER_TILE / 2L,
            "they are still stacked: $gap raw, from a start of ${33L * Flight.PER_TILE / 10L}",
        )
        // And slowly, and then they stopped. The budget is a tenth of a tile a tick shared between
        // them, so a tile and a half of overlap takes most of this run — anything past six tiles is
        // a body thrown by its own depenetration, which is the failure the budget exists for and the
        // one that used to put a rock through the far wall.
        assertTrue(
            gap < 6L * Flight.PER_TILE,
            "they were fired apart: ${gap / Flight.PER_TILE} tiles in $TICKS ticks",
        )
        // ⚠️ And neither of them is *moving*. A push that leaked into the velocity would separate
        // them just as well and then keep going for ever, which on a deck is a pile that slowly
        // spreads itself out.
        assertEquals(0L, a.impulseX, "the push was booked as momentum, and it will not stop")
        assertEquals(0L, b.impulseX, "the same, on the other one")
    }

    /**
     * The **default** rule on the same fixture: they ease together into an interlock and stop there.
     *
     * ⚠️ This pins a jam on purpose. Deepest-push-per-direction cannot separate two lattices of
     * discs — at a half-tile offset each body is asked to go left exactly as hard as it is asked to
     * go right — so a placed pair walks to the nearest half-tile offset and stays. Stu's call is that
     * this is the reading he wants: rubble that has keyed together looks like a pile of ore, where
     * two blobs standing exactly tangent look like two blobs.
     *
     * What the test is really guarding is the other half of that claim, which is the half that could
     * quietly stop being true: it settles, and it settles **still**. A jam that fed the position push
     * back into the velocity would creep, buzz, or spit the pair out later, and none of those reads
     * as ore.
     */
    @Test
    fun `the default rule settles an overlapping pair into an interlock and leaves it there`() {
        RockContact.separateAlongCentres = false
        val one = bodyAt(x = 17, y = 16)
        val two = bodyAt(x = 17, y = 16, offsetX = 33L * Flight.PER_TILE / 10L)
        val controller = OutofspaceController(CFG, vacuumHull().copy(bodies = listOf(one, two)))

        repeat(TICKS) { controller.stepOnce() }
        val settled = controller.state.bodies.let { it[1].centreX - it[0].centreX }
        repeat(TICKS) { controller.stepOnce() }
        val later = controller.state.bodies.let { it[1].centreX - it[0].centreX }

        // It moved — the overlap was answered — and it landed on the half-tile interlock.
        assertTrue(settled > 33L * Flight.PER_TILE / 10L, "nothing happened at all: $settled")
        assertEquals(7L * Flight.PER_TILE / 2L, settled, "it settled somewhere other than the interlock")
        // And then stayed there, with no momentum anywhere in the pair.
        assertEquals(settled, later, "the interlock is creeping")
        val (a, b) = controller.state.bodies
        assertEquals(0L, a.impulseX, "the push leaked into the momentum")
        assertEquals(0L, b.impulseX, "the same, on the other one")
    }

    private fun balance(s: VesselState): Long = s.momentumBalanceX + s.momentumBalanceY

    /** A body centred on a tile, given a velocity in the world frame rather than an impulse. */
    private fun bodyAt(
        x: Int,
        y: Int,
        velocityX: Long = 0L,
        velocityY: Long = 0L,
        /** A sub-tile nudge, for fixtures that must not land on the lattice. */
        offsetX: Long = 0L,
    ): RigidBody {
        val blank = RigidBody.rockBlob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = 0L, positionY = 0L,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
        val half = (Edit.DEFAULT_ROCK_RADIUS * 2 + 1) * Flight.PER_TILE / 2L
        return RigidBody.rockBlob(
            radius = Edit.DEFAULT_ROCK_RADIUS,
            positionX = x * Flight.PER_TILE - half + offsetX,
            positionY = y * Flight.PER_TILE - half,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
            impulseX = scaledRatio(velocityX, Flight.PER_TILE, blank.mass),
            impulseY = scaledRatio(velocityY, Flight.PER_TILE, blank.mass),
        )
    }

    /** A box with a wall down each side and no air in it — `RockContactTest`'s, for the same reason. */
    private fun vacuumHull(): VesselState {
        val grid = CFG.initialGrid
        val machines = arrayOfNulls<Machine>(grid.size)
        val deck = DeckArray(grid.size)
        fun put(x: Int, y: Int) { if (grid.inBounds(x, y) && deck[grid.tile(x, y)] == null) deck += Hull(grid.tile(x, y)) }
        for (x in 2..<WALL_X) { put(x, 6); put(x, 26) }
        for (y in 6..26) { put(1, y); put(WALL_X, y) }
        val state = VesselState(grid = grid, machines = machines.toList(), deck, gravity = VesselState.FREEFALL, buffers = BufferLayer.forMachines(grid, machines.toList()), rail = RailLayer.empty(grid.size))
        return state.copy(air = Stuff.gas(MassArray(grid.size)))
    }

    private companion object {
        val CFG = OutofspaceConfig()

        /** The starboard wall. */
        const val WALL_X = 33

        const val TICKS = 40
    }
}
