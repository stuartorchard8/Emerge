package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DeckMachineKind

/**
 * One touch: **a point, a normal, and a depth** — step 2 of `PLAN_rigid_bodies.md`.
 *
 * This is the whole interface between *finding* a contact and *resolving* one, and keeping it that
 * narrow is the point of the type. Everything downstream — restitution, the resting threshold, the
 * torque arm, the ledger, and friction when it arrives — reads these five numbers and never asks
 * what shape produced them. A new cell shape then costs one function that emits `Contact`s and
 * changes nothing here.
 *
 * The code this replaces could not have been extended that way. It decided its normal by re-running
 * the overlap test on one axis at a time (*"if I move on x alone, do I still overlap?"*), so the
 * answer was always ±x or ±y by construction, whatever angle anything was at, and it computed no
 * point at all — which is why no torque could be booked from a collision even in principle.
 *
 * ⚠️ [normalX]/[normalY] is a **unit vector in [Flight.FRAC_ONE]ths**, not an axis flag, even though
 * every normal produced today happens to lie on an axis because the hull is square tiles. Storing it
 * as a vector is what lets step 4 hand the same solver a disc's normal without touching it.
 */
class Contact(
    /** Which body, as an index into the list being stepped. */
    val body: Int,
    /**
     * What it is touching: another body's index, or [HULL] for the vessel.
     *
     * ⚠️ **The solver does not know which of the two it is**, and that is the whole of step 5. A
     * contact used to name one body and mean "against the ship" by omission, so a rock could only
     * ever be answered against the hull and two rocks passed through each other. Naming both sides
     * makes rock-on-rock the *same* contact as rock-on-hull with a different index in one field —
     * and it is what lets a stack converge, because the body underneath is solved in the same pass
     * as the body on top rather than in its own sweep afterwards.
     *
     * The normal points **out of [other] and into [body]**, whichever [other] turns out to be.
     */
    val other: Int = HULL,
    /** Where they touch, in the **grid frame**, at [Flight.PER_TILE] to the tile. */
    val pointX: Long,
    val pointY: Long,
    /** Unit, [Flight.FRAC_ONE]ths, pointing **out of the hull and into the body**. */
    val normalX: Long,
    val normalY: Long,
    /** How far they have interpenetrated along the normal, at [Flight.PER_TILE] to the tile. */
    val depth: Long,
    /** The closing speed threshold below which this contact stops bouncing — see [RockContact.restingSpeed]. */
    val restingSpeed: Long,
    /**
     * How much sideways force this touch can carry before it slides, as a fraction of the force
     * pressing it together — Coulomb's `μ`, in [Flight.FRAC_ONE]ths.
     *
     * ⚠️ **Per contact, looked up from the two cells that are touching**, not read from a constant by
     * the solver. It returns the same number for every pair today ([RockContact.FRICTION]), and that
     * is the point: Stu's call is that restitution and friction become mixture- and form-dependent later, and a
     * lookup that currently answers a constant is a one-line change then, where a constant baked into
     * the solver is a redo.
     *
     * ⚠️ This is the **only** place friction enters, and it enters as a tangential impulse at a
     * contact. Nothing damps a body that is not touching anything: space is a vacuum, and a rock
     * spinning in it spins for ever.
     */
    val friction: Long = 0L,
) {
    companion object {
        /** [other]'s value when the thing being touched is the vessel rather than another body. */
        const val HULL: Int = -1
    }
}

/**
 * Every place [body] — placed in the **world** by [at] — is inside the hull.
 *
 * One contact per cell-and-tile pair that overlaps, with the normal along the **axis of least
 * penetration** and the point at the centre of the overlap. That is the honest reading of a
 * square-against-square touch: the shallow axis is the one it came in through, and the deep axis
 * would push it sideways out of a wall it is resting against.
 *
 * ### The shapes, since step 4
 *
 * Each of the body's cells is a [CellShape.Disc] and each solid tile is a [CellShape.Box], and the
 * geometry is [contactBetween]'s business rather than this function's. What is left here is the
 * search: which cells, against which tiles.
 *
 * A disc at a turned centre is **exact at every angle** — a disc has no orientation to get wrong —
 * so the approximation step 3 carried, of turning a cell's centre but not its square, is gone rather
 * than merely reduced.
 *
 * ⚠️ Emits contacts for cells that are *already* interpenetrating, which is what makes the solver
 * able to push a body out rather than merely refuse to let it in. It is also why the caller must
 * skip a body that started the tick wedged — see [driftBodies].
 */
