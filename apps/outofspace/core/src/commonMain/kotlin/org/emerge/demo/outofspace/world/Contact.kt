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
 * Every place [body] — placed at grid-frame ([atX], [atY]) — is inside the hull.
 *
 * One contact per cell-and-tile pair that overlaps, with the normal along the **axis of least
 * penetration** and the point at the centre of the overlap. That is the honest reading of a
 * square-against-square touch: the shallow axis is the one it came in through, and the deep axis
 * would push it sideways out of a wall it is resting against.
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
    atX: Long,
    atY: Long,
    restingSpeedX: Long,
    restingSpeedY: Long,
    into: MutableList<Contact>,
) {
    for (cy in 0 until body.height) {
        for (cx in 0 until body.width) {
            if (!body.cells[cy * body.width + cx]) continue
            val x0 = atX + cx * Flight.PER_TILE
            val y0 = atY + cy * Flight.PER_TILE
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
 * What a solve did: how much momentum the **ship** handed out, in the grid's axes.
 *
 * Per body, because the caller books each body's half against that body and the ship's half against
 * the ledger, and the two have to stay paired for the exchange to close.
 */
class Solved(val alongX: LongArray, val alongY: LongArray)

/**
 * Resolve a whole list of [contacts] together, [iterations] times over.
 *
 * **Together is the point.** The code this replaces resolved one axis of one body inline, in the
 * middle of moving it, so a body wedged in a corner had its two walls answered in sequence: the
 * second push was computed against a velocity the first had already spent, and the pair argued. A
 * list solved repeatedly converges on an answer that satisfies every contact at once, which is the
 * property that stacking will need — and it needs it for the same reason a corner does.
 *
 * ⚠️ Sequential impulses, not a simultaneous solve. Each pass refines the last, so more iterations
 * is more correct and never less stable; [DEFAULT_ITERATIONS] is where a corner stops arguing.
 *
 * ⚠️ Iterated **in list order**, which the caller builds by body index and then by cell. Never in
 * whatever order a broad phase happens to emit, or two runs of the same world would disagree — the
 * same hazard as `LockstepHost` ordering its inputs by arrival.
 */
fun solveContacts(
    contacts: List<Contact>,
    bodyMass: LongArray,
    velocityX: LongArray,
    velocityY: LongArray,
    shipVelocityX: Long,
    shipVelocityY: Long,
    shipMass: Long,
    iterations: Int = DEFAULT_ITERATIONS,
): Solved {
    val gaveX = LongArray(bodyMass.size)
    val gaveY = LongArray(bodyMass.size)
    if (contacts.isEmpty()) return Solved(gaveX, gaveY)

    var svx = shipVelocityX
    var svy = shipVelocityY

    repeat(iterations) {
        for (c in contacts) {
            val b = c.body
            val mass = bodyMass[b]
            if (mass <= 0L) continue
            // Closing speed along the normal. Positive is separating, so there is nothing to do.
            val relX = velocityX[b] - svx
            val relY = velocityY[b] - svy
            val closing = rotScale(relX, c.normalX) + rotScale(relY, c.normalY)
            if (closing >= 0L) continue

            val mu = if (shipMass <= 0L) mass else scaledRatio(mass, mass + shipMass, shipMass)
            val j = normalImpulseFor(closing, mu, c.restingSpeed)
            if (j == 0L) continue

            val jx = rotScale(j, c.normalX)
            val jy = rotScale(j, c.normalY)
            velocityX[b] += scaledRatio(jx, mass, Flight.PER_TILE)
            velocityY[b] += scaledRatio(jy, mass, Flight.PER_TILE)
            gaveX[b] += jx
            gaveY[b] += jy
            if (shipMass > 0L) {
                svx += scaledRatio(-jx, shipMass, Flight.PER_TILE)
                svy += scaledRatio(-jy, shipMass, Flight.PER_TILE)
            }
        }
    }
    return Solved(gaveX, gaveY)
}

/**
 * `J = −(1+e)·v·μ` along the normal, dropping the restitution below [rest] so a settling body stops
 * dead rather than buzzing.
 *
 * One chain and one rounding, as [RockContact.restingSpeed] requires and for the same reason: split
 * into a multiply and a divide it truncates twice, and the second truncation is the one that decides
 * whether a body is asleep.
 */
private fun normalImpulseFor(closing: Long, mu: Long, rest: Long): Long {
    val speed = if (closing < 0L) -closing else closing
    if (speed == 0L) return 0L
    val num = if (speed < rest) RockContact.RESTITUTION_DEN
    else RockContact.RESTITUTION_DEN + RockContact.RESTITUTION_NUM
    return scaledRatio(mu, RockContact.RESTITUTION_DEN * Flight.PER_TILE, speed * num)
}

/**
 * How many times the contact list is swept.
 *
 * Four, because a corner is two contacts and each pass propagates one contact's answer into the
 * next — two would leave the pair still arguing on the tick they first touch, and beyond four the
 * change is below the resting threshold that ends the bounce anyway. Raise it when stacking arrives
 * and a tower is more than two contacts deep.
 */
const val DEFAULT_ITERATIONS = 4
