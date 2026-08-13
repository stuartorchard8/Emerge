package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio

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
)

/**
 * Every place [body] — placed in the grid by [at] — is inside the hull.
 *
 * One contact per cell-and-tile pair that overlaps, with the normal along the **axis of least
 * penetration** and the point at the centre of the overlap. That is the honest reading of a
 * square-against-square touch: the shallow axis is the one it came in through, and the deep axis
 * would push it sideways out of a wall it is resting against.
 *
 * ### ⚠️ The cell boxes do not turn with the body, and that is deliberate
 *
 * [at] carries the body's orientation and it moves every cell **centre** to where the body's angle
 * puts it, which is the whole of what step 3 needs: a contact off the centre of mass now has a real
 * lever arm, and the body spins about it. What it does not do is turn the cell's own square, which
 * stays axis-aligned.
 *
 * That is an approximation for exactly one more step. Step 4 of `PLAN_rigid_bodies.md` makes a cell
 * a **disc**, and a disc at a rotated centre is not an approximation of anything — it is exact at
 * every angle, because a disc has no orientation to get wrong. So this converges on the right answer
 * rather than having to be unpicked, and it is why discs were chosen for bodies in the first place.
 * The error meanwhile is bounded by the corner of a square against its inscribed circle, 0.207 tile,
 * and it is worst at 45° and zero at every right angle.
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
    at: Pose,
    restingSpeedX: Long,
    restingSpeedY: Long,
    into: MutableList<Contact>,
) {
    val half = Flight.PER_TILE / 2L
    for (cy in 0 until body.height) {
        for (cx in 0 until body.width) {
            if (!body.cells[cy * body.width + cx]) continue
            // The centre turns with the body; the box around it does not. See the note above.
            val centreX = at.toWorldX(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half)
            val centreY = at.toWorldY(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half)
            val x0 = centreX - half
            val y0 = centreY - half
            val x1 = x0 + Flight.PER_TILE
            val y1 = y0 + Flight.PER_TILE
            val tx0 = floorTileOf(x0)
            val ty0 = floorTileOf(y0)
            val tx1 = floorTileOf(x1 - 1L)
            val ty1 = floorTileOf(y1 - 1L)
            for (ty in ty0..ty1) {
                if (ty < 0 || ty >= grid.height) continue
                for (tx in tx0..tx1) {
                    if (tx < 0 || tx >= grid.width) continue
                    if (!structure.isImpermeable(grid.index(tx.toInt(), ty.toInt()))) continue

                    val wallX0 = tx * Flight.PER_TILE
                    val wallY0 = ty * Flight.PER_TILE
                    val overlapX0 = maxOf(x0, wallX0)
                    val overlapY0 = maxOf(y0, wallY0)
                    val overlapX1 = minOf(x1, wallX0 + Flight.PER_TILE)
                    val overlapY1 = minOf(y1, wallY0 + Flight.PER_TILE)
                    val spanX = overlapX1 - overlapX0
                    val spanY = overlapY1 - overlapY0
                    if (spanX <= 0L || spanY <= 0L) continue

                    // The shallow axis is the way in. On an exact tie the sign decides nothing and
                    // either axis is correct, so x is taken and the choice is deterministic.
                    val alongX = spanX <= spanY
                    val cellCentreX = x0 + Flight.PER_TILE / 2L
                    val cellCentreY = y0 + Flight.PER_TILE / 2L
                    val wallCentreX = wallX0 + Flight.PER_TILE / 2L
                    val wallCentreY = wallY0 + Flight.PER_TILE / 2L
                    val sign = if (alongX) {
                        if (cellCentreX >= wallCentreX) 1L else -1L
                    } else {
                        if (cellCentreY >= wallCentreY) 1L else -1L
                    }
                    into.add(
                        Contact(
                            body = index,
                            pointX = (overlapX0 + overlapX1) / 2L,
                            pointY = (overlapY0 + overlapY1) / 2L,
                            normalX = if (alongX) sign * Flight.FRAC_ONE else 0L,
                            normalY = if (alongX) 0L else sign * Flight.FRAC_ONE,
                            depth = if (alongX) spanX else spanY,
                            restingSpeed = if (alongX) restingSpeedX else restingSpeedY,
                        ),
                    )
                }
            }
        }
    }
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
        return scaledRatio(kSq, kSq + cross * cross, mass)
    }

    /** How fast the point at arm ([rx], [ry]) is moving — its own velocity plus `ω × r`. */
    fun pointVelocityX(rx: Long, ry: Long): Long = velocityX - spinSpeed(angVel, ry)

    fun pointVelocityY(rx: Long, ry: Long): Long = velocityY + spinSpeed(angVel, rx)

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
 */
