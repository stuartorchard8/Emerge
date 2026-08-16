package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES

/**
 * Fixed-point unit for the volume sum below. Volumes are ratios, so they need somewhere to keep
 * their fraction.
 *
 * ⚠️ It has to be far larger than it looks like it needs to be, because the divisor is a *tile* of
 * solid — millions of mass — while the dividend is a composition stated in parts per thousand. At
 * a million, `500 g / 6_532_100 g` truncated to 76 parts in a million and put the density of a
 * half-and-half mixture out by a third of a per cent. The division below keeps the remainder
 * explicitly rather than leaning on this being big enough.
 */
private const val VOLUME_UNIT: Long = 1_000_000_000L

/**
 * What a tile of pure [Species] weighs, at its real density.
 *
 * A tile is [TILE_LITRES] of room — a cube a little under a metre on a side — and a tile of iron is
 * what that much iron weighs, six and a half tonnes. Everything solid in the vessel is stated at
 * this scale, which is the scale the *gas* was always at: [Atmosphere.AMBIENT_AIR] puts a real
 * kilogram of air in a tile. Before that the solids were three orders under, and the visible
 * consequence was a steel ship that weighed about as much as the air inside it.
 *
 * `kg/m³ × litres` is mass because a cubic metre is a thousand litres, so [Budget.GRAM] is the only
 * factor between the textbook density and the integer. Its doc used to read "no scale factor", which
 * was true of the arithmetic and false about the meaning — the twin of `Critical.massPerTile` and
 * the same miss, found the same way. At 10⁶ without it a tile of iron weighed six and a half
 * **mass**, and every rock, hull plate and machine in the vessel with it.
 */
val Species.solidMassPerTile: Long
    get() = solidKgPerCubicMetre.toLong() * TILE_LITRES * Budget.GRAM

/**
 * The densest thing there is, and so the reference every other density is measured against in
 * [massPerTileOf]. Osmium, which is on the species table for exactly this purpose.
 */
private val REFERENCE_DENSITY: Long get() = Species.Osmium.solidMassPerTile

/**
 * What a tile of [mixture] weighs, from what it is made of.
 *
 * Densities do **not** average by mass — two things sharing a tile share its *volume*, so the
 * mixture's density is the harmonic mean over mass fractions (`total / Σ mᵢ/ρᵢ`) and not the
 * arithmetic one. It matters at exactly the compositions the ore field produces: half silica by
 * mass is most of the tile by volume, and an arithmetic mean would have that rock a third too
 * heavy.
 *
 * Empty mixtures weigh nothing, which is the honest answer and keeps callers from dividing by it.
 */
fun massPerTileOf(mixture: Mixture): Long {
    val memo = mixture.massPerTileMemo
    if (memo != Mixture.UNSET) return memo
    return massPerTileUncached(mixture).also { mixture.massPerTileMemo = it }
}

private fun massPerTileUncached(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    // A pure pile is its species' density exactly. Not an optimisation: the fixed-point round trip
    // below is accurate to a few parts per million, and "a tile of iron weighs what a tile of iron
    // weighs" should not be approximate.
    mixture.dominant?.let { if (mixture[it] == total) return it.solidMassPerTile }
    // ── Everything below is a ratio of two like quantities, so the mass unit cancels ──
    //
    // The obvious loop accumulates `mass / density` directly, and that cannot survive a rescale:
    // `mass` is a *proportion* — a per-mille figure, a few hundred, and it does not move when the
    // unit moves — while `density` is a real tile mass and moves in full. The ratio between them is
    // therefore not scale-free at all; at one microgram per unit it is 6e-11 and floors to nothing
    // however much fixed point is thrown at it. [scaledRatio] cannot rescue it either, since
    // reducing that fraction shifts a three-digit numerator straight to zero.
    //
    // So each density is expressed against [REFERENCE_DENSITY] first. `ρ₀ / ρᵢ` is a ratio of two
    // real densities — unit-free, order one, and exact in fixed point — and the mixture's own
    // proportions are likewise taken as fractions of its total. The mass unit then enters exactly
    // once, at the end, carried by the reference density alone.
    var weighted = 0L
    for (species in Species.ALL) {
        val mass = mixture[species]
        if (mass <= 0L) continue
        val relative = scaledRatio(REFERENCE_DENSITY, species.solidMassPerTile, VOLUME_UNIT)
        weighted += scaledRatio(mass, total, relative)
    }
    // `ρ_mix = ρ₀ / Σ fᵢ·(ρ₀/ρᵢ)`, which is the harmonic mean rearranged so that the only
    // dimensioned term left is the reference density. The normalisation by [total] above is what
    // makes this independent of how the caller chose to state its proportions — per mille, per cent
    // or as a real pile of ore. That used to be an *unstated invariant*: nothing declared that
    // compositions had to sum to about 1000, nothing checked it, and handing this function a real
    // pile — which its signature plainly invites — wrapped it silently.
    return if (weighted <= 0L) 0L else scaledRatio(VOLUME_UNIT, weighted, REFERENCE_DENSITY)
}

