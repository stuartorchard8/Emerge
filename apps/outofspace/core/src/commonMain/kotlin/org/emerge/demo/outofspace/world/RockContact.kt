package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Coord

/**
 * Rock-hull contact: the swept overlap test, and the constants a bounce is made of.
 *
 * Ricochet: restitution = 0.5, tuned for legibility rather than measured — rock on steel is nearer
 * 0.2–0.4. Exchange: +J to the body, −J to the ship, with the ledger closed through
 * [VesselState.bodyImpulseX] rather than apologised for.
 *
 * ⚠️ Still **frictionless**, and since step 3 that is a visible gap rather than a simplification: a
 * body now carries a spin, and nothing anywhere takes energy out of one. A rock that lands on a
 * corner cartwheels for ever. Friction arrives with the per-cell shapes at step 4 of
 * `PLAN_rigid_bodies.md`, looked up from the two contacting cells.
 *
 * The normal and the contact point are no longer this file's business — [Contact] owns them, and
 * with them the torque that used to be impossible here.
 */
object RockContact {

    /** The bounce, as a fraction. A half — see the note above on why it is not a measurement. */
    const val RESTITUTION_NUM: Long = 1L
    const val RESTITUTION_DEN: Long = 2L

    /**
     * The most a rock may move in one sub-step, so that a contact cannot be missed by stepping over
     * it: half a tile, which is under the smallest feature a hull has.
     *
     * Tunnelling is much less urgent than it looks — a burn is a quarter of a g and the plating is
     * gone — but a fast approach in H4 is the entire point of H4, and a sweep that only works below
     * some speed is a sweep that fails exactly when the player is doing the interesting thing.
     */
    const val MAX_SUBSTEP: Long = Flight.PER_TILE / 2L

    /**
     * The floor under the resting threshold: a thousandth of a tile per tick.
     *
     * The threshold proper is computed from the gravity — see [restingSpeed] — and in genuine
     * freefall that is zero, which would leave a rock bouncing between two walls forever with its
     * speed halving and never arriving. This is what makes that asymptote terminate.
     */
    const val REST_FLOOR: Long = Flight.PER_TILE / 1000L

    /**
     * Resting threshold: v > a/e (below this, bounce ends in one tick = buzzing).
     * REST_FLOOR = perTick/1000 (terminates asymptote in freefall). One rounding chain (§5g).
     */
    fun restingSpeed(accelerationRaw: Long): Long {
        val a = if (accelerationRaw < 0L) -accelerationRaw else accelerationRaw
        // One chain, one rounding — §5g. Split into a multiply and a divide it would truncate twice,
        // and the second truncation is the one that decides whether a rock is asleep.
        val perTick = a * Flight.PER_TILE * RESTITUTION_DEN / (Flight.FRAC_ONE * RESTITUTION_NUM)
        return if (perTick < REST_FLOOR) REST_FLOOR else perTick
    }
}

/**
 * One body's tick of travel across the grid, stopped and bounced wherever the hull is in the way.
 */
class SweptBody(
    val body: RigidBody,
    val impulseX: Long,
    val impulseY: Long,
    /**
     * The angular momentum the **ship** took from the contacts, about its own centre of mass.
     *
     * Booked at the contact point rather than worked out afterwards from [impulseX] — a total
     * impulse has already lost the positions it was applied at, and the case the whole feature
     * exists for is two touches that cancel linearly and twist hard. Same argument as
     * [torqueAbout]'s, one level down.
     */
    val torque: Long,
)

/** Integer floor division, which is not what `/` does for negatives — and a body goes negative. */
private fun floorTile(v: Long): Long =
    if (v >= 0L) v / Flight.PER_TILE else -((-v + Flight.PER_TILE - 1L) / Flight.PER_TILE)

/**
 * Does [body], placed in the grid by [at], overlap anything solid?
 *
 * ⚠️ [at] is a **grid-frame** pose — a body stores world coordinates, so a caller composes it
 * through [VesselState.pose] first, which is what [RigidBody.poseIn] is for. This function is the
 * boundary: everything below it is tile indices.
 *
 * Cell centres turn with the body and cell boxes do not, exactly as in [collectHullContacts] and for
 * the reason set out there — the two must agree about where a cell is, or a body would be pushed out
 * of a wall this function says it is not in.
 */