fun collectHullContacts(
    grid: Grid,
    structure: StructureMap,
    body: RigidBody,
    index: Int,
    /** The body's pose **in the world**, which is where a body stores itself. */
    at: Pose,
    /**
     * Where the grid is in the world — the ship's pose for this substep.
     *
     * ### ⚠️ This is the parameter that stops the grid being the frame
     *
     * [at] used to be a *grid*-frame pose, and every contact came back in grid axes, because a
     * [CellShape.Box] could not be asked at an angle: the only way to make the hull square was to
     * express the body relative to it. The grid is still where the *search* happens — it is an
     * addressing scheme, and a tile lookup needs tile indices — but it is no longer the axes the
     * answer is stated in. See `PLAN_grid_vs_continuous.md` §4: the grid as an addressing scheme and
     * the grid as a shape constraint are independent, and this is the line where they separate.
     */
    shipPose: Pose,
    restingSpeedX: Long,
    restingSpeedY: Long,
    into: MutableList<Contact>,
    /**
     * The deck, for one thing only: what a contact's friction should be. `null` means "bare hull
     * everywhere", which is what a test wall is and what the shape of the contact does not depend on.
     */
    deck: DeckArray? = null,
) {
    val half = Flight.PER_TILE / 2L
    for (cy in 0 until body.height) {
        for (cx in 0 until body.width) {
            val cell = cy * body.width + cx
            if (!body.cells[cell]) continue
            val shape = body.shapeAt(cell)
            val centreX = at.toWorldX(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half)
            val centreY = at.toWorldY(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half)
            // ── The search, and only the search, happens in the grid ──────────────
            //
            // Which tiles could this cell be touching is a question about tile indices, so the
            // cell's centre is read back into the grid to ask it. ⚠️ The ±[reach] box around that
            // centre is exact **because the cell is a disc**: a disc's bounding box is the same in
            // every frame. A turned box cell would need its bound widened here before the search
            // could be trusted, and that is the one thing to remember when `CellShape.Box` cells
            // arrive on a body.
            val localX = shipPose.toLocalX(centreX, centreY)
            val localY = shipPose.toLocalY(centreX, centreY)
            // The shape's own bounding box, which for a disc of half a tile is the cell it came from.
            val reach = shapeReach(shape)
            val tx0 = floorTileOf(localX - reach)
            val ty0 = floorTileOf(localY - reach)
            val tx1 = floorTileOf(localX + reach - 1L)
            val ty1 = floorTileOf(localY + reach - 1L)
            for (ty in ty0..ty1) {
                if (ty < 0 || ty >= grid.height) continue
                for (tx in tx0..tx1) {
                    if (tx < 0 || tx >= grid.width) continue
                    val tile = grid.tile(tx.toInt(), ty.toInt())
                    // What a rock hits, not what holds the air: a thruster is open to the room and still
                    // something an asteroid bounces off. See [StructureMap.blocksPassage].
                    if (!structure.blocksPassage(tile)) continue
                    // The tile goes out into the world to be answered, and it takes the ship's
                    // pose with it as the frame it is square in — which is the whole of what the
                    // grid frame used to buy, bought per query instead of imposed on everybody.
                    val tileX = tx * Flight.PER_TILE + half
                    val tileY = ty * Flight.PER_TILE + half
                    contactBetween(
                        a = shape, ax = centreX, ay = centreY,
                        b = CellShape.TILE,
                        bx = shipPose.toWorldX(tileX, tileY), by = shipPose.toWorldY(tileX, tileY),
                        body = index,
                        restingSpeedX = restingSpeedX, restingSpeedY = restingSpeedY,
                        friction = frictionBetween(body, cell, deck?.get(tile)),
                        into = into,
                        bFrame = shipPose,
                    )
                }
            }
        }
    }
}

/**
 * Every place two **bodies** touch each other — step 5 of `PLAN_rigid_bodies.md`, and the first
 * collision in the game that does not involve the ship at all.
 *
 * Both are grids of discs, so the narrow phase is Disc-vs-Disc for every pair of cells that reach
 * each other, and it is exact at every angle of either body for the same reason the hull pair is: a
 * disc has no orientation to get wrong. The normal comes out pointing **out of [b] and into [a]**,
 * which is the direction [Contact] promises, so [a] is pushed along it and [b] against it.
 *
 * ### The broad phase, such as it is
 *
 * Two rejections, both bounding circles: the pair as wholes, then cell against cell. A rock is a
 * couple of dozen cells and a scene is a handful of rocks, so the quadratic inside a rejected-pair
 * test is a few hundred compares a tick — cheaper than the tile walk the hull side already does per
 * cell. Stu's call on the hull was *colliders for every tile, optimise later*, and this is the same
 * call for the same reason: a spatial index is invisible above the broad phase, so it stays cheap to
 * add and costs nothing to defer.
 *
 * ⚠️ Emitted **once per pair**, with the lower index as [a]. Emitting both orderings would double
 * every impulse in a stack, and it would do it silently — a body would simply be twice as springy as
 * the one under it.
 */