/**
 * Millijoules per kelvin for a tile of [mixture] — the same unit every other solid's capacity is in.
 *
 * Heat capacity *does* average by mass, unlike density: warming a tile means warming each gram in
 * it. So this is the mass-weighted specific heat times what the tile weighs, and the two weightings
 * being different is the whole reason they are written out separately here.
 */
fun capacityPerTileOf(mixture: Mixture): Long {
    val memo = mixture.capacityPerTileMemo
    if (memo != Mixture.UNSET) return memo
    return capacityPerTileUncached(mixture).also { mixture.capacityPerTileMemo = it }
}

private fun capacityPerTileUncached(mixture: Mixture): Long {
    if (mixture.total <= 0L) return 0L
    // Split rather than multiplied-then-divided, which is what makes [SPECIFIC_HEAT_SCALE] free.
    //
    // Written the obvious way, `tile × mean / SCALE` peaks at the scaled average — so every digit of
    // precision kept in that average costs an order of headroom, and unlike most products here only
    // ONE side is a mass, so the whole thing is k¹. At a scale of 1000 that measured out at a safe
    // mass unit of 118,000: already too tight for the microgram rebaseline, and a thousand times
    // worse for every further digit.
    //
    // Splitting the average into whole units and remainder bounds the first term by the *unscaled*
    // specific heat (4182, a physical constant) and the second by the tile mass itself. Neither
    // mentions the scale, so the precision of the average and the headroom of the product stop
    // trading against each other and the row goes to a safe unit of 1.2e8 at any scale.
    //
    // [Budget.CAPACITY_DIVISOR] is applied to the *mass* and not folded into the scale, which would
    // be the obvious place for it. Folding it in makes the denominator 1e13, far past the point
    // [scaledRatio] has to start reducing, and the reduction shifts a nine-digit mean specific heat
    // down to three digits — half a per cent of error on every capacity in the game. Dividing the
    // tile mass instead costs the sub-milligram part of a tile, which is nothing.
    return scaledRatio(
        meanSpecificHeatMilli(mixture),
        SPECIFIC_HEAT_SCALE,
        massPerTileOf(mixture) / Budget.CAPACITY_DIVISOR,
    )
}

/** Millijoules per gram per kelvin: what [mixture] costs to warm, averaged by mass. */
fun specificHeatOf(mixture: Mixture): Long = meanSpecificHeatMilli(mixture) / SPECIFIC_HEAT_SCALE

/**
 * Fixed-point unit for [meanSpecificHeatMilli].
 *
 * A millionth, and it costs nothing: [capacityPerTileOf] splits rather than divides, so this does
 * not appear in any overflow bound. It buys the averaging step a truncation of a few parts per
 * billion instead of a few parts per million — which matters only where a rounding accumulates,
 * which is exactly where the energy ledgers live.
 */
internal const val SPECIFIC_HEAT_SCALE: Long = 1_000_000L

/**
 * The mass-averaged specific heat of [mixture], in thousandths of a J/kg/K.
 *
 * **Normalised per species rather than summed and then divided**, which is what makes every caller
 * above total-agnostic. A composition is a set of *proportions* — `Water to 1000` and `Water to 1`
 * are the same recipe and must give the same answer — so this function is only meaningful if it
 * depends on the ratios and not on the units they were stated in. Dividing at the end looks like it
 * achieves that and does not: `Σ mass × specificHeat` overflows on its own at about 2.2e15,
 * before any division gets the chance to cancel the scale. Reducing each term against `total` as it
 * is accumulated means nothing here ever grows with the total at all, and the sum is bounded by the
 * largest specific heat in the table whatever it is handed.
 *
 * The [SPECIFIC_HEAT_SCALE] is why this is not the rounding the old code was right to avoid: an
 * average taken in whole J/kg/K costs 0.04% on a wet rock, which is small until it is the difference
 * between two ledgers. In thousandths the per-species truncation is under 3 parts per million total.
 */
private fun meanSpecificHeatMilli(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    var mean = 0L
    for (species in Species.ALL) {
        val mass = mixture[species]
        if (mass > 0L) mean += scaledRatio(mass, total, species.specificHeat * SPECIFIC_HEAT_SCALE)
    }
    return mean
}
