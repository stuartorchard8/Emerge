package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio

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
 * Same as the [Rock] overload — [body] carries [width], [height], [cells] the same way.
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
 */
fun sweepBody(
    grid: Grid,
    structure: StructureMap,
    body: RigidBody,
    shipVelocityX: Long,
    shipVelocityY: Long,
    shipMassGrams: Long,
    restingSpeedX: Long,
    restingSpeedY: Long,
): SweptBody {
    val mass = body.massGrams
    if (mass <= 0L) return SweptBody(body, 0L, 0L)

    var px = body.positionX
    var py = body.positionY
    var ix = body.impulseX
    var iy = body.impulseY
    var gotX = 0L
    var gotY = 0L

    // The reduced mass, and the one expression here that is *quadratic* in the mass unit: a product
    // of two masses over their sum. Written plainly it wraps for any pair heavier than a few grams
    // once a unit is a microgram, so it is a reduced fraction — the ratio it computes is what the
    // physics wants and the ratio has no unit.
    val mu = if (shipMassGrams <= 0L) mass else scaledRatio(mass, mass + shipMassGrams, shipMassGrams)

    // [RigidBody.velocityX]'s expression, on an impulse that is being carried through the sweep.
    fun relative(impulse: Long, shipVelocity: Long): Long =
        scaledRatio(impulse, mass, Flight.PER_TILE) - shipVelocity

    val startRvx = relative(ix, shipVelocityX)
    val startRvy = relative(iy, shipVelocityY)
    val reach = maxOf(abs(startRvx), abs(startRvy))
    val steps = (reach / RockContact.MAX_SUBSTEP + 1L).toInt()

    val wedged = overlapsHull(grid, structure, body, px, py)

    for (k in 0 until steps) {
        val rvx = relative(ix, shipVelocityX)
        val rvy = relative(iy, shipVelocityY)
        val dx = rvx * (k + 1) / steps - rvx * k / steps
        val dy = rvy * (k + 1) / steps - rvy * k / steps
        val nx = px + dx
        val ny = py + dy

        if (wedged || !overlapsHull(grid, structure, body, nx, ny)) {
            px = nx
            py = ny
            continue
        }

        var hitX = dx != 0L && overlapsHull(grid, structure, body, nx, py)
        var hitY = dy != 0L && overlapsHull(grid, structure, body, px, ny)
        if (!hitX && !hitY) {
            hitX = dx != 0L
            hitY = dy != 0L
        }

        if (!hitX) px = nx
        if (!hitY) py = ny
        if (hitX) {
            val j = normalImpulse(rvx, mu, restingSpeedX)
            ix += j
            gotX += j
        }
        if (hitY) {
            val j = normalImpulse(rvy, mu, restingSpeedY)
            iy += j
            gotY += j
        }
    }

    return SweptBody(body.copy(positionX = px, positionY = py, impulseX = ix, impulseY = iy), gotX, gotY)
}