fun collectBodyContacts(
    a: RigidBody,
    aIndex: Int,
    aAt: Pose,
    b: RigidBody,
    bIndex: Int,
    bAt: Pose,
    restingSpeedX: Long,
    restingSpeedY: Long,
    into: MutableList<Contact>,
) {
    val half = Flight.PER_TILE / 2L
    val aCentreX = aAt.toWorldX(a.width * Flight.PER_TILE / 2L, a.height * Flight.PER_TILE / 2L)
    val aCentreY = aAt.toWorldY(a.width * Flight.PER_TILE / 2L, a.height * Flight.PER_TILE / 2L)
    val bCentreX = bAt.toWorldX(b.width * Flight.PER_TILE / 2L, b.height * Flight.PER_TILE / 2L)
    val bCentreY = bAt.toWorldY(b.width * Flight.PER_TILE / 2L, b.height * Flight.PER_TILE / 2L)
    // ⚠️ Differences before any multiply, and this is the §5.3 hazard in its purest form: these are
    // world-ish grid coordinates, and a product of two of them leaves `Long` three tiles from the
    // origin.
    if (!withinReach(aCentreX - bCentreX, aCentreY - bCentreY, a.boundRadius + b.boundRadius)) return

    for (ay in 0 until a.height) {
        for (ax in 0 until a.width) {
            val aCell = ay * a.width + ax
            if (!a.cells[aCell]) continue
            val aShape = a.shapeAt(aCell)
            val axx = aAt.toWorldX(ax * Flight.PER_TILE + half, ay * Flight.PER_TILE + half)
            val ayy = aAt.toWorldY(ax * Flight.PER_TILE + half, ay * Flight.PER_TILE + half)
            for (by in 0 until b.height) {
                for (bx in 0 until b.width) {
                    val bCell = by * b.width + bx
                    if (!b.cells[bCell]) continue
                    val bShape = b.shapeAt(bCell)
                    val bxx = bAt.toWorldX(bx * Flight.PER_TILE + half, by * Flight.PER_TILE + half)
                    val byy = bAt.toWorldY(bx * Flight.PER_TILE + half, by * Flight.PER_TILE + half)
                    if (!withinReach(axx - bxx, ayy - byy, shapeReach(aShape) + shapeReach(bShape))) continue
                    contactBetween(
                        a = aShape, ax = axx, ay = ayy,
                        b = bShape, bx = bxx, by = byy,
                        body = aIndex, other = bIndex,
                        restingSpeedX = restingSpeedX, restingSpeedY = restingSpeedY,
                        friction = frictionBetween(a, aCell, b, bCell),
                        into = into,
                    )
                }
            }
        }
    }
}

/**
 * Do two bodies overlap at all — the boolean half of [collectBodyContacts], and it must agree with
 * it for the same reason [overlapBetween] must agree with [contactBetween]: a body that this says is
 * interpenetrating is eased out on a budget rather than bounced, and a `true` the narrow phase does
 * not back up with a contact is a body being told to wait for a push that never comes.
 */
fun bodiesOverlap(a: RigidBody, aAt: Pose, b: RigidBody, bAt: Pose): Boolean {
    val half = Flight.PER_TILE / 2L
    val aCentreX = aAt.toWorldX(a.width * Flight.PER_TILE / 2L, a.height * Flight.PER_TILE / 2L)
    val aCentreY = aAt.toWorldY(a.width * Flight.PER_TILE / 2L, a.height * Flight.PER_TILE / 2L)
    val bCentreX = bAt.toWorldX(b.width * Flight.PER_TILE / 2L, b.height * Flight.PER_TILE / 2L)
    val bCentreY = bAt.toWorldY(b.width * Flight.PER_TILE / 2L, b.height * Flight.PER_TILE / 2L)
    if (!withinReach(aCentreX - bCentreX, aCentreY - bCentreY, a.boundRadius + b.boundRadius)) {
        return false
    }
    for (ay in 0 until a.height) {
        for (ax in 0 until a.width) {
            val aCell = ay * a.width + ax
            if (!a.cells[aCell]) continue
            val aShape = a.shapeAt(aCell)
            val axx = aAt.toWorldX(ax * Flight.PER_TILE + half, ay * Flight.PER_TILE + half)
            val ayy = aAt.toWorldY(ax * Flight.PER_TILE + half, ay * Flight.PER_TILE + half)
            for (by in 0 until b.height) {
                for (bx in 0 until b.width) {
                    val bCell = by * b.width + bx
                    if (!b.cells[bCell]) continue
                    val bShape = b.shapeAt(bCell)
                    val bxx = bAt.toWorldX(bx * Flight.PER_TILE + half, by * Flight.PER_TILE + half)
                    val byy = bAt.toWorldY(bx * Flight.PER_TILE + half, by * Flight.PER_TILE + half)
                    if (overlapBetween(aShape, axx, ayy, bShape, bxx, byy)) return true
                }
            }
        }
    }
    return false
}