fun overlapsHull(grid: Grid, structure: StructureMap, body: RigidBody, at: Pose): Boolean {
    val half = Flight.PER_TILE / 2L
    for (cy in 0 until body.height) {
        for (cx in 0 until body.width) {
            if (!body.cells[cy * body.width + cx]) continue
            val x0 = at.toWorldX(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half) - half
            val y0 = at.toWorldY(cx * Flight.PER_TILE + half, cy * Flight.PER_TILE + half) - half
            val tx0 = floorTile(x0)
            val ty0 = floorTile(y0)
            val tx1 = floorTile(x0 + Flight.PER_TILE - 1L)
            val ty1 = floorTile(y0 + Flight.PER_TILE - 1L)
            for (ty in ty0..ty1) {
                if (ty < 0 || ty >= grid.height) continue
                for (tx in tx0..tx1) {
                    if (tx < 0 || tx >= grid.width) continue
                    if (structure.isImpermeable(grid.index(tx.toInt(), ty.toInt()))) return true
                }
            }
        }
    }
    return false
}

/**
 * Sweep one body: relative velocity (body world-frame, ship grid-frame), bounce off hull.
 * Normal: ask x-only and y-only overlap separately (exact corner case, no preference).
 *
 * ### ⚠️ The ship recoils *inside* this loop, and it has to
 *
 * [shipVelocityX] is where the ship started the tick, and for most of this file's life that was the
 * same as where it is: a rock was a pebble against a hull, so the recoil rounded away and a wall
 * that never moved was a wall that never moved. It is not true any more. A default ore body is
 * **83 tonnes** and the box the tests fly is **40**, so a bounce moves the ship more than it moves
 * the rock, and a restitution computed against a stale wall is computed against the wrong closing
 * speed.
 *
 * Left stale it is an energy source rather than merely an inaccuracy. A rock dropped under one g
 * doubled its speed on every bounce — 5, 21, 95, 1821 tiles a tick — because each sub-step's
 * impulse was sized from a closing speed that the previous sub-step's impulse had already spent.
 * Nothing in the ledger noticed: momentum conserved perfectly the whole way up. Conservation of
 * momentum is not conservation of energy, and this is the shape of the difference.
 *
 * With the recoil fed back the arithmetic collapses to the law it was always supposed to be:
 * `rv' = rv + J/μ` and `J = −(1+e)·rv·μ` give `rv' = −e·rv` exactly, for any mass ratio, which is
 * what makes the bounce terminate rather than merely shrink.
 */
