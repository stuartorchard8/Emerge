package org.emerge.demo.outofspace.world

/**
 * What happens when a rock meets the hull: a swept overlap test, and a normal impulse with
 * restitution — increment H2.
 *
 * ### It ricochets, and that is a decision
 *
 * The cheap version of contact is "lands and stays landed", and it is a corner cut: a rock is a
 * heavy object arriving fast, and a heavy object arriving fast bounces. [RESTITUTION_NUM] over
 * [RESTITUTION_DEN] is a half, which is **tuned for legibility rather than measured** — rock on
 * steel is really nearer 0.2 to 0.4, and a ricochet you cannot see is not worth having. It is one
 * number, in one place, and the day the plan wants a material property instead of a constant it
 * becomes one.
 *
 * ### The exchange needs no ledger term of its own, and gets one anyway
 *
 * `+J` to the rock and `−J` to the ship conserves momentum *by construction*, which is the whole
 * difference between this and the debug engine: nothing is minted, so nothing has to be confessed.
 * But the ship's half lands in [VesselState.vesselImpulseX], and that quantity is one term of the
 * momentum ledger while the rock's half is not in the ledger at all — so without a term for it, the
 * ledger would read the exchange as the ship gaining momentum from nowhere. [VesselState.rockImpulseX]
 * is therefore a **store and not an apology**: it is exactly the momentum that is now in the rocks,
 * the same way `exhaust` is exactly the momentum that is now in space. The plating pays into the same
 * store, and [driftRocks] says why that turned out to matter.
 *
 * ### Axis-aligned, frictionless, and both for the same reason
 *
 * A rock does not rotate — see [Rock] — so a contact has no torque to produce and a tangential
 * impulse would have nowhere to put the angular momentum it implies. Normal impulse only: a rock
 * sliding along a wall keeps sliding. The normal is an axis, because the shapes are grids and
 * because the alternative is a contact manifold, which is a rigid-body engine.
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
     * Below what closing speed the bounce is dropped and the contact simply stops the rock.
     *
     * ⚠️ **Not a magic number, and not a guess either — it is derived, and the derivation is the
     * whole of it.** A bounce is worth having when the rock actually *leaves*: it departs at `e·v`
     * and whatever is pressing it into the surface pulls it back at `a` per tick, so it is airborne
     * for at least one tick only if `e·v > a`. Hence
     *
     *     v > a / e
     *
     * and below that the "bounce" begins and ends inside a single tick, which is not a ricochet — it
     * is a rock buzzing on the floor, which is what this is here to prevent.
     *
     * The factor is what a first version got wrong, and it was instructive: with the threshold at
     * `a` rather than `a / e`, a landed rock sat in a **perfect limit cycle**, alternating between
     * two velocities and two heights a third of a tile apart, forever. It was not drifting and it
     * was not exploding — every conserved quantity was exactly right — so nothing but looking at the
     * numbers over time would have found it. `a` is the speed a resting rock arrives at; `a / e` is
     * the speed it has to arrive at to leave again.
     *
     * Deriving it from the acceleration rather than fixing it also means it stays right when the
     * gravity changes, and the gravity here is the engine, so it changes whenever the player touches
     * a key.
     *
     * [accelerationRaw] is a [org.emerge.sim.core.physics.primitives.Frac] component in tiles per
     * tick per tick; the result is in the billionths of a tile per tick that [Flight.PER_TILE]
     * counts.
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
 * Sweeps one rock across the grid for a tick and bounces it off whatever it hits.
 *
 * ### What moves, and against what
 *
 * The rock's momentum is the **world's** and its position is the **grid's** — see [Rock] — so what
 * this sweeps by is the difference, and what a contact is *about* is the difference too: a wall
 * bolted to a ship doing five tiles a tick is not a thing a rock doing five tiles a tick collides
 * with. Every velocity below is therefore relative to the vessel, and the impulse that comes out is
 * absolute, because that is the thing that gets conserved.
 *
 * ### The normal comes from which move was blocked
 *
 * Having found an overlap, the axis is recovered by asking the same question twice more — would
 * moving in x alone have hit? would moving in y alone? — which is exact, cheap, and gives the corner
 * case an honest answer instead of a preference: a rock that fits through neither gap on its own but
 * overlaps when it takes both is in a corner, and stops on both axes.
 *
 * ### Where the half-tick of lag is
 *
 * [shipVelocityX] is fixed for the whole tick, so a rock that bounces does not see the ship's own
 * recoil until the next one. That is the same explicitness the rest of the tick is written with —
 * this tick's forces buy next tick's travel — and at a mass ratio of six to one the recoil is a
 * fraction of the closing speed anyway.
 *
 * ⚠️ A rock that is **already** inside a wall when the tick begins is left alone entirely, and flies
 * as it did before H2. Anything else wedges it: every escape route is also an overlap, so the rock
 * would be pinned by the very test meant to free it. Dropping one on a bulkhead with `F6` is the way
 * to see it, and "it drifts out" beats "it is stuck in the wall forever".
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
        // A partition of the remaining travel rather than a repeated division, so the sub-steps add
        // up to the whole move and a rock does not lose a billionth of a tile per tick to rounding.
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
 * The impulse that turns a closing speed of [rv] into a departing one of `−e·rv`.
 *
 * `Δv_relative = J/μ`, so `J = −(1 + e)·rv·μ`, and the reduced mass is what makes that one line
 * cover both halves of the exchange: the ship gets `−J` and its own velocity changes by `−J/M`, and
 * the two together come to exactly `(1 + e)` times the approach. Below [rest] the restitution is
 * dropped rather than scaled, which stops the rock dead — see [RockContact.restingSpeed].
 *
 * ⚠️ One rounded chain, not two — §5g's lesson, and this is precisely the shape that produced it: a
 * multiply by a fraction followed by a divide by a scale. Written as a single expression it
 * truncates *once*, toward zero, so a bounce is very slightly under-delivered and never
 * over-delivered. Losing a billionth of a tile per tick of relative speed is a rock that settles;
 * gaining one is a rock that climbs the wall.
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