/**
 * The friction of one cell of a body against one tile of the ship — **from what the two are made
 * of**, and looked up per contact rather than read from a constant by the solver.
 *
 * Each side answers with its own [Material.roughness] and [pairRoughness] combines them, so the
 * ordering the game actually shows is metal on metal sliding, rock on metal gripping a little
 * harder, and rock on rock gripping hardest. A rock that lands on a steel deck settles; the same
 * rock landing on a smelter's firebrick lining stops sooner.
 *
 * ⚠️ [machine] is the machine occupying the tile, and `null` means bare hull. It is threaded down
 * here from the vessel for this one question, because [StructureMap] deliberately does not carry it
 * — it knows a tile is solid and not what kind of solid, which is all every other reader of it
 * needs.
 */
fun frictionBetween(body: RigidBody, cell: Int, machine: DeckMachine?): Long =
    pairRoughness(roughnessOfBody(body), (machine?.kind ?: DeckMachineKind.Hull).material.roughness)

/**
 * The friction of one body's cell against **another body's** cell — the same lookup, both sides now
 * being rubble.
 *
 * It is a separate entry point rather than a default argument because the two questions differ in
 * what they are given, not in what they compute: a hull side is a tile's machine and a body side is
 * a composition. What they share is [pairRoughness], which is where the answer actually comes from,
 * and which is the thing that must stay single so that mixture-dependent friction lands in one
 * place later.
 *
 * Rock on rock is the roughest pair the table produces, which is what makes a heap of rubble
 * behave like a heap rather than like a bag of ball bearings.
 */
fun frictionBetween(a: RigidBody, aCell: Int, b: RigidBody, bCell: Int): Long =
    pairRoughness(roughnessOfBody(a), roughnessOfBody(b))

/** What a body's surface is like, from what it is made of. */
private fun roughnessOfBody(body: RigidBody): Long = when (body.kind) {
    BodyKind.ROCK -> roughnessOf(body.oreComposition ?: Mixture.EMPTY)
    BodyKind.FRAGMENT -> body.machineKind!!.material.roughness
}

/**
 * Are two circles of combined radius [reach], [dx] and [dy] apart, close enough to be worth a narrow
 * phase?
 *
 * ⚠️ **Reduced to millitiles before the squares**, and this is §5.3 rather than tidiness: a gap
 * across a thirty-tile grid is 3e10 and its square is 9e20, so the obvious form wraps and answers
 * *no* for a pair that is touching — a rejection that reads, as ever, like an absence. Truncating the
 * gap toward zero and rounding the reach up makes the test **inclusive** where it is inexact, which
 * is the only direction a broad phase may err in.
 */
private fun withinReach(dx: Long, dy: Long, reach: Long): Boolean {
    val mx = dx / RigidBody.COM_SCALE
    val my = dy / RigidBody.COM_SCALE
    val r = reach / RigidBody.COM_SCALE + 1L
    return mx * mx + my * my < r * r
}

/** Integer floor division, which is not what `/` does for negatives — and a body goes negative. */
internal fun floorTileOf(v: Long): Long =
    if (v >= 0L) v / Flight.PER_TILE else -((-v + Flight.PER_TILE - 1L) / Flight.PER_TILE)

/**
 * One side of a contact: **a mass distribution and a motion**, which is all the solver is allowed to
 * know about anything.
 *
 * The vessel is one of these and so is a rock, and neither can tell which it is from in here. That is
 * §3.3 of `PLAN_rigid_bodies.md` made mechanical rather than aspirational: when step 6 passes the
 * vessel through this as an ordinary operand, nothing in this file changes, because there is nothing
 * in this file that could notice.
 *
 * Mutable, and read back by the caller after the solve. The velocities are the working state the
 * iteration converges; [gaveX], [gaveY] and [spun] are what came out, for the ledger.
 */
