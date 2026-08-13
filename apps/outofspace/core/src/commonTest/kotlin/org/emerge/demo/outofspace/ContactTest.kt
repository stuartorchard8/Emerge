package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Contact
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.collectHullContacts
import org.emerge.demo.outofspace.world.solveContacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 2 of `PLAN_rigid_bodies.md`: a contact becomes **a point, a normal and a depth**, and a whole
 * list of them is solved together instead of one axis at a time in the middle of moving.
 *
 * Two things are being pinned. The first is that the geometry a contact reports is right, because
 * everything downstream — torque arms at step 3, friction at step 4 — reads it and nothing else. The
 * second is the corner, which is the smallest case where solving *together* differs from solving in
 * sequence, and therefore the smallest case that shows why stacking will work.
 */
class ContactTest {

    // ── What a contact says ───────────────────────────────────────────────────

    /**
     * A body overlapping a wall from the left reports a normal pointing **out of the hull**, a depth
     * equal to how far in it is, and a point in the middle of the overlap.
     *
     * The normal's direction is the half worth stating twice: it is the axis the solver pushes
     * along, so a sign error here is a body sucked into a wall rather than pushed out of it, and the
     * suite would show it as a body that fell through the floor.
     */
    @Test
    fun `a contact points out of the hull`() {
        val world = wall()
        val body = oneCell()
        val contacts = ArrayList<Contact>()

        // A quarter tile into the wall's left face.
        val overlap = Flight.PER_TILE / 4L
        collectHullContacts(
            world.grid, world.structure, body, 0,
            atX = WALL * Flight.PER_TILE - Flight.PER_TILE + overlap,
            atY = ROW * Flight.PER_TILE,
            restingSpeedX = 0L, restingSpeedY = 0L, into = contacts,
        )

        assertEquals(1, contacts.size, "one cell in one tile is one contact")
        val c = contacts.single()
        assertEquals(-Flight.FRAC_ONE, c.normalX, "the push must be back the way it came, not into the wall")
        assertEquals(0L, c.normalY, "a face-on touch has no sideways component")
        assertEquals(overlap, c.depth, "the depth is how far in it got")
        // The overlap is a quarter-tile sliver against the wall's left edge, so its centre is an
        // eighth of a tile inside.
        assertEquals(WALL * Flight.PER_TILE + overlap / 2L, c.pointX, "the point is the middle of the overlap")
        assertEquals(ROW * Flight.PER_TILE + Flight.PER_TILE / 2L, c.pointY, "and centred on the face")
    }

    /**
     * The normal follows the **shallow** axis, because that is the way in.
     *
     * A body resting on a floor overlaps it slightly in y and enormously in x; pushed along the deep
     * axis it would shoot sideways out of a wall it was merely leaning on. This is the rule that
     * makes a resting contact rest.
     */
    @Test
    fun `the normal takes the axis of least penetration`() {
        val world = wall()
        val body = oneCell()
        val contacts = ArrayList<Contact>()

        // Barely into the top of the wall tile, but almost entirely overlapping it horizontally.
        val shallow = Flight.PER_TILE / 8L
        collectHullContacts(
            world.grid, world.structure, body, 0,
            atX = WALL * Flight.PER_TILE,
            atY = ROW * Flight.PER_TILE - Flight.PER_TILE + shallow,
            restingSpeedX = 0L, restingSpeedY = 0L, into = contacts,
        )

        val c = contacts.single()
        assertEquals(0L, c.normalX, "it came in through the top, so x is not the way out")
        assertEquals(-Flight.FRAC_ONE, c.normalY, "and the way out is upward")
        assertEquals(shallow, c.depth)
    }

    /** Nothing touching is no contacts — the case that must not cost anything or invent anything. */
    @Test
    fun `clear air reports nothing`() {
        val world = wall()
        val contacts = ArrayList<Contact>()
        collectHullContacts(
            world.grid, world.structure, oneCell(), 0,
            atX = 2L * Flight.PER_TILE, atY = 2L * Flight.PER_TILE,
            restingSpeedX = 0L, restingSpeedY = 0L, into = contacts,
        )
        assertTrue(contacts.isEmpty(), "a body in open space touched ${contacts.size} things")
    }

    // ── Solving them together ─────────────────────────────────────────────────

