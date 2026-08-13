package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatioRounded
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Norm

/**
 * Where a body's local origin sits in the world, and how far it is turned — step 1 of
 * `PLAN_rigid_bodies.md`.
 *
 * This is the piece that lets the vessel frame be **deleted** rather than patched. Bodies and the
 * vessel all store world coordinates; the grid stops being the frame and keeps the job it is good
 * at, which is being the addressing scheme for fluids, heat, transport and the ledgers. A collision
 * query against the vessel is turned into the vessel's local space **once** by [toLocalX]/[toLocalY]
 * and then indexes tiles exactly as it always did.
 *
 * The alternative — keeping bodies in the vessel's frame and correcting with `R(−ang)` and an
 * `ω × r` term — buys a rotating reference frame and every fictitious force that comes with it,
 * permanently, and it cannot survive the unification: **you cannot unify vessels and rocks while one
 * of them defines the coordinate system.**
 *
 * Local coordinates are grid coordinates at [Flight.PER_TILE] to the tile. World coordinates are the
 * same unit, and `Long` holds them to ±9.2e9 tiles at a **uniform** 1e-9 tile — which is the whole
 * reason this is viable. The same span in a `Float` is 60 tiles of error at its far end.
 *
 * [cos] and [sin] are held rather than recomputed because a body is asked to transform many points
 * per tick and [Norm.fromAngle] is a CORDIC loop. Construct one per body per tick.
 */
class Pose(
    /** The world position of the body's local origin, at [Flight.PER_TILE] to the tile. */
    val x: Long,
    val y: Long,
    /** How far the body is turned relative to open space. */
    val ang: Coord,
) {
    private val norm: Norm = Norm.fromAngle(ang)

    /** `(cos, sin)` as [Flight.FRAC_ONE]ths, in the grid's axes — +y down, so +ang is clockwise. */
    val cos: Long = norm.x.raw
    val sin: Long = norm.y.raw

    fun toWorldX(localX: Long, localY: Long): Long = x + rotScale(localX, cos) - rotScale(localY, sin)

    fun toWorldY(localX: Long, localY: Long): Long = y + rotScale(localX, sin) + rotScale(localY, cos)

    /**
     * A **direction** in this pose's frame, expressed in the world: [toWorldX] without the offset.
     *
     * The distinction is not pedantry. A point has a position and a direction does not, so a
     * momentum, a velocity or a force turns with the body but does not translate with it — putting
     * one through [toWorldX] adds the ship's position to it, which is nonsense of a size that grows
     * as the ship flies. Everything the grid produces is a direction in this sense: the pressure on
     * a bulkhead, an exhaust plume, the pull of plating. See [VesselState.vesselImpulseX] for why
     * they have to be turned at all.
     */
    fun turnedX(localX: Long, localY: Long): Long = rotScale(localX, cos) - rotScale(localY, sin)

    fun turnedY(localX: Long, localY: Long): Long = rotScale(localX, sin) + rotScale(localY, cos)

    /** The inverse of [turnedX]: a world-frame direction read back in this pose's axes. */
    fun unturnedX(worldX: Long, worldY: Long): Long = rotScale(worldX, cos) + rotScale(worldY, sin)

    fun unturnedY(worldX: Long, worldY: Long): Long = -rotScale(worldX, sin) + rotScale(worldY, cos)

    /**
     * The inverse of [toWorldX] — `R(−ang)` applied to the offset from this pose's origin.
     *
     * ⚠️ The subtraction happens **first**, and that is not a stylistic choice: see [rotScale].
     */
    fun toLocalX(worldX: Long, worldY: Long): Long {
        val dx = worldX - x
        val dy = worldY - y
        return rotScale(dx, cos) + rotScale(dy, sin)
    }

    fun toLocalY(worldX: Long, worldY: Long): Long {
        val dx = worldX - x
        val dy = worldY - y
        return -rotScale(dx, sin) + rotScale(dy, cos)
    }

    /**
     * This pose turned by [by], about the local point ([pivotX], [pivotY]) rather than about its own
     * origin — which is how a body actually spins, since it spins about its centre of mass.
     *
     * The origin has to move for the pivot to stay put, and this is the expression that moves it:
     * `origin' = origin + R(ang)·p − R(ang + by)·p`. Storing the pose of the *origin* and turning
     * about the *centre of mass* is the way round that survives cargo shifting, because the centre
     * of mass moves in local space every time a packet does. Store the centre of mass's world
     * position instead and the whole grid lurches sideways whenever an ingot slides down a rail.
     */
    fun turnedAbout(by: Coord, pivotX: Long, pivotY: Long): Pose {
        if (by.raw == 0) return this
        val turned = Pose(x, y, Coord(ang.raw + by.raw))
        return Pose(
            x = x + rotScale(pivotX, cos) - rotScale(pivotY, sin) -
                (rotScale(pivotX, turned.cos) - rotScale(pivotY, turned.sin)),
            y = y + rotScale(pivotX, sin) + rotScale(pivotY, cos) -
                (rotScale(pivotX, turned.sin) + rotScale(pivotY, turned.cos)),
            ang = turned.ang,
        )
    }

    /** This pose moved by a world-frame offset, turning not at all. */
    fun movedBy(dx: Long, dy: Long): Pose = Pose(x + dx, y + dy, ang)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Pose && x == other.x && y == other.y && ang == other.ang)

    override fun hashCode(): Int = (x * 31 + y).toInt() * 31 + ang.raw

    override fun toString(): String =
        "Pose(${x.toDouble() / Flight.PER_TILE}, ${y.toDouble() / Flight.PER_TILE}, ang=${ang.raw})"

    companion object {
        /** At the world origin, pointing the way the grid is drawn — what every old save means. */
        val IDENTITY = Pose(0L, 0L, Coord(0))
    }
}