fun sweepBody(
    grid: Grid,
    structure: StructureMap,
    body: RigidBody,
    ship: ShipMotion,
    shipMass: Long,
    shipAbout: MassDistribution,
    restingSpeedX: Long,
    restingSpeedY: Long,
): SweptBody {
    val mass = body.mass
    if (mass <= 0L) return SweptBody(body, 0L, 0L, 0L)

    // World, because that is where a body lives now. The hull is reached through [ship].
    var px = body.positionX
    var py = body.positionY
    var ix = body.impulseX
    var iy = body.impulseY
    var gotX = 0L
    var gotY = 0L

    // The angular half, carried alongside the linear one and integrated in the same breath. Step 3
    // of `PLAN_rigid_bodies.md`: `L = Σ τ`, `ω = L/I`, `ang += ω`, which is the same three lines the
    // linear side has always had.
    var bodyAng = body.ang.raw.toLong()
    var bodyAngImpulse = body.angImpulse
    var gotTorque = 0L

    // The body's centre of mass in its own frame, which is the point it spins about and the point
    // every lever arm is measured from. Fixed for the tick: a rock does not shed cells mid-sweep.
    val comLocalX = body.about.comX * RigidBody.COM_SCALE
    val comLocalY = body.about.comY * RigidBody.COM_SCALE

    // Where the wall is going, updated as the body shoves it. See the note on this function.
    var svx = ship.velocityX
    var svy = ship.velocityY
    var sav = ship.angVel

    fun worldVel(impulse: Long): Long = scaledRatio(impulse, mass, Flight.PER_TILE)

    // The reach is the *relative* travel, because that is what can step over a wall. Measured in the
    // grid's own axes, which is where walls are.
    val startRvx = worldVel(ix) - svx
    val startRvy = worldVel(iy) - svy
    // ⚠️ **Turning is travel too.** A body's far corner moves at `ω × r` whether or not its centre
    // is going anywhere, so a spinning rock parked against a bulkhead sweeps its own width through
    // the wall in a tick and a substep count sized on the linear speed alone would step clean over
    // the contact. Step 3 is what makes this reachable; before it, every body's `ω` was zero.
    //
    // The radius is the furthest cell corner from the centre of mass, upper-bounded by the whole
    // cell box, which is cheap and never under-estimates.
    val spinRadius = maxOf(
        maxOf(comLocalX, body.width * Flight.PER_TILE - comLocalX),
        maxOf(comLocalY, body.height * Flight.PER_TILE - comLocalY),
    )
    val tipSpeed = spinSpeed(angularVelocity(bodyAngImpulse, body.about) - sav, spinRadius)
    val reach = maxOf(
        maxOf(
            if (startRvx < 0L) -startRvx else startRvx,
            if (startRvy < 0L) -startRvy else startRvy,
        ),
        if (tipSpeed < 0L) -tipSpeed else tipSpeed,
    )
    val steps = (reach / RockContact.MAX_SUBSTEP + 1L).toInt()

    /**
     * The vessel's pose part way through the tick.
     *
     * The sweep advances the body in the world and the ship in the world, and asks where the body
     * has got to *in the ship's frame* at each substep. Both are moving, so the relative motion —
     * including everything the ship's rotation contributes — falls out of the subtraction instead of
     * being written down as an `ω × r` term that could be got wrong or forgotten.
     */
    fun poseAt(step: Int): Pose = Pose(
        ship.pose.x + svx * step / steps,
        ship.pose.y + svy * step / steps,
        Coord((ship.pose.ang.raw + sav * step / steps).toInt()),
    )

    /**
     * The body's own pose, in the grid, right now — composed from where it is in the world and
     * where the ship is in the world.
     *
     * The angles subtract because rotations commute in two dimensions, so a ship turning under a
     * body it is not touching turns the body's *grid* orientation and leaves its world orientation
     * alone. That is the same thing the linear side already does, where astern drift is the grid
     * leaving rather than the body moving, and it arrives here for free rather than as a term.
     */
    fun gridPose(worldX: Long, worldY: Long, ang: Long, shipPose: Pose): Pose = Pose(
        shipPose.toLocalX(worldX, worldY),
        shipPose.toLocalY(worldX, worldY),
        Coord((ang - shipPose.ang.raw).toInt()),
    )

    // A body that begins the tick already inside the hull is left alone — a rock dropped onto the
    // deck by the editor starts that way, and pushing it out would fling it. It is not a contact,
    // it is a placement.
    val wedged = overlapsHull(grid, structure, body, gridPose(px, py, bodyAng, poseAt(0)))

    val contacts = ArrayList<Contact>(4)

    for (k in 0 until steps) {
        val vx = worldVel(ix)
        val vy = worldVel(iy)
        val spin = angularVelocity(bodyAngImpulse, body.about)

        // One substep of travel *and* of turning, taken about the centre of mass rather than about
        // the local origin — a body spins about its mass, and turning about the corner of its cell
        // box would walk it sideways across the deck at a speed proportional to its spin. The vessel
        // had exactly this bug until step 1 found it.
        val turned = Pose(px, py, Coord(bodyAng.toInt()))
            .turnedAbout(
                Coord((spin * (k + 1) / steps - spin * k / steps).toInt()),
                comLocalX, comLocalY,
            )
            .movedBy(vx * (k + 1) / steps - vx * k / steps, vy * (k + 1) / steps - vy * k / steps)

        val next = poseAt(k + 1)
        var inGrid = gridPose(turned.x, turned.y, turned.ang.raw.toLong(), next)

        if (wedged) {
            px = turned.x; py = turned.y; bodyAng = turned.ang.raw.toLong()
            continue
        }

        contacts.clear()
        collectHullContacts(grid, structure, body, 0, inGrid, restingSpeedX, restingSpeedY, contacts)
        if (contacts.isEmpty()) {
            px = turned.x; py = turned.y; bodyAng = turned.ang.raw.toLong()
            continue
        }

        // ── Velocity: every contact of this substep, solved together ──────────────
        //
        // The whole substance of step 2, now with the angular half step 3 adds. Grid axes, because
        // that is where a normal is; the operands come in the same way. A scalar spin needs no
        // turning — it is the same number in any set of axes — which is why only the velocities are
        // conjugated here and the angular velocities are passed straight through.
        val worldVx = worldVel(ix)
        val worldVy = worldVel(iy)
        val bodyOp = Operand(
            mass = mass,
            about = body.about,
            comX = inGrid.toWorldX(comLocalX, comLocalY),
            comY = inGrid.toWorldY(comLocalX, comLocalY),
            velocityX = rotScale(worldVx, next.cos) + rotScale(worldVy, next.sin),
            velocityY = -rotScale(worldVx, next.sin) + rotScale(worldVy, next.cos),
            angVel = spin,
        )
        val shipOp = if (shipMass <= 0L) null else Operand(
            mass = shipMass,
            about = shipAbout,
            comX = shipAbout.comX * RigidBody.COM_SCALE,
            comY = shipAbout.comY * RigidBody.COM_SCALE,
            velocityX = rotScale(svx, next.cos) + rotScale(svy, next.sin),
            velocityY = -rotScale(svx, next.sin) + rotScale(svy, next.cos),
            angVel = sav,
        )
        solveContacts(contacts, listOf(bodyOp), shipOp)

        // ⚠️ The impulse comes back in grid axes and a body's momentum is in the world, so it is
        // turned before it is booked — on both halves of the exchange, or the ledger leaks the
        // moment the ship is turned. The **torque does not turn**, for the same reason the spin
        // did not on the way in.
        val jx = rotScale(bodyOp.gaveX, next.cos) - rotScale(bodyOp.gaveY, next.sin)
        val jy = rotScale(bodyOp.gaveX, next.sin) + rotScale(bodyOp.gaveY, next.cos)
        ix += jx; iy += jy
        gotX += jx; gotY += jy
        bodyAngImpulse += bodyOp.spun
        if (shipOp != null) {
            svx += scaledRatio(-jx, shipMass, Flight.PER_TILE)
            svy += scaledRatio(-jy, shipMass, Flight.PER_TILE)
            sav += angularVelocity(shipOp.spun, shipAbout)
            gotTorque += shipOp.spun
        }

        // ── Position: out of the wall, along the normals that put it there ────────
        //
        // Deepest push per direction rather than the sum, so a cell touching two tiles of the same
        // flat wall is one wall and not two — summing would launch a body off any surface wider
        // than itself.
        var pushLeft = 0L; var pushRight = 0L; var pushUp = 0L; var pushDown = 0L
        for (c in contacts) {
            if (c.normalX > 0L) pushRight = maxOf(pushRight, c.depth)
            if (c.normalX < 0L) pushLeft = maxOf(pushLeft, c.depth)
            if (c.normalY > 0L) pushDown = maxOf(pushDown, c.depth)
            if (c.normalY < 0L) pushUp = maxOf(pushUp, c.depth)
        }
        // ⚠️ Pushed out along the **grid's** axes, which is where the depths were measured, so the
        // correction is applied to the grid pose and read back into the world rather than added to
        // the world position directly.
        inGrid = inGrid.movedBy(pushRight - pushLeft, pushDown - pushUp)
        px = next.toWorldX(inGrid.x, inGrid.y)
        py = next.toWorldY(inGrid.x, inGrid.y)
        bodyAng = turned.ang.raw.toLong()
    }

    return SweptBody(
        body.copy(
            positionX = px, positionY = py,
            impulseX = ix, impulseY = iy,
            ang = Coord(bodyAng.toInt()), angImpulse = bodyAngImpulse,
        ),
        gotX, gotY, gotTorque,
    )
}

/**
 * How the vessel is placed and where it is going, for one tick — everything a sweep needs to know
 * about the thing it might hit.
 *
 * Bundled rather than passed as four longs because the sweep now needs the *pose*, not just the
 * velocity, and a pose plus its rate is one idea. It is also the shape the unified body will take:
 * every operand of a collision is a pose and a motion, and the vessel is about to be one of them.
 */
class ShipMotion(
    val pose: Pose,
    /** World-frame, per tick, at [Flight.PER_TILE] to the tile. */
    val velocityX: Long,
    val velocityY: Long,
    /** [Coord] raw per tick. */
    val angVel: Long,
)
