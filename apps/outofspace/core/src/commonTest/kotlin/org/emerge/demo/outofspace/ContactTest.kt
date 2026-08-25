package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.CellShape
import org.emerge.demo.outofspace.world.Contact
import org.emerge.demo.outofspace.world.contactBetween
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.MassDistribution
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.Operand
import org.emerge.demo.outofspace.world.Pose
import org.emerge.demo.outofspace.world.pairRoughness
import org.emerge.demo.outofspace.world.roughnessOf
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.RockContact
import org.emerge.demo.outofspace.world.RockSpawner
import org.emerge.demo.outofspace.world.Rotation
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.collectHullContacts
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.TileEnergy
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
            shipPose = Pose.IDENTITY,
            restingSpeedX = 0L, restingSpeedY = 0L, into = contacts,
        )

        assertEquals(1, contacts.size, "one cell in one tile is one contact")
        val c = contacts.single()
        assertEquals(-Flight.FRAC_ONE, c.normalX, "the push must be back the way it came, not into the wall")
        assertEquals(0L, c.normalY, "a face-on touch has no sideways component")
        assertEquals(overlap, c.depth, "the depth is how far in it got")
        // ⚠️ **The point is on the wall's face**, and step 4 is what moved it there. It used to be
        // the middle of the overlap box, which is what a box against a box has to say, because two
        // overlapping rectangles touch across a region and the middle of it is the only fair
        // summary. A **disc** against a box touches at one place — the closest point on the box to
        // the disc's centre — so there is nothing to average and the exact answer is available.
        //
        // Derived rather than observed: the cell's centre lands at `WALL·PER − PER/4`, which is
        // outside the wall tile, so the closest point on that tile is on its left face at
        // `WALL·PER`, level with the centre. The depth checked above is the same number under both
        // conventions, which is why only this line moved.
        assertEquals(WALL * Flight.PER_TILE, c.pointX, "the point is where the disc touches the face")
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
            shipPose = Pose.IDENTITY,
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
            shipPose = Pose.IDENTITY,
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
     *
     * ⚠️ **Pinned against the tip speed, and the version this replaces was not.** That one asserted
     * only that *something* had been booked and that the spin had not reversed — and both of those
     * pass on a term a **millionth** of its true size, which is what `ω × r` was for a whole step
     * after an arm in millitiles was subtracted from a velocity in [Flight.PER_TILE]s. A spinning
     * rock ground through bulkheads the entire time with this test green.
     *
     * It also had the claim backwards. A bouncing contact is *supposed* to reverse a spin; only a
     * solver that could barely see the spin would leave its sign alone. What the restitution
     * actually promises is a magnitude, so a term of the wrong size cannot satisfy it.
     */
    @Test
    fun `a spinning body still closes on a contact its centre is not approaching`() {
        val spin = Rotation.RAW_PER_RADIAN / 10L
        val body = free(vx = 0L, vy = 0L, spin = spin)
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

        // The tip rebounds at `e` times the speed it arrived at, and on a fixed arm that is the
        // spin rebounding at `e` times its own — the arm cancels, because it is the same arm going
        // in and coming out. Derived from the restitution rather than read off the solver.
        val expected = -spin * RockContact.RESTITUTION_NUM / RockContact.RESTITUTION_DEN
        assertTrue(body.spun != 0L, "a body at rest but spinning was treated as touching nothing")
        assertTrue(
            abs(body.angVel - expected) < abs(expected) / 4L,
            "the tip should rebound at half its speed: ${body.angVel} against $expected",
        )
    }

    // ── Friction ──────────────────────────────────────────────────────────────

    /**
     * **What step 4 is for.** A body spinning against a surface it is pressed into is slowed by it,
     * and the same body against a frictionless surface is not.
     *
     * Asserted as a pair, because either half alone passes on something broken: a solver that damped
     * every spin regardless would pass the first, and one that never applied a tangential impulse at
     * all would pass the second. Only the difference between them is evidence that the friction on
     * the *contact* is what did it.
     *
     * Nothing anywhere else takes energy out of a spin — space is a vacuum and a rock turning in it
     * turns for ever — so this is the whole of the mechanism by which a rock that lands on a corner
     * eventually stops cartwheeling.
     */
    @Test
    fun `friction takes the spin off a body pressed against a surface`() {
        val spin = Rotation.RAW_PER_RADIAN / 10L
        fun ground(mu: Long): Operand {
            val body = free(vx = 0L, vy = Flight.PER_TILE / 2L, spin = spin)
            solveContacts(
                listOf(
                    Contact(
                        body = 0, pointX = 0L, pointY = 2L * Flight.PER_TILE,
                        normalX = 0L, normalY = -Flight.FRAC_ONE,
                        depth = Flight.PER_TILE / 100L, restingSpeed = 0L,
                        friction = mu,
                    ),
                ),
                listOf(body), ship = null,
            )
            return body
        }

        val gripped = ground(pairRoughness(800L, 800L))
        val slippery = ground(0L)

        assertEquals(spin, slippery.angVel, "a frictionless touch slowed the spin")
        assertTrue(
            gripped.angVel < spin,
            "a gripping touch left the spin alone: ${gripped.angVel} against $spin",
        )
        assertTrue(gripped.angVel >= 0L, "and it drove the spin backwards: ${gripped.angVel}")
    }

    /**
     * Stu's ordering, stated as a test rather than as five numbers in an enum: **metal slides and
     * rock does not.**
     *
     * The rule is that the smoother surface governs, so rock against a steel deck grips at steel's
     * number and not at rock's — which is why the middle case equals the metal-on-metal one rather
     * than sitting between the two.
     */
    @Test
    fun `rock grips harder than metal, and the smoother surface governs`() {
        val rock = roughnessOf(Mixture.EMPTY)
        val steel = Material.Steel.roughness

        val rockOnRock = pairRoughness(rock, rock)
        val rockOnSteel = pairRoughness(rock, steel)
        val steelOnSteel = pairRoughness(steel, steel)

        assertTrue(rockOnRock > rockOnSteel, "rubble on rubble slid as easily as rubble on plate")
        assertEquals(steelOnSteel, rockOnSteel, "the rougher surface decided the pair")
        assertTrue(steelOnSteel > 0L, "the ship is frictionless")
    }

    // ── Fixture ───────────────────────────────────────────────────────────────

    private fun abs(v: Long): Long = if (v < 0L) -v else v

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
        val deck = DeckArray(grid)
        deck += Hull(grid.tile(WALL.toInt(), ROW.toInt()))
        return VesselState(
            grid = grid,
                        deck = deck,
            air = Stuff.gas(MassArray(grid.size)),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
    }

    /** A single-cell body, so that one contact means one cell against one tile and nothing else. */
    private fun oneCell(): RigidBody = RigidBody(
        kind = org.emerge.demo.outofspace.world.BodyKind.ROCK,
        width = 1, height = 1, cells = booleanArrayOf(true),
        positionX = 0L, positionY = 0L, impulseX = 0L, impulseY = 0L,
        oreComposition = OutofspaceReducer.DEFAULT_ORE_BODY,
        energy = TileEnergy.uniform(1, 0L),
    )

    // ── A box has an orientation, and that is what deletes the shared frame ────

    /**
     * A disc touching a box turned 45° is pushed **along the face it actually touched**, not along
     * a grid axis.
     *
     * This is step 6's blocker stated as small as it goes. Every hull contact in the game is
     * Disc-vs-Box, and a [CellShape.Box] is axis-aligned *in whatever frame it is handed* — it has
     * nowhere to carry an angle. That is not a gap in the shape table, it is the reason the sweep
     * has to turn every operand into the grid's axes before it can ask a single question: one
     * operand is allowed to be square and everybody else has to be expressed relative to it.
     *
     * Which is exactly what §5 says cannot survive the unification — *"you cannot unify vessels and
     * rocks while one of them defines the coordinate system"*. Two box-celled operands at different
     * angles have no shared frame that is axis-aligned for both, so as long as a box cannot be
     * asked at an angle there can only ever be one of them, and it can only ever be the ship.
     *
     * The claim is stated structurally rather than against transcribed cosines, so that it survives
     * a change in how the turn is computed: at 45° the two components of a unit normal are equal,
     * they are both positive when the disc sits off the box's local +x face, and their length is a
     * unit. A tolerance here is the rotation round trip — [Pose]'s own bound is ~3e-6 of a tile — so
     * **a near miss means the transcription is wrong, not that the bound is tight.**
     */
    @Test
    fun `a box can be asked at an angle`() {
        val half = Flight.PER_TILE / 2L
        val radius = Flight.PER_TILE / 2L
        val overlap = Flight.PER_TILE / 4L
        // ⚠️ A quarter turn, and `Coord(1, 4)` is what writes one: a full turn is the whole `Int`
        // range, so [Coord]'s `(n, d)` constructor — `n * Int.MAX_VALUE / d` — is in **half** turns.
        // `Coord(1, 8)` is 22.5°, which this test caught by reporting a normal at exactly tan 22.5°.
        val turn = Coord(1, 4)

        // The box sits somewhere unremarkable; the disc sits off its local +x face, overlapping by
        // a quarter tile. Placed *through* the turn so the setup states a direction in the box's
        // frame and lets the transform put it in the world — the thing under test is the answer
        // coming back out, not the arithmetic going in.
        val boxX = 20L * Flight.PER_TILE
        val boxY = 15L * Flight.PER_TILE
        val frame = Pose(boxX, boxY, turn)
        val reach = half + radius - overlap
        val contacts = ArrayList<Contact>()

        contactBetween(
            a = CellShape.CELL, ax = frame.toWorldX(reach, 0L), ay = frame.toWorldY(reach, 0L),
            b = CellShape.Box(half, half), bx = boxX, by = boxY, bFrame = frame,
            body = 0, restingSpeedX = 0L, restingSpeedY = 0L, friction = 0L, into = contacts,
        )

        assertEquals(1, contacts.size, "a disc a quarter tile into a face is one touch")
        val c = contacts.single()
        assertTrue(c.normalX > 0L && c.normalY > 0L, "the push is out along the turned face: $c")
        assertNear(c.normalX, c.normalY, "at 45 degrees the two components of the normal are equal")
        assertNear(
            isqrt(c.normalX * c.normalX + c.normalY * c.normalY), Flight.FRAC_ONE,
            "a contact normal is a unit, whatever angle it came out at",
        )
        assertNear(c.depth, overlap, "the depth is how far into the turned face it got")
        // And the point is on that face — reach minus the radius back along the local +x axis.
        assertNear(c.pointX, frame.toWorldX(half, 0L), "the touch is on the face, not at the centre")
        assertNear(c.pointY, frame.toWorldY(half, 0L), "the touch is on the face, not at the centre")
    }

    /**
     * A hull contact comes back in **world** axes, and the ship's angle is what makes that a claim
     * rather than a restatement.
     *
     * The second half of step 6. With a box able to carry a turn, the sweep no longer has to express
     * everything in the grid's axes to ask a question of the hull — so it stops doing so, and the
     * ship becomes an operand whose cells happen to be boxes rather than the thing everybody else is
     * measured against.
     *
     * Stated at 45° because that is the angle where the two components of the answer are equal and a
     * frame that had not actually changed would give itself away as a clean `(0, −1)`. A body resting
     * on the deck's upper face is pushed **out of the deck**, which is straight up in the *grid* and
     * up-and-to-starboard in the world once the ship is rolled.
     */
    @Test
    fun `a hull contact comes back in world axes`() {
        val world = wall()
        val body = oneCell()
        val turn = Coord(1, 4)
        // The ship's origin is left at zero: what is under test is the turn, and a translation would
        // only add the same offset to both sides of every assertion below.
        val ship = Pose(0L, 0L, turn)
        val contacts = ArrayList<Contact>()

        // A quarter tile into the wall tile's **upper** face, expressed in the grid and then put
        // into the world by the ship's own pose — the body stores world coordinates, so this is the
        // ordinary way a caller states "resting on top of that tile".
        val overlap = Flight.PER_TILE / 4L
        val localX = WALL * Flight.PER_TILE
        val localY = ROW * Flight.PER_TILE - Flight.PER_TILE + overlap
        collectHullContacts(
            world.grid, world.structure, body, 0,
            at = Pose(ship.toWorldX(localX, localY), ship.toWorldY(localX, localY), turn),
            shipPose = ship,
            restingSpeedX = 0L, restingSpeedY = 0L, into = contacts,
        )

        assertEquals(1, contacts.size, "one cell on top of one tile is one contact")
        val c = contacts.single()
        // Out of the deck is grid −y; rolled a quarter turn that is world (+sin, −cos), so the
        // components are equal in size, and the signs are what say it is a *roll* and not a flip.
        assertTrue(c.normalX > 0L, "a ship rolled clockwise pushes a rock off its deck to starboard: $c")
        assertTrue(c.normalY < 0L, "and still upward, or the deck is sucking the rock in: $c")
        assertNear(c.normalX, -c.normalY, "at a quarter turn the two components are equal in size")
        assertNear(
            isqrt(c.normalX * c.normalX + c.normalY * c.normalY), Flight.FRAC_ONE,
            "a contact normal is a unit, whatever frame it came back in",
        )
        assertNear(c.depth, overlap, "the depth is a length, and a turn does not change one")
        // The touch is at the middle of the tile's upper face, in the same place the grid would have
        // put it — a cell's centre is half a tile in from the body's origin, so a one-cell body posed
        // at the tile's own x sits centred over it rather than over its corner.
        val faceX = WALL * Flight.PER_TILE + Flight.PER_TILE / 2L
        val faceY = ROW * Flight.PER_TILE
        assertNear(c.pointX, ship.toWorldX(faceX, faceY), "the touch is on the face")
        assertNear(c.pointY, ship.toWorldY(faceX, faceY), "the touch is on the face")
    }

    /**
     * The rotation round trip, as [Pose] measures it: ~3e-6 of a tile over a full grid, and normals
     * are unit vectors of [Flight.FRAC_ONE] rather than lengths, so the same relative bound applies
     * to both. Generous by three orders of magnitude against a transcription error.
     */
    private fun assertNear(actual: Long, expected: Long, why: String) {
        val slack = 1L + (if (expected < 0L) -expected else expected) / 100_000L
        val off = actual - expected
        assertTrue(
            (if (off < 0L) -off else off) <= slack,
            "$why: expected $expected, got $actual (off by $off, slack $slack)",
        )
    }

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