fun solveContacts(
    contacts: List<Contact>,
    bodies: List<Operand>,
    ship: Operand?,
    iterations: Int = DEFAULT_ITERATIONS,
) {
    if (contacts.isEmpty()) return
    val n = contacts.size

    // Arms and effective masses are fixed for the whole solve — the geometry does not move while
    // the velocities are being argued over — so they are worked out once. §5.3's reduction to
    // millitiles happens here and nowhere else.
    val arx = LongArray(n)
    val ary = LongArray(n)
    val brx = LongArray(n)
    val bry = LongArray(n)
    val mu = LongArray(n)
    val share = LongArray(n)
    val target = LongArray(n)
    val accumulated = LongArray(n)
    val step = LongArray(n)

    val onBody = HashMap<Int, Long>()
    for (c in contacts) onBody[c.body] = (onBody[c.body] ?: 0L) + 1L

    for (i in 0 until n) {
        val c = contacts[i]
        val body = bodies[c.body]
        arx[i] = (c.pointX - body.comX) / RigidBody.COM_SCALE
        ary[i] = (c.pointY - body.comY) / RigidBody.COM_SCALE
        brx[i] = if (ship == null) 0L else (c.pointX - ship.comX) / RigidBody.COM_SCALE
        bry[i] = if (ship == null) 0L else (c.pointY - ship.comY) / RigidBody.COM_SCALE
        share[i] = onBody[c.body] ?: 1L

        val ma = body.effectiveMass(arx[i], ary[i], c.normalX, c.normalY)
        mu[i] = if (ship == null || ship.mass <= 0L) ma else {
            val mb = ship.effectiveMass(brx[i], bry[i], c.normalX, c.normalY)
            scaledRatio(ma, ma + mb, mb)
        }

        // The bounce is captured **now**, from the speed it arrived at, and then never recomputed.
        // That is what makes restitution survive being solved iteratively: recomputed each pass it
        // would be applied to a closing speed the previous pass had already spent, and a manifold of
        // several touches would converge on zero bounce instead of on half of one.
        val closing = closingAt(contacts[i], bodies[c.body], ship, arx[i], ary[i], brx[i], bry[i])
        val speed = if (closing < 0L) -closing else closing
        target[i] = if (closing >= 0L || speed < c.restingSpeed) 0L
        else scaledRatio(RockContact.RESTITUTION_NUM, RockContact.RESTITUTION_DEN, speed)
    }

    repeat(iterations) {
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
            if (body.mass <= 0L) { step[i] = 0L; continue }
            val closing = closingAt(c, body, ship, arx[i], ary[i], brx[i], bry[i])
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
        }

        // ── And then all of them applied ──────────────────────────────────────────
        var moved = false
        for (i in 0 until n) {
            val j = step[i]
            if (j == 0L) continue
            moved = true
            val c = contacts[i]
            val jx = rotScale(j, c.normalX)
            val jy = rotScale(j, c.normalY)
            bodies[c.body].apply(arx[i], ary[i], jx, jy)
            ship?.takeIf { it.mass > 0L }?.apply(brx[i], bry[i], -jx, -jy)
        }
        if (!moved) return
    }
}

/** The speed the two sides of [c] are closing on each other **at the contact point**. */
private fun closingAt(
    c: Contact,
    body: Operand,
    ship: Operand?,
    arx: Long,
    ary: Long,
    brx: Long,
    bry: Long,
): Long {
    // At the point, not at the centres — which is the whole of what an angular half changes about
    // the linear solve. A rock already spinning presents a different closing speed at each of its
    // contacts, and a rock skimming a wall approaches it at one corner while leaving it at the other.
    val relX = body.pointVelocityX(arx, ary) - (ship?.pointVelocityX(brx, bry) ?: 0L)
    val relY = body.pointVelocityY(arx, ary) - (ship?.pointVelocityY(brx, bry) ?: 0L)
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
