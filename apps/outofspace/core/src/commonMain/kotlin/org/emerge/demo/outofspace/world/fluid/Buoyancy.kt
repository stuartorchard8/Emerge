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
 * Temperature enters here and nowhere else, and it did so without this function changing. A hot
 * parcel reads as *more pressure for its mass*, so the `excess` below goes negative and the parcel
 * is pulled the other way — it rises. Convection is that, plus [advectHeat] carrying the warmth up
 * with the gas rather than leaving it behind. There is no line of code anywhere that mentions
 * convection, and that is the whole point of doing it as a force.
 *
 * ### The reaction
 *
 * Held-up air pushes down on whatever holds it up, and that is the deck. The net is returned rather
 * than discarded so the vessel's momentum ledger stays closed; over a settled vessel it comes to
 * very little, because the excess that is being pulled down is by definition the excess.
 *
 * ### Volume, and why it scales the reference rather than the parcel
 *
 * This is one of exactly two places the solver cares how big a cell is — see [VolumeField] for why
 * the other two dozen uses of `tileGrams` do not. The comparison being made is against *the mass
 * ordinary air would have at this pressure*, and that reference is a mass, so it has to be the mass
 * that would fit in **this** cell rather than in a whole tile. Half the room, half the reference.
 *
 * Scaling the reference keeps [excess] a mass, which is what the rest of the function needs: the
 * impulse below is a weight, and a weight is a mass times a gravity. Converting the whole comparison
 * to densities instead would read more like a textbook and would then have to multiply the volume
 * straight back in to get a force, so it is the same arithmetic with an extra round trip through a
 * quantity nothing else here uses.
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
    volumes: VolumeField? = null,
): BuoyancyResult {
    val gx = gravity.x.raw
    val gy = gravity.y.raw
    if (gx == 0L && gy == 0L) return BuoyancyResult(0L, 0L)

    // How much heavier each tile is than ordinary air at the same pressure would be. Negative means
    // lighter, which is what rises.
    val excess = LongArray(tileGrams.size) { tile ->
        val reference = pressure[tile] * AMBIENT_TILE_GRAMS / AMBIENT_PRESSURE
        val fitted =
            if (volumes == null) reference
            else reference * volumes.at(tile) / VolumeField.FULL
        tileGrams[tile] - fitted
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
    scaleByGravity(excessGrams, gravityRaw) * SETTLING_NUMERATOR / SETTLING_DENOMINATOR

/**
 * Multiply a quantity by a gravity, rounded to nearest and **symmetrically about zero**.
 *
 * ### Why this is not `a * g / SPEED_LIMIT_RAW`
 *
 * It was, everywhere, until an engine made gravity stop being exactly one. Integer division truncates
 * toward zero, and it does two things at once that are both invisible at exactly `g = 1` and neither
 * of which is small:
 *
 *  - **It annihilates the small.** A drift of one gram scaled by `0.9999 g` truncates to **zero**, not
 *    to one. So does two grams, and three. In the thick of a room nobody notices; a plume is made
 *    almost entirely of ones and twos, and switching them all off is a different plume.
 *  - **It is not symmetric.** Truncation toward zero rounds `+7` down and `−7` *up*, so a signed
 *    quantity gets a bias whose direction depends on its sign — which on a mirrored pair of faces is
 *    a lean, and a lean is the one thing `BreachSymmetryTest` exists to catch.
 *
 * The sim's whole life had been spent at `gravity = Frac(1, 1)`, where `a * LIMIT / LIMIT` is exactly
 * `a` and neither effect exists. Increment G moves gravity off that value on every tick that anything
 * is thrusting, and the measurement is blunt: at exactly one g the amidships plume mirrors to within
 * 1%; at 0.9999 g, truncating, it leant **9%** — the same 9% at 0.99 g, because what matters is not
 * how far from one it is but that it is not one.
 *
 * Round-to-nearest fixes both. One gram at 0.9999 g stays one gram; the rounding is the same distance
 * for `+7` as for `−7`; and at exactly one g it is still the identity, so nothing that was ever
 * measured under gravity moves.
 */
internal fun scaleByGravity(quantity: Long, gravityRaw: Long): Long {
    val magnitude = if (quantity < 0L) -quantity else quantity
    val g = if (gravityRaw < 0L) -gravityRaw else gravityRaw
    val limit = MomentumField.SPEED_LIMIT_RAW
    val scaled = (magnitude * g + limit / 2L) / limit
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
