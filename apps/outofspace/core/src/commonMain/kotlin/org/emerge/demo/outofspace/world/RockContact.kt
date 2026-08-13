package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.sim.core.physics.primitives.Coord

/**
 * Rock-hull contact: swept overlap test + normal impulse with restitution (H2).
 * Ricochet: restitution=0.5 (tuned for legibility, not measured; rock on steel ~0.2-0.4).
 * Exchange: +J to rock, -J to ship (ledger closed via rockImpulseX store, not apology).
 * Axis-aligned, frictionless (no rotation → no torque; grids → axes, not contact manifolds).
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
class SweptBody(val body: RigidBody, val impulseX: Long, val impulseY: Long)

/** Integer floor division, which is not what `/` does for negatives — and a body goes negative. */
private fun floorTile(v: Long): Long =
    if (v >= 0L) v / Flight.PER_TILE else -((-v + Flight.PER_TILE - 1L) / Flight.PER_TILE)

/**
 * Normal impulse: J = −(1+e)·rv·μ (below restingSpeed: drop restitution = stop dead).
 * One rounded chain (not two) — truncate once toward zero (settles, never over-delivers).
 */
private fun normalImpulse(rv: Long, mu: Long, rest: Long): Long {
    if (rv == 0L) return 0L
    val speed = abs(rv)
    val num = if (speed < rest) RockContact.RESTITUTION_DEN
    else RockContact.RESTITUTION_DEN + RockContact.RESTITUTION_NUM
    // Still one chain and one rounding — [scaledRatio] does the whole fraction in one go. The mass
    // is [mu], and it is the term that wraps: a speed is at most a few tiles a tick, but a reduced
    // mass is kilograms, and kilograms are 1e9 of anything now.
    val magnitude = scaledRatio(mu, RockContact.RESTITUTION_DEN * Flight.PER_TILE, speed * num)
    return if (rv > 0L) -magnitude else magnitude
}

private fun abs(v: Long): Long = if (v < 0L) -v else v

/**
 * Does [body], placed with its top-left corner at [atX], [atY], overlap anything solid?
 *
 * ⚠️ [atX]/[atY] are **grid-frame** — a body stores world coordinates, so a caller converts through
 * [VesselState.pose] first. This function is the boundary: everything below it is tile indices.
 */
fun overlapsHull(grid: Grid, structure: StructureMap, body: RigidBody, atX: Long, atY: Long): Boolean {
    for (cy in 0 until body.height) {
        for (cx in 0 until body.width) {
            if (!body.cells[cy * body.width + cx]) continue
            val x0 = atX + cx * Flight.PER_TILE
            val y0 = atY + cy * Flight.PER_TILE
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
    restingSpeedX: Long,
    restingSpeedY: Long,
): SweptBody {
    val mass = body.mass
    if (mass <= 0L) return SweptBody(body, 0L, 0L)

    // World, because that is where a body lives now. The hull is reached through [ship].
    var px = body.positionX
    var py = body.positionY
    var ix = body.impulseX
    var iy = body.impulseY
    var gotX = 0L
    var gotY = 0L

    // The reduced mass, and the one expression here that is *quadratic* in the mass unit: a product
    // of two masses over their sum. Written plainly it wraps for any pair heavier than a few units
    // once a unit is a microgram, so it is a reduced fraction — the ratio it computes is what the
    // physics wants and the ratio has no unit.
    val mu = if (shipMass <= 0L) mass else scaledRatio(mass, mass + shipMass, shipMass)

    // Where the wall is going, updated as the body shoves it. See the note on this function.
    var svx = ship.velocityX
    var svy = ship.velocityY

    /** What the ship's velocity moves by when the body is handed [j] — the equal and opposite half. */
    fun recoil(j: Long): Long =
        if (shipMass <= 0L) 0L else scaledRatio(-j, shipMass, Flight.PER_TILE)

    fun worldVel(impulse: Long): Long = scaledRatio(impulse, mass, Flight.PER_TILE)

    // The reach is the *relative* travel, because that is what can step over a wall. Measured in the
    // grid's own axes, which is where walls are.
    val startRvx = worldVel(ix) - svx
    val startRvy = worldVel(iy) - svy
    val reach = maxOf(abs(startRvx), abs(startRvy))
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
        Coord((ship.pose.ang.raw + ship.angVel * step / steps).toInt()),
    )

    var at = poseAt(0)
    var lx = at.toLocalX(px, py)
    var ly = at.toLocalY(px, py)
    val wedged = overlapsHull(grid, structure, body, lx, ly)

    for (k in 0 until steps) {
        val vx = worldVel(ix)
        val vy = worldVel(iy)
        val nwx = px + (vx * (k + 1) / steps - vx * k / steps)
        val nwy = py + (vy * (k + 1) / steps - vy * k / steps)

        val next = poseAt(k + 1)
        val nlx = next.toLocalX(nwx, nwy)
        val nly = next.toLocalY(nwx, nwy)
        // The body's displacement *in the grid's frame* over this substep. This is the quantity the
        // wall cares about, and it already contains the ship's translation and its spin.
        val dx = nlx - lx
        val dy = nly - ly

        if (wedged || !overlapsHull(grid, structure, body, nlx, nly)) {
            px = nwx; py = nwy; at = next; lx = nlx; ly = nly
            continue
        }

        var hitX = dx != 0L && overlapsHull(grid, structure, body, nlx, ly)
        var hitY = dy != 0L && overlapsHull(grid, structure, body, lx, nly)
        if (!hitX && !hitY) {
            hitX = dx != 0L
            hitY = dy != 0L
        }

        // Whichever axis did not hit still travels. The world position has to follow the local one,
        // so a blocked axis is undone through the same pose that blocked it.
        val keptX = if (hitX) lx else nlx
        val keptY = if (hitY) ly else nly
        px = next.toWorldX(keptX, keptY)
        py = next.toWorldY(keptX, keptY)
        at = next; lx = keptX; ly = keptY

        // ⚠️ The closing speed is a *grid-axis* quantity and the impulse it produces is too, but a
        // body's momentum is in the world — so the impulse is turned into world axes before it is
        // booked, on both sides of the exchange. Booking a grid-frame impulse into a world-frame
        // ledger would be a slow leak that only appears once the ship is turned.
        if (hitX) {
            val j = normalImpulse(dx * steps, mu, restingSpeedX)
            val jx = rotScale(j, at.cos)
            val jy = rotScale(j, at.sin)
            ix += jx; iy += jy
            gotX += jx; gotY += jy
            svx += recoil(jx); svy += recoil(jy)
        }
        if (hitY) {
            val j = normalImpulse(dy * steps, mu, restingSpeedY)
            val jx = -rotScale(j, at.sin)
            val jy = rotScale(j, at.cos)
            ix += jx; iy += jy
            gotX += jx; gotY += jy
            svx += recoil(jx); svy += recoil(jy)
        }
    }

    return SweptBody(body.copy(positionX = px, positionY = py, impulseX = ix, impulseY = iy), gotX, gotY)
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
