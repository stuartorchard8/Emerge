package org.emerge.demo.outofspace.world

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
 * One rock's tick of travel across the grid, stopped and bounced wherever the hull is in the way.
 *
 * [impulseX] is what the ship handed the rock — the rock already has it, and the ship owes itself
 * the negative of it.
 */
class Swept(val rock: Rock, val impulseX: Long, val impulseY: Long)

/**
 * Does [rock], placed with its top-left corner at [atX], [atY], overlap anything solid?
 *
 * A rock's cells are the same size as the grid's and axis-aligned with them but offset by a fraction
 * of a tile, so one rock cell covers up to four tiles and the test is an integer box per cell. The
 * `- 1` on the far edge is what makes an exactly-aligned rock *touching* a wall not count as being
 * inside it, which is the difference between a rock at rest against a bulkhead and a rock that
 * bounces off it once a tick forever.
 *
 * Anything off the grid is open space, not wall. A rock leaves the world by flying off the edge and
 * that is correct: the plating stops where the vessel does and so does everything else about it.
 */
fun overlapsHull(grid: Grid, structure: StructureMap, rock: Rock, atX: Long, atY: Long): Boolean {
    for (cy in 0 until rock.height) {
        for (cx in 0 until rock.width) {
            if (!rock.cells[cy * rock.width + cx]) continue
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

/** Integer floor division, which is not what `/` does for negatives — and a rock goes negative. */
private fun floorTile(v: Long): Long =
    if (v >= 0L) v / Flight.PER_TILE else -((-v + Flight.PER_TILE - 1L) / Flight.PER_TILE)

/**
 * Sweep one rock: relative velocity (rock world-frame, ship grid-frame), bounce off hull.
 * Normal: ask x-only and y-only overlap separately (exact corner case, no preference).
 * shipVelocityX fixed per tick (explicitness: forces buy next tick's travel).
 * ⚠️ Rock already inside wall = left alone (escape route = overlap → wedging).
 */
fun sweepRock(
    grid: Grid,
    structure: StructureMap,
    rock: Rock,
    shipVelocityX: Long,
    shipVelocityY: Long,
    shipMassGrams: Long,
    restingSpeedX: Long,
    restingSpeedY: Long,
): Swept {
    val mass = rock.massGrams
    if (mass <= 0L) return Swept(rock, 0L, 0L)

    var px = rock.positionX
    var py = rock.positionY
    var ix = rock.impulseX
    var iy = rock.impulseY
    var gotX = 0L
    var gotY = 0L

    // Reduced mass, which is what makes the ship's finite weight show up in the bounce: against an
    // infinitely heavy wall this is just the rock's mass, and against a ship six times its own the
    // rock keeps a sixth of the exchange for itself. A ship is not a wall, and at 380kg against 63
    // it is not a very good approximation of one either.
    val mu = if (shipMassGrams <= 0L) mass else mass * shipMassGrams / (mass + shipMassGrams)

    fun relative(impulse: Long, shipVelocity: Long): Long = impulse * Flight.PER_TILE / mass - shipVelocity

    val startRvx = relative(ix, shipVelocityX)
    val startRvy = relative(iy, shipVelocityY)
    val reach = maxOf(abs(startRvx), abs(startRvy))
    val steps = (reach / RockContact.MAX_SUBSTEP + 1L).toInt()

    val wedged = overlapsHull(grid, structure, rock, px, py)

    for (k in 0 until steps) {
        val rvx = relative(ix, shipVelocityX)
        val rvy = relative(iy, shipVelocityY)
        // Partition (not repeated division) so sub-steps sum exactly to full move (no rounding loss).
        val dx = rvx * (k + 1) / steps - rvx * k / steps
        val dy = rvy * (k + 1) / steps - rvy * k / steps
        val nx = px + dx
        val ny = py + dy

        if (wedged || !overlapsHull(grid, structure, rock, nx, ny)) {
            px = nx
            py = ny
            continue
        }

        var hitX = dx != 0L && overlapsHull(grid, structure, rock, nx, py)
        var hitY = dy != 0L && overlapsHull(grid, structure, rock, px, ny)
        if (!hitX && !hitY) {
            // Neither axis alone reaches it: a corner, and the rock stops on both.
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

    return Swept(rock.copy(positionX = px, positionY = py, impulseX = ix, impulseY = iy), gotX, gotY)
}

/**
 * Normal impulse: J = −(1+e)·rv·μ (below restingSpeed: drop restitution = stop dead).
 * One rounded chain (not two) — truncate once toward zero (settles, never over-delivers).
 */
private fun normalImpulse(rv: Long, mu: Long, rest: Long): Long {
    if (rv == 0L) return 0L
    val speed = abs(rv)
    val num = if (speed < rest) RockContact.RESTITUTION_DEN
    else RockContact.RESTITUTION_DEN + RockContact.RESTITUTION_NUM
    val magnitude = speed * mu * num / (RockContact.RESTITUTION_DEN * Flight.PER_TILE)
    return if (rv > 0L) -magnitude else magnitude
}

private fun abs(v: Long): Long = if (v < 0L) -v else v