    /**
     * **The case that says why this is a list and not a branch.** A body driven into a corner is
     * touching two walls at once, and both have to end up satisfied.
     *
     * Answered in sequence — which is what the code this replaces did, one axis at a time, mid-move
     * — the second wall computes its push from a velocity the first has already spent, so one of the
     * two is always under-answered and the body keeps some closing speed into it. Solved as a list,
     * every pass refines the last until neither wall is being approached. That is the same property
     * a stack of rocks needs, which is why it is worth having before there are any.
     */
    @Test
    fun `a corner answers both walls at once`() {
        val toward = -2L * Flight.PER_TILE
        val contacts = listOf(
            contact(normalX = Flight.FRAC_ONE, normalY = 0L),
            contact(normalX = 0L, normalY = Flight.FRAC_ONE),
        )
        val vx = longArrayOf(toward)
        val vy = longArrayOf(toward)

        solveContacts(contacts, longArrayOf(MASS), vx, vy, 0L, 0L, shipMass = 0L)

        assertTrue(vx[0] >= 0L, "still driving into the left wall at ${vx[0]}")
        assertTrue(vy[0] >= 0L, "still driving into the floor at ${vy[0]}")
    }

    /** A contact already separating is not a contact to answer — pushing it would add energy. */
    @Test
    fun `a separating touch is left alone`() {
        val away = 2L * Flight.PER_TILE
        val vx = longArrayOf(away)
        val vy = longArrayOf(0L)

        solveContacts(
            listOf(contact(normalX = Flight.FRAC_ONE, normalY = 0L)),
            longArrayOf(MASS), vx, vy, 0L, 0L, shipMass = 0L,
        )

        assertEquals(away, vx[0], "a body leaving was pushed again")
    }

    /**
     * Below the resting threshold the bounce is dropped, or a settling body buzzes for ever.
     *
     * Both halves are asserted against each other rather than against a figure: the fast one must
     * come back up, the slow one must not, and the threshold is what separates them. A test that
     * only checked the slow case would pass on a solver that had stopped bouncing altogether.
     *
     * +y is down and the floor's normal points up, so "coming back up" is a **negative** velocity.
     */
    @Test
    fun `a slow touch stops dead where a fast one bounces`() {
        val floor = contact(normalX = 0L, normalY = -Flight.FRAC_ONE, resting = Flight.PER_TILE / 10L)
        val crawling = Flight.PER_TILE / 100L
        val falling = 2L * Flight.PER_TILE

        val slow = longArrayOf(crawling)
        solveContacts(listOf(floor), longArrayOf(MASS), longArrayOf(0L), slow, 0L, 0L, shipMass = 0L)

        val fast = longArrayOf(falling)
        solveContacts(listOf(floor), longArrayOf(MASS), longArrayOf(0L), fast, 0L, 0L, shipMass = 0L)

        assertTrue(fast[0] < 0L, "a body dropped hard did not come back up: ${fast[0]}")
        assertTrue(
            slow[0] > -crawling / 2L,
            "a body settling was bounced instead of stopped: ${slow[0]} from $crawling",
        )
        assertTrue(slow[0] <= 0L, "and it should not still be sinking: ${slow[0]}")
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private fun contact(
        normalX: Long,
        normalY: Long,
        resting: Long = 0L,
    ) = Contact(
        body = 0,
        pointX = 0L, pointY = 0L,
        normalX = normalX, normalY = normalY,
        depth = Flight.PER_TILE / 100L,
        restingSpeed = resting,
    )

    /** One solid tile at ([WALL], [ROW]) in an otherwise empty, airless world. */
    private fun wall(): VesselState {
        val grid = Grid(12, 12)
        val machines = arrayOfNulls<Machine>(grid.size)
        machines[grid.index(WALL.toInt(), ROW.toInt())] = Hull()
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            air = AirField.of(LongArray(grid.size * Species.COUNT)),
        )
    }

    /** A single-cell body, so that one contact means one cell against one tile and nothing else. */
    private fun oneCell(): RigidBody = RigidBody(
        kind = org.emerge.demo.outofspace.world.BodyKind.ROCK,
        width = 1, height = 1, cells = booleanArrayOf(true),
        positionX = 0L, positionY = 0L, impulseX = 0L, impulseY = 0L,
        oreComposition = OutofspaceReducer.DEFAULT_ORE_BODY,
        energy = org.emerge.demo.outofspace.world.TileEnergy.uniform(1, 0L),
    )

    private companion object {
        init { RockSpawner.enabled = false }

        const val WALL = 6L
        const val ROW = 6L

        /** A round mass, so the arithmetic in a failure message is readable. */
        const val MASS = 1_000_000_000L
    }
}
