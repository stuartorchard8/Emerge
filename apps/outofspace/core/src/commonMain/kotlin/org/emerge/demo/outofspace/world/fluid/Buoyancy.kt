package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.world.AirField
import org.emerge.sim.core.physics.primitives.Frac2

/** The reaction the vessel takes from holding its air up, by axis. */
class BuoyancyResult(val vesselX: Long, val vesselY: Long)

/**
 * Gravity, acting on the gas — which is to say, on how much it differs from the gas around it.
 *
 * ### Why the difference and not the weight
 *
 * The obvious implementation applies gravity to all the mass on a face. It is also wrong here, and
 * expensively so: a compressible atmosphere under full gravity settles into a hydrostatic column,
 * and on a grid a hundred tiles tall that means most of the vessel's air ending up in a dense layer
 * on the floor with a near-vacuum above it. Real air does this too, over kilometres. Over the eight
 * metres of a deck it does not, and a spacecraft whose air all falls to the floor is a bug however
 * defensible its derivation.
 *
 * So what is applied is the **difference from what this pressure ought to weigh** — the Boussinesq
 * approximation, and the standard one for exactly this situation. A parcel is compared against the
 * mass ordinary air would have at the same *pressure*, and only the excess feels gravity. Uniform air
 * therefore feels nothing at all and the atmosphere simply stays where it is, while anything unusual
 * separates out.
 *
 * ### What this replaces
 *
 * `stratifyColumns` swaps heavy gas downward and light gas up, one vertical pair at a time. It is the
 * function the plan flags as *the one permitted to assume gravity is axis-aligned*, because walking
 * vertical neighbours only means anything when "down" is a grid axis. It does not get ported. What
 * takes its place is this: a force on the edge momenta along whatever vector gravity happens to be,
 * which handles a diagonal or a rotating vessel without noticing, and which produces sorting as a
 * *consequence* of heavy gas being pulled down rather than as a rule saying that it sorts.
 *
 * The reason it can be a force now, when it could not be before, is [tilePressure]: once pressure is
 * moles rather than mass, a dense gas is no longer automatically a high-pressure gas, so gravity can
 * pull it down without the pressure solve immediately pushing it back. Separating those two
 * quantities is what makes the honest version possible.
 *
 * Temperature will enter here and nowhere else. A hot parcel is fewer moles for its mass — lighter
 * than its pressure suggests — so it rises, and convection appears without a line of code that
 * mentions convection. That is what increment D switches back on.
 *
 * ### The reaction
 *
 * Held-up air pushes down on whatever holds it up, and that is the deck. The net is returned rather
 * than discarded so the vessel's momentum ledger stays closed; over a settled vessel it comes to
 * very little, because the excess that is being pulled down is by definition the excess.
 *
 * [mx] and [my] are edited in place. Closed faces are left alone: gas does not press on a bulkhead
 * sideways in a way that moves it through the bulkhead.
 */
fun applyBuoyancy(
    edges: EdgeGrid,
    apertures: ApertureField,
    mx: LongArray,
    my: LongArray,
    tileGrams: LongArray,
    pressure: LongArray,
    gravity: Frac2,
): BuoyancyResult {
    val gx = gravity.x.raw
    val gy = gravity.y.raw
    if (gx == 0L && gy == 0L) return BuoyancyResult(0L, 0L)

    // How much heavier each tile is than ordinary air at the same pressure would be. Negative means
    // lighter, which is what rises.
    val excess = LongArray(tileGrams.size) { tile ->
        tileGrams[tile] - pressure[tile] * AMBIENT_GRAMS / AMBIENT_PRESSURE
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
    excessGrams * gravityRaw / MomentumField.SPEED_LIMIT_RAW * SETTLING_NUMERATOR / SETTLING_DENOMINATOR

private const val SETTLING_NUMERATOR = 1L
private const val SETTLING_DENOMINATOR = 4L

/** What a tile of ordinary air weighs at one atmosphere — the reference [applyBuoyancy] compares to. */
private val AMBIENT_GRAMS: Long = AirField.AMBIENT_AIR.total