class Operand(
    val mass: Long,
    /** Where its mass is, in **its own** frame — [comX] states where that lands in the grid. */
    val about: MassDistribution,
    /** Its centre of mass in the grid frame, at [Flight.PER_TILE] to the tile. */
    val comX: Long,
    val comY: Long,
    /** Grid-frame velocity, per tick. Mutated by the solve. */
    var velocityX: Long,
    var velocityY: Long,
    /** [Coord] raw per tick. Mutated by the solve. */
    var angVel: Long,
) {
    /** Total linear momentum handed to it, grid axes — the ledger's half of the exchange. */
    var gaveX: Long = 0L
    var gaveY: Long = 0L

    /** Total angular momentum handed to it, about its own [comX] — the same, for the twist. */
    var spun: Long = 0L

    /**
     * How much mass this operand presents to a push at ([rx], [ry]) along ([nx], [ny]) — the
     * quantity a bounce is actually sized against.
     *
     * `1/m_eff = 1/m + (r×n)²/I`, which with `I = m·k²` collapses to `m_eff = m·k²/(k² + (r×n)²)`.
     * **The mass cancels out of the correction entirely**, leaving a dimensionless ratio of two
     * squared lengths, and that is the only reason this is computable in integers at all: written
     * with `I` in it the expression is quadratic in the mass unit, and a mass unit is a microgram.
     *
     * A body struck **through** its centre of mass has `r×n = 0` and presents its whole mass. Struck
     * on a long arm it presents almost none, which is why a glancing blow on a nacelle spins a ship
     * instead of shoving it.
     */
    fun effectiveMass(rx: Long, ry: Long, nx: Long, ny: Long): Long {
        val kSq = about.gyrationSq
        if (kSq <= 0L) return mass
        // Millitiles crossed with a unit normal: ≤1e5 against 2.1e9, so this product is safe as it
        // stands and does not need [rotScale]'s reduction.
        val cross = (rx * ny - ry * nx) / Flight.FRAC_ONE
        if (cross == 0L) return mass
        // ⚠️ **The mass is the numerator, not the scale**, and the two orderings are the same
        // fraction with very different range. [scaledRatio] carries its remainder as
        // `n % d * scale / d`, so putting the *gyration* on top makes that term `k² × m` — at a
        // microgram per unit that is 2e6 × 8.3e13 = 1.7e20, which wraps, and a wrapped effective
        // mass reads as an absence: the impulse sized against it is zero and the contact does
        // nothing. `PLAN_rigid_bodies.md` §6 called this one in advance — *"the angular version is
        // worse: it is quadratic in mass **and** carries an r²"*.
        //
        // With the mass as the numerator the remainder is `(m mod d) × k²`, and `m mod d` is bounded
        // by the denominator rather than by the mass, so the product cannot run away.
        //
        // It hid for a whole step because the **normal** never reaches here: a contact answered
        // along its own normal has `cross == 0` and returns above. Only friction, which is sized
        // across the tangent where the arm is longest, ever asked the question — and got a zero.
        return scaledRatio(mass, kSq + cross * cross, kSq)
    }

    /**
     * How fast the point at arm ([rx], [ry]) is moving — its own velocity plus `ω × r`.
     *
     * ⚠️ **The arm is converted before it is used, and the conversion is not optional.** [spinSpeed]
     * answers in whatever unit its radius came in, and an arm here is in millitiles ([Rotation.MILLI_TILE],
     * a thousand to the tile) while a velocity is in [Flight.PER_TILE]s, a billion to the tile. Left
     * unconverted the spin term arrives a **million times too small**, which is not a spin term that
     * is slightly wrong — it is one that is not there.
     *
     * It survived step 3 because the test that covers it, `a spinning body still closes on a contact
     * its centre is not approaching`, asserts only that *something* was booked, and a millionth of
     * the right impulse is still not zero. Step 4 is what made it visible: friction is a tangential
     * impulse sized entirely on the sliding speed, and at a millionth of it a spinning rock ground
     * against a deck for ever with the friction switched on.
     */
    fun pointVelocityX(rx: Long, ry: Long): Long = velocityX - spinSpeed(angVel, inTiles(ry))

    fun pointVelocityY(rx: Long, ry: Long): Long = velocityY + spinSpeed(angVel, inTiles(rx))

    /** A millitile arm in the units a velocity is measured in. See [pointVelocityX]. */
    private fun inTiles(rMilli: Long): Long = rMilli * (Flight.PER_TILE / Rotation.MILLI_TILE)

    /** Take an impulse at arm ([rx], [ry]) in millitiles: linear straight in, angular as `r × J`. */
    fun apply(rx: Long, ry: Long, jx: Long, jy: Long) {
        velocityX += scaledRatio(jx, mass, Flight.PER_TILE)
        velocityY += scaledRatio(jy, mass, Flight.PER_TILE)
        gaveX += jx
        gaveY += jy
        val torque = momentOf(rx, jy) - momentOf(ry, jx)
        if (torque != 0L) {
            spun += torque
            angVel += angularVelocity(torque, about)
        }
    }
}

/**
 * `r × J` for one component pair: a lever arm in millitiles times an impulse, in tiles.
 *
 * ⚠️ Not `r * j / MILLI_TILE`. An 83-tonne rock at a microgram per unit carries an impulse around
 * 1e14, and a hundred-tile arm is 1e5 millitiles — the plain product is 1e19 and leaves the range.
 * Reducing before the multiply is the same discipline [rotScale] applies, for the same reason, and
 * `PLAN_rigid_bodies.md` §5.3 names it as the single most likely thing to go wrong in this work.
 */
