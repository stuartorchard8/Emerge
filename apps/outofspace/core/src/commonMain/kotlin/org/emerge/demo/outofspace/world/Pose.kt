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
    /**
     * The world position of the body's **centre of mass**, at [Flight.PER_TILE] to the tile.
     *
     * Not its local origin. A body stores where its mass is, and the grid hangs off that — see
     * [comLocalX] for the offset that does the hanging, and `PLAN_com_anchored_frames.md` for why
     * this way round. The short of it: with the origin held, cargo sliding down a rail moves the
     * centre of mass through *open space* with no force acting on it, and momentum conservation
     * says that is exactly backwards. Held this way the invariant is free — the centre cannot move
     * unless something pushes it, because the centre is the thing that is stored.
     */
    val x: Long,
    val y: Long,
    /** How far the body is turned relative to open space. */
    val ang: Coord,
    /**
     * Where that centre of mass sits in the body's **own grid**, at [Flight.PER_TILE] to the tile.
     *
     * This is what superimposes the grid on the point [x] fixes: the grid is placed so that its own
     * centre of mass lands there, whatever the mass distribution currently says. Cargo moving aboard
     * changes this, so the whole grid — colliders and all — shifts under a stationary centre, which
     * is the hull recoiling from its own cargo and is correct.
     *
     * ⚠️ **Fixed for a tick.** It is a fact about a mass distribution that changes *during* a tick
     * as matter moves, so a conversion early in the tick and one late in it must agree. The class
     * already required one instance per body per tick because [Norm.fromAngle] is a CORDIC loop;
     * that lifetime is the freeze, and it is now load-bearing rather than an optimisation.
     *
     * ⛔ Millitiles will not do here. A 100 kg packet moving one tile on the starter vessel shifts
     * the centre by 1.04 millitiles, so a grid placed on the radius scale would snap in whole
     * thousandths of a tile and swallow everything finer — see [MassDistribution.comX].
     */
    val comLocalX: Long,
    val comLocalY: Long,
) {
    /** The pose of a body whose centre of mass [about] describes. */
    constructor(x: Long, y: Long, ang: Coord, about: MassDistribution) :
        this(x, y, ang, about.comX, about.comY)

    private val norm: Norm = Norm.fromAngle(ang)

    /** `(cos, sin)` as [Flight.FRAC_ONE]ths, in the grid's axes — +y down, so +ang is clockwise. */
    val cos: Long = norm.x.raw
    val sin: Long = norm.y.raw

    /**
     * A point in the body's grid, in the world.
     *
     * ⚠️ The offset from the centre of mass is taken **first**, and for [rotScale]'s reason: it is
     * the small quantity. A grid coordinate is bounded by the grid and so is its distance from the
     * centre, whereas [x] is a world coordinate and can be billions of tiles out.
     */
    fun toWorldX(localX: Long, localY: Long): Long =
        x + rotScale(localX - comLocalX, cos) - rotScale(localY - comLocalY, sin)

    fun toWorldY(localX: Long, localY: Long): Long =
        y + rotScale(localX - comLocalX, sin) + rotScale(localY - comLocalY, cos)

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
        return rotScale(dx, cos) + rotScale(dy, sin) + comLocalX
    }

    fun toLocalY(worldX: Long, worldY: Long): Long {
        val dx = worldX - x
        val dy = worldY - y
        return -rotScale(dx, sin) + rotScale(dy, cos) + comLocalY
    }

    /**
     * This pose turned by [by] — and that is the whole of it.
     *
     * ⛔ **There is no pivot, because the anchor is the pivot.** A free body spins about its centre
     * of mass, [x] *is* its centre of mass, and a rotation about the point you are anchored to
     * moves nothing but the angle. Two commits ago this took a pivot as two `Long`s; one commit ago
     * it took the [MassDistribution] the pivot had to come from; now there is nothing left to pass.
     * That is the whole argument of `PLAN_com_anchored_frames.md` arriving in one line of code.
     *
     * ⚠️ **A welded pair still turns about the pair's centre, not the vessel's** — but that is a
     * statement about *which body is being advanced*, not about this method. Advance the pair's
     * pose and read the members back out of it; see [Weld.advance].
     */
    fun turned(by: Coord): Pose =
        if (by.raw == 0) this else Pose(x, y, Coord(ang.raw + by.raw), comLocalX, comLocalY)

    /**
     * This pose with the grid hung off a different centre of mass, the body not having moved.
     *
     * What "not having moved" means is that every tile stays where it is in the world and the
     * *centre* is what shifts — the opposite of [movedBy]. That is what an intra-grid mass change
     * does: `⛔` the world centre of mass must not move when cargo does, so when the local centre
     * moves the anchor has to follow it, and this is the expression that follows it.
     */
    fun about(comLocalX: Long, comLocalY: Long): Pose = Pose(
        x = toWorldX(comLocalX, comLocalY),
        y = toWorldY(comLocalX, comLocalY),
        ang = ang,
        comLocalX = comLocalX,
        comLocalY = comLocalY,
    )

    fun about(distribution: MassDistribution): Pose = about(distribution.comX, distribution.comY)

    /** This pose moved by a world-frame offset, turning not at all. */
    fun movedBy(dx: Long, dy: Long): Pose = Pose(x + dx, y + dy, ang, comLocalX, comLocalY)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Pose && x == other.x && y == other.y && ang == other.ang &&
            comLocalX == other.comLocalX && comLocalY == other.comLocalY)

    override fun hashCode(): Int = (x * 31 + y).toInt() * 31 + ang.raw

    override fun toString(): String =
        "Pose(${x.toDouble() / Flight.PER_TILE}, ${y.toDouble() / Flight.PER_TILE}, ang=${ang.raw})"

    companion object {
        /** At the world origin, pointing the way the grid is drawn — what every old save means. */
        val IDENTITY = Pose(0L, 0L, Coord(0), 0L, 0L)
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
