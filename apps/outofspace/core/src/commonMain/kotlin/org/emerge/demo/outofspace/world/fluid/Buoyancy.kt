package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.sim.core.physics.primitives.Frac2

/** The reaction the vessel takes from holding its air up, by axis. */
class BuoyancyResult(val vesselX: Long, val vesselY: Long)

/**
 * Boussinesq buoyancy: gravity on gas excess vs. ambient at same pressure.
 * Replaces stratifyColumns; handles any gravity vector (not just axis-aligned).
 * Hot parcels → negative excess → rise (convection emerges naturally).
 * Returns vessel reaction (momentum ledger closed).
 * Volume-scaled reference (cell-level, not tile-level), via [ambientMassAtPressure].
 *
 * ⚠️ "Ambient at the same pressure" is an *inverse* equation of state, and it stopped being a
 * division when the solver stopped using the ideal gas law — see [ambientMassAtPressure]. Anything
 * that changes how pressure relates to density has to change here too, or this silently leaves a
 * standing force under every cell in the vessel.
 */
fun applyBuoyancy(
    edges: EdgeGrid,
    apertures: ApertureField,
    mx: LongArray,
    my: LongArray,
    tileGrams: LongArray,
    pressure: LongArray,
    gravity: Frac2,
    volumes: VolumeField? = null,
): BuoyancyResult {
    val gx = gravity.x.raw
    val gy = gravity.y.raw
    if (gx == 0L && gy == 0L) return BuoyancyResult(0L, 0L)

    // How much heavier each tile is than ordinary air at the same pressure would be. Negative means
    // lighter, which is what rises.
    //
    // The reference is ordinary air at *ambient* temperature, not at the tile's own, and that is
    // what makes convection work: a hot tile carries more pressure for the same mass, so the mass of
    // room-temperature air needed to match its pressure is larger than the mass it actually has, its
    // excess comes out negative, and it rises. Reading the tile's own temperature here would cancel
    // exactly that and leave hot gas sitting where it was.
    val excess = LongArray(tileGrams.size) { tile ->
        val room = volumes?.at(tile) ?: VolumeField.FULL
        tileGrams[tile] - ambientMassAtPressure(pressure[tile], Temperature.AMBIENT_KELVIN, room)
    }

    var addedX = 0L
    var addedY = 0L
    if (gx != 0L) {
        for (e in 0 until edges.xEdgeCount) {
            if (!apertures.isXOpen(e)) continue
            // The same mean-of-adjacent-tiles a face mass uses; the quantity differs, the geometry
            // does not.
            val impulse = pull(xFaceGrams(edges, excess, e), gx)
            mx[e] += impulse
            addedX += impulse
        }
    }
    if (gy != 0L) {
        for (e in 0 until edges.yEdgeCount) {
            if (!apertures.isYOpen(e)) continue
            val impulse = pull(yFaceGrams(edges, excess, e), gy)
            my[e] += impulse
            addedY += impulse
        }
    }

    return BuoyancyResult(-addedX, -addedY)
}

/**
 * The momentum an excess of [excessGrams] picks up in one tick under one component of gravity.
 *
 * [SETTLING] is a tuning dial rather than a physical constant, and it is set low on purpose: nothing
 * in the model damps yet — there is no viscosity and no drag — so the only thing stopping a sinking
 * parcel is the pressure building underneath it. A gentle pull settles heavy gas over tens of ticks
 * and lets the projection keep up; a strong one overshoots and the layer bounces. It wants setting
 * properly once there is something to watch, which is increment D.
 */
private fun pull(excessGrams: Long, gravityRaw: Long): Long =
    scaleByGravity(excessGrams, gravityRaw, SETTLING_NUMERATOR, SETTLING_DENOMINATOR)

/**
 * Multiply quantity by gravity, rounded to nearest and symmetric about zero.
 * ⚠️ Must fold settling factor in (not apply after): truncating divide after function undoes rounding,
 * annihilates small quantities at low gravity (<0.3g). Single operation: `q × g × num / (LIMIT × den)`.
 */
internal fun scaleByGravity(
    quantity: Long,
    gravityRaw: Long,
    /** Settling rate fraction (folded in, not applied after; default=1 for gravity-only). */
    numerator: Long = 1L,
    denominator: Long = 1L,
): Long {
    val magnitude = if (quantity < 0L) -quantity else quantity
    val g = if (gravityRaw < 0L) -gravityRaw else gravityRaw
    // ~1e14 product fits in Long (arithmetic is load-bearing).
    val divisor = MomentumField.SPEED_LIMIT_RAW * denominator
    val scaled = (magnitude * g * numerator + divisor / 2L) / divisor
    val negative = (quantity < 0L) != (gravityRaw < 0L)
    return if (negative) -scaled else scaled
}

private const val SETTLING_NUMERATOR = 1L
private const val SETTLING_DENOMINATOR = 4L

/**
 * What a tile of ordinary air weighs at one atmosphere — the reference [applyBuoyancy] compares to,
 * and the mass [applyPressureForce] scales the speed of sound against.
 */
internal val AMBIENT_TILE_GRAMS: Long = AirField.AMBIENT_AIR.total
