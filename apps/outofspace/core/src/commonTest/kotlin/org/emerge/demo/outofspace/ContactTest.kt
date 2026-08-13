package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Contact
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.Operand
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.collectHullContacts
import org.emerge.demo.outofspace.world.solveContacts
import org.emerge.sim.core.physics.primitives.Coord
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
            at = Pose(WALL * Flight.PER_TILE - Flight.PER_TILE + overlap, ROW * Flight.PER_TILE, Coord(0)),
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
            at = Pose(WALL * Flight.PER_TILE, ROW * Flight.PER_TILE - Flight.PER_TILE + shallow, Coord(0)),
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
            at = Pose(2L * Flight.PER_TILE, 2L * Flight.PER_TILE, Coord(0)),
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
        val body = free(vx = toward, vy = toward)

        solveContacts(contacts, listOf(body), ship = null)

        assertTrue(body.velocityX >= 0L, "still driving into the left wall at ${body.velocityX}")
        assertTrue(body.velocityY >= 0L, "still driving into the floor at ${body.velocityY}")
    }

    /** A contact already separating is not a contact to answer — pushing it would add energy. */
    @Test
    fun `a separating touch is left alone`() {
        val away = 2L * Flight.PER_TILE
        val body = free(vx = away, vy = 0L)

        solveContacts(listOf(contact(normalX = Flight.FRAC_ONE, normalY = 0L)), listOf(body), ship = null)

        assertEquals(away, body.velocityX, "a body leaving was pushed again")
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

        val slow = free(vx = 0L, vy = crawling)
        solveContacts(listOf(floor), listOf(slow), ship = null)

        val fast = free(vx = 0L, vy = falling)
        solveContacts(listOf(floor), listOf(fast), ship = null)

        assertTrue(fast.velocityY < 0L, "a body dropped hard did not come back up: ${fast.velocityY}")
        assertTrue(
            slow.velocityY > -crawling / 2L,
            "a body settling was bounced instead of stopped: ${slow.velocityY} from $crawling",
        )
        assertTrue(slow.velocityY <= 0L, "and it should not still be sinking: ${slow.velocityY}")
    }

    // ── The angular half ──────────────────────────────────────────────────────

    /**
     * **The discriminator for step 3.** The same blow, at the same speed, along the same normal:
     * through the centre of mass it only shoves, off the centre of mass it also spins.
     *
     * Both halves are asserted against each other because either alone passes on something broken.
     * A solver that ignored the arm entirely would pass the centreline half; a solver that spun
     * everything — an arm computed from the wrong origin, say, which is the specific mistake step 1
     * caught the vessel making — would pass the off-centre half. Only the pair pins it.
     */
    @Test
    fun `a blow off the centre of mass spins the body and one through it does not`() {
        val falling = 2L * Flight.PER_TILE
        val floor = { at: Long ->
            Contact(
                body = 0, pointX = at, pointY = 0L,
                normalX = 0L, normalY = -Flight.FRAC_ONE,
                depth = Flight.PER_TILE / 100L, restingSpeed = 0L,
            )
        }

        val square = free(vx = 0L, vy = falling)
        solveContacts(listOf(floor(0L)), listOf(square), ship = null)

        val corner = free(vx = 0L, vy = falling)
        solveContacts(listOf(floor(2L * Flight.PER_TILE)), listOf(corner), ship = null)

        assertEquals(0L, square.spun, "a blow straight through the centre of mass twisted it")
        assertTrue(square.angVel == 0L, "and it should not be turning: ${square.angVel}")
        assertTrue(corner.spun != 0L, "a blow two tiles off the centre of mass did not twist it")
        assertTrue(corner.angVel != 0L, "and it should be turning: ${corner.angVel}")
    }

    /**
     * A long arm makes a body **easier to move and harder to stop**: the impulse that answers a
     * contact out on a limb is smaller, because most of the closing speed goes into spin instead.
     *
     * This is [Operand.effectiveMass] being read at all. A solver that sized every bounce against
     * the whole mass would hand the two contacts identical impulses, and the visible symptom would
     * be rocks that pivot off a corner as hard as they rebound off a flat — energy from nowhere,
     * the same shape of defect as the stale wall.
     */
    @Test
    fun `a contact far from the centre of mass takes a smaller impulse`() {
        val falling = 2L * Flight.PER_TILE
        fun struck(at: Long): Long {
            val body = free(vx = 0L, vy = falling)
            solveContacts(
                listOf(
                    Contact(
                        body = 0, pointX = at, pointY = 0L,
                        normalX = 0L, normalY = -Flight.FRAC_ONE,
                        depth = Flight.PER_TILE / 100L, restingSpeed = 0L,
                    ),
                ),
                listOf(body), ship = null, iterations = 1,
            )
            return -body.gaveY
        }

        val centred = struck(0L)
        val distant = struck(3L * Flight.PER_TILE)
        assertTrue(centred > 0L, "the centred blow delivered nothing at all")
        assertTrue(distant < centred, "an arm's length out the blow was as hard: $distant vs $centred")
    }

    /**
     * A spin the solver cannot see is a spin that pumps energy. The body here is **not** moving —
     * its centre of mass is at rest — and it is still driving one corner into the floor, because it
     * is turning.
     *
     * `ω × r` is what makes that contact visible, and it is the term this pins: without it the
     * closing speed reads as zero, the contact is skipped as separating, and a spinning rock grinds
     * through a bulkhead at any speed at all.
     */
    @Test
    fun `a spinning body still closes on a contact its centre is not approaching`() {
        val body = free(vx = 0L, vy = 0L, spin = Rotation.RAW_PER_RADIAN / 10L)
        // Two tiles to the *left* of the centre of mass. Turning clockwise (+ang is clockwise, +y
        // down), a point out to the left is on its way up — so the floor to meet it is above.
        solveContacts(
            listOf(
                Contact(
                    body = 0, pointX = -2L * Flight.PER_TILE, pointY = 0L,
                    normalX = 0L, normalY = Flight.FRAC_ONE,
                    depth = Flight.PER_TILE / 100L, restingSpeed = 0L,
                ),
            ),
            listOf(body), ship = null,
        )

        assertTrue(body.spun != 0L, "a body at rest but spinning was treated as touching nothing")
        assertTrue(
            body.angVel in 0L until Rotation.RAW_PER_RADIAN / 10L,
            "the spin should have been taken off it, not reversed or ignored: ${body.angVel}",
        )
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

    /**
     * A free operand with its centre of mass at the grid origin, so a contact's lever arm is just
     * its point — which keeps the arithmetic in a failure message readable.
     *
     * [GYRATION_SQ] is a third of a tile², roughly a 2×2 block of cells, so an arm of a tile or two
     * is comparable to the radius of gyration and the angular terms are the same size as the linear
     * ones. Pick it much smaller and every test here passes on rounding.
     */
    private fun free(vx: Long, vy: Long, spin: Long = 0L) = Operand(
        mass = MASS,
        about = MassDistribution(mass = MASS, comX = 0L, comY = 0L, gyrationSq = GYRATION_SQ),
        comX = 0L, comY = 0L,
        velocityX = vx, velocityY = vy, angVel = spin,
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

        /** A third of a tile² of gyration — see [free]. */
        const val GYRATION_SQ = Rotation.GYRATION_SCALE / 3L
    }
}