private fun momentOf(rMilli: Long, j: Long): Long {
    if (rMilli == 0L || j == 0L) return 0L
    val magnitude = scaledRatio(
        numerator = if (rMilli < 0L) -rMilli else rMilli,
        denominator = Rotation.MILLI_TILE,
        scale = if (j < 0L) -j else j,
    )
    return if ((rMilli < 0L) == (j < 0L)) magnitude else -magnitude
}

/**
 * Resolve a whole list of [contacts] together, [iterations] times over.
 *
 * **Together is the point.** The code this replaces resolved one axis of one body inline, in the
 * middle of moving it, so a body wedged in a corner had its two walls answered in sequence: the
 * second push was computed against a velocity the first had already spent, and the pair argued. A
 * list solved repeatedly converges on an answer that satisfies every contact at once, which is the
 * property that stacking will need — and it needs it for the same reason a corner does.
 *
 * ⚠️ Iterated impulses, not a simultaneous solve. Each pass refines the last, so more iterations is
 * more correct and never less stable; [DEFAULT_ITERATIONS] is where a corner stops arguing.
 *
 * ⚠️ **Each pass reads a frozen state and writes at the end of it** — Jacobi, not Gauss-Seidel. That
 * is a step-3 change and the reason is symmetry, not speed; see the note in the loop. It also means
 * the *order* of the list stops mattering to the answer, which removes a whole class of determinism
 * hazard rather than merely documenting it — though the list is still built by body index and then
 * by cell, because the accumulators are summed in it.
 *
 * ⚠️ Restitution is captured **before the first pass**, from the speed each contact arrived at, and
 * held. Recomputed per pass it would be applied to a closing speed an earlier pass had already
 * spent, and a manifold of several touches would converge on no bounce at all.
 *
 * @param ship the other operand, or `null` for an immovable one of infinite mass.
 * @param impacts if given and sized [contacts]`.size`, filled with the normal impulse each contact
 *   ended up spending — but **only for the contacts that arrived closing fast enough to bounce**,
 *   and zero everywhere else. That is what makes it a *collision* report rather than a load report:
 *   a rock lying on the deck is held up by a large impulse every tick of its life, and a reading
 *   that included it would be a continuous roar. See [driftBodies] for what listens.
 */