/**
 * `value × fracRaw / FRAC_ONE`, for a **signed** value and a signed [Flight.FRAC_ONE]th — the one
 * multiply every rotation is made of.
 *
 * ### ⚠️ Why this is not `value * fracRaw / Flight.FRAC_ONE`
 *
 * Written that way it **overflows a `Long` past four tiles from the origin**. `FRAC_ONE` is 2.1e9
 * and a coordinate is 1e9 to the tile, so the product passes `Long.MAX_VALUE` at 4.3 tiles — and
 * this function exists precisely so that coordinates can be *world* coordinates, which are large.
 * It would have failed immediately, silently, as a wrapped value that reads like an absence.
 *
 * [scaledRatio] is the fix and it is already the right shape: it reduces the fraction before scaling,
 * so it never forms the wide product at all. It refuses negatives, hence the sign handling here —
 * a sine is negative for half of every turn and a coordinate is negative for half of every grid.
 *
 * ### ⚠️ Why it rounds to nearest and does not truncate
 *
 * Everything else in the simulation truncates, and a rotation is the one place that is wrong. `R(θ)`
 * is four of these multiplies and truncation pulls all four toward zero, so a turned vector comes
 * back systematically *short*. On a single read that is a unit or two and nobody could see it. On
 * the ship's momentum, which is a running total turned once per tick as it is booked, the shortfall
 * has a fixed sign and simply piles up: `momentumBalanceX` on a rotating starter vessel walked
 * monotonically to 112 over forty ticks and **stopped moving the tick the rotation stopped**, which
 * is the fingerprint of a biased rounding rather than of momentum going missing.
 *
 * [scaledRatioRounded] makes each error a coin flip instead. It does not make the turn exact —
 * nothing integer can — but an unbiased error random-walks rather than drifts, which is the
 * difference between a ledger that closes to a handful of units for ever and one that does not.
 *
 * ### Measured, not asserted
 *
 * A full rotation of any point on a 96×60 grid is out by at most **1667 sub-units, 1.7e-6 tile**,
 * and a `local → world → local` round trip by at most **2705, 2.7e-6 tile** (60 000 random
 * angle/point pairs, in a Python model emulating Kotlin's truncating division and shifts). Contacts
 * are resolved in millitiles, a thousand times coarser, so the reduction's loss is three orders of
 * magnitude below anything that reads it. **If a test here fails by a small margin the transcription
 * is wrong, not the tolerance** — the same rule `Trig` earned.
 */
fun rotScale(value: Long, fracRaw: Long): Long {
    if (value == 0L || fracRaw == 0L) return 0L
    val magnitude = scaledRatioRounded(
        numerator = if (fracRaw < 0L) -fracRaw else fracRaw,
        denominator = Flight.FRAC_ONE,
        scale = if (value < 0L) -value else value,
    )
    return if ((value < 0L) == (fracRaw < 0L)) magnitude else -magnitude
}