fun solveContacts(
    contacts: List<Contact>,
    bodies: List<Operand>,
    ship: Operand?,
    iterations: Int = DEFAULT_ITERATIONS,
    impacts: LongArray? = null,
) {
    if (impacts != null) impacts.fill(0L)
    if (contacts.isEmpty()) return
    val n = contacts.size

    /**
     * The other side of a contact — the vessel, or another body, and the solver cannot tell.
     *
     * This one function is the whole of what step 5 added to this file, which is the property §3.1
     * of the plan was written to buy: a contact is a point, a normal and a depth, and *who* it is
     * between is an index the resolver looks up rather than a case it branches on.
     */
    fun otherOf(c: Contact): Operand? =
        if (c.other == Contact.HULL) ship else bodies[c.other].takeIf { it.mass > 0L }

    // Arms and effective masses are fixed for the whole solve — the geometry does not move while
    // the velocities are being argued over — so they are worked out once. §5.3's reduction to
    // millitiles happens here and nowhere else.
    val arx = LongArray(n)
    val ary = LongArray(n)
    val brx = LongArray(n)
    val bry = LongArray(n)
    val mu = LongArray(n)
    val muTangent = LongArray(n)
    val share = LongArray(n)
    val target = LongArray(n)
    val accumulated = LongArray(n)
    val accumulatedTangent = LongArray(n)
    val step = LongArray(n)
    val stepTangent = LongArray(n)

    // ⚠️ Counted on **both** sides of every contact. A rock with a rock on top of it is pushed by
    // that touch just as hard as the one on top is, and a share that only counted the contacts
    // naming a body as its first operand would let the body underneath take a whole impulse from
    // each of the several touches resting on it and fire it out of the stack.
    val onBody = HashMap<Int, Long>()
    for (c in contacts) {
        onBody[c.body] = (onBody[c.body] ?: 0L) + 1L
        if (c.other != Contact.HULL) onBody[c.other] = (onBody[c.other] ?: 0L) + 1L
    }

    for (i in 0 until n) {
        val c = contacts[i]
        val body = bodies[c.body]
        val other = otherOf(c)
        arx[i] = (c.pointX - body.comX) / RigidBody.COM_SCALE
        ary[i] = (c.pointY - body.comY) / RigidBody.COM_SCALE
        brx[i] = if (other == null) 0L else (c.pointX - other.comX) / RigidBody.COM_SCALE
        bry[i] = if (other == null) 0L else (c.pointY - other.comY) / RigidBody.COM_SCALE
        share[i] = maxOf(
            onBody[c.body] ?: 1L,
            if (c.other == Contact.HULL) 1L else onBody[c.other] ?: 1L,
        )

        val ma = body.effectiveMass(arx[i], ary[i], c.normalX, c.normalY)
        mu[i] = if (other == null || other.mass <= 0L) ma else {
            val mb = other.effectiveMass(brx[i], bry[i], c.normalX, c.normalY)
            scaledRatio(ma, ma + mb, mb)
        }
        // The same quantity along the tangent, and it is genuinely a different number: the arm that
        // is short across a normal is long across the tangent perpendicular to it, which is exactly
        // why friction spins a body up or stops it spinning rather than merely slowing it down.
        val ta = body.effectiveMass(arx[i], ary[i], -c.normalY, c.normalX)
        muTangent[i] = if (other == null || other.mass <= 0L) ta else {
            val tb = other.effectiveMass(brx[i], bry[i], -c.normalY, c.normalX)
            scaledRatio(ta, ta + tb, tb)
        }

        // The bounce is captured **now**, from the speed it arrived at, and then never recomputed.
        // That is what makes restitution survive being solved iteratively: recomputed each pass it
        // would be applied to a closing speed the previous pass had already spent, and a manifold of
        // several touches would converge on zero bounce instead of on half of one.
        val closing = closingAt(contacts[i], bodies[c.body], other, arx[i], ary[i], brx[i], bry[i])
        val speed = if (closing < 0L) -closing else closing
        target[i] = if (closing >= 0L || speed < c.restingSpeed) 0L
        else scaledRatio(RockContact.RESTITUTION_NUM, RockContact.RESTITUTION_DEN, speed)
    }

    // Which contacts arrived as a *collision* rather than as a weight already resting. Captured
    // beside [target] because it is the same question: a touch the restitution pass declined to
    // bounce is a touch that was already there, and a rock lying on the deck must not be audible.
    val struck = if (impacts == null) null else BooleanArray(n) { target[it] > 0L }

    run { repeat(iterations) {
        // ── Every contact answered from the same starting velocities ──────────────
        //
        // ⚠️ **Jacobi, not Gauss-Seidel, and the difference is not convergence speed — it is
        // symmetry.** Answered one after another, each touch sees the spin the touches before it put
        // on the body, so a symmetric blob hitting a flat wall head-on gets three unequal impulses
        // and comes away turning. Measured, before this was written: a 5×5 rock thrown square at a
        // bulkhead left it spinning at 0.04 radians a tick and rebounded at 30% of its approach
        // instead of 50%, with the missing energy in the spin. It is not a small error and no number
        // of extra passes removes it, because the state it lands in satisfies every constraint —
        // it is simply the wrong one.
        //
        // Computed from a frozen state, identical contacts get identical impulses, their torques
        // cancel exactly, and the symmetry the body started with is the symmetry it keeps.
        for (i in 0 until n) {
            val c = contacts[i]
            val body = bodies[c.body]
            if (body.mass <= 0L) { step[i] = 0L; stepTangent[i] = 0L; continue }
            val other = otherOf(c)
            val closing = closingAt(c, body, other, arx[i], ary[i], brx[i], bry[i])
            // What this touch still needs: to be separating at [target], rather than closing.
            val wanted = target[i] - closing
            // Shared out, because every contact on this body is about to push it at once and each
            // of them sized its push against the whole of the body's motion.
            val raw = if (wanted <= 0L) 0L else scaledRatio(mu[i], Flight.PER_TILE * share[i], wanted)
            // ⚠️ A contact may push and may not pull. Clamped on the **accumulated** impulse rather
            // than on this pass's, so a later pass can take back a push an earlier one over-gave
            // without ever leaving the body glued to the wall.
            val next = maxOf(0L, accumulated[i] + raw)
            step[i] = next - accumulated[i]
            accumulated[i] = next

            // ── Friction, across the same touch ───────────────────────────────────
            //
            // The tangent is the normal turned a quarter turn. What friction wants is for the two
            // surfaces to stop sliding over each other, so the impulse it asks for is whatever
            // cancels the sliding — and then Coulomb says it may not have more than `μ` times what
            // is pressing them together.
            if (c.friction <= 0L) { stepTangent[i] = 0L; continue }
            val sliding = slidingAt(c, body, other, arx[i], ary[i], brx[i], bry[i])
            // ⚠️ **Signed, and [scaledRatio] is not.** It returns zero for a negative `scale` — a
            // deliberate guard, and harmless on the normal above, where `wanted <= 0` is filtered
            // out one line earlier so the argument is always positive. A *sliding* speed has no such
            // sign: it is negative whenever the surfaces are sliding the other way, which is half
            // the time. Passed straight in, friction was silently zero in one of the two directions
            // and the spin it was meant to take off a body simply stayed there. Magnitude in, sign
            // reapplied after, the way [spinSpeed] and [momentOf] already do it.
            val slide = if (sliding < 0L) -sliding else sliding
            val magnitudeT = scaledRatio(muTangent[i], Flight.PER_TILE * share[i], slide)
            val rawT = if (sliding < 0L) magnitudeT else -magnitudeT
            // ⚠️ The cone is on the **accumulated** pair, not on this pass's. Clamped per pass, a
            // contact that has been pressing for several passes could keep spending a fresh
            // allowance each time and drag harder than the surfaces are being held together.
            val limit = rotScale(accumulated[i], c.friction)
            val nextT = clampTo(accumulatedTangent[i] + rawT, limit)
            stepTangent[i] = nextT - accumulatedTangent[i]
            accumulatedTangent[i] = nextT
        }

        // ── And then all of them applied ──────────────────────────────────────────
        var moved = false
        for (i in 0 until n) {
            val j = step[i]
            val jt = stepTangent[i]
            if (j == 0L && jt == 0L) continue
            moved = true
            val c = contacts[i]
            // Normal plus tangent, as one impulse: the tangent is the normal turned a quarter turn,
            // so `t = (−n_y, n_x)`.
            val jx = rotScale(j, c.normalX) - rotScale(jt, c.normalY)
            val jy = rotScale(j, c.normalY) + rotScale(jt, c.normalX)
            bodies[c.body].apply(arx[i], ary[i], jx, jy)
            otherOf(c)?.takeIf { it.mass > 0L }?.apply(brx[i], bry[i], -jx, -jy)
        }
        if (!moved) return@run
    } }

    // ⚠️ Reported **after** the whole solve, off the accumulated normal impulse rather than off any
    // one pass's. A manifold converges by taking impulse back from contacts an earlier pass
    // over-gave, so a per-pass sum reports a collision louder than it was — sometimes several times
    // over, on exactly the many-touch landings that are already the loudest.
    if (impacts != null && struck != null) {
        for (i in 0 until n) if (struck[i]) impacts[i] = accumulated[i]
    }
}

/** Held to `±[limit]`, which is the whole of Coulomb's law once the two impulses are in hand. */
private fun clampTo(value: Long, limit: Long): Long =
    if (value > limit) limit else if (value < -limit) -limit else value

/**
 * How fast the two sides of [c] are sliding **across** each other at the contact point.
 *
 * The tangent is the normal turned a quarter turn, `t = (−n_y, n_x)`. A rock spinning on a deck it
 * is not otherwise moving along has a sliding speed and no closing speed at all, which is the case
 * friction exists for and the one that finally lets a landed rock stop cartwheeling.
 */
private fun slidingAt(
    c: Contact,
    body: Operand,
    other: Operand?,
    arx: Long,
    ary: Long,
    brx: Long,
    bry: Long,
): Long {
    val relX = body.pointVelocityX(arx, ary) - (other?.pointVelocityX(brx, bry) ?: 0L)
    val relY = body.pointVelocityY(arx, ary) - (other?.pointVelocityY(brx, bry) ?: 0L)
    return rotScale(relX, -c.normalY) + rotScale(relY, c.normalX)
}

/** The speed the two sides of [c] are closing on each other **at the contact point**. */
private fun closingAt(
    c: Contact,
    body: Operand,
    other: Operand?,
    arx: Long,
    ary: Long,
    brx: Long,
    bry: Long,
): Long {
    // At the point, not at the centres — which is the whole of what an angular half changes about
    // the linear solve. A rock already spinning presents a different closing speed at each of its
    // contacts, and a rock skimming a wall approaches it at one corner while leaving it at the other.
    val relX = body.pointVelocityX(arx, ary) - (other?.pointVelocityX(brx, bry) ?: 0L)
    val relY = body.pointVelocityY(arx, ary) - (other?.pointVelocityY(brx, bry) ?: 0L)
    return rotScale(relX, c.normalX) + rotScale(relY, c.normalY)
}

/**
 * How many times the contact list is swept.
 *
 * Eight, because the sharing that keeps a manifold symmetric also means one pass only delivers a
 * fraction of what a contact needs, and the remainder halves or better on each pass after it. Four
 * was enough when a contact took its whole impulse in one go; it is not enough now, and the cost of
 * a pass is a handful of multiplies over a list that is rarely longer than five. Raise it again when
 * stacking arrives and a tower is more than two contacts deep.
 *
 * A solve that has nothing left to give returns early, so the common case — a body resting quietly
 * on the deck — pays for one pass and not eight.
 */
const val DEFAULT_ITERATIONS = 8
