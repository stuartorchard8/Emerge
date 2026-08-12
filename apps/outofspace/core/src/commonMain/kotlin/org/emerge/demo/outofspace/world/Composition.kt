package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES

/**
 * Fixed-point unit for the volume sum below. Volumes are ratios, so they need somewhere to keep
 * their fraction.
 *
 * ⚠️ It has to be far larger than it looks like it needs to be, because the divisor is a *tile* of
 * solid — millions of grams — while the dividend is a composition stated in parts per thousand. At
 * a million, `500 g / 6_532_100 g` truncated to 76 parts in a million and put the density of a
 * half-and-half mixture out by a third of a per cent. The division below keeps the remainder
 * explicitly rather than leaning on this being big enough.
 */
private const val VOLUME_UNIT: Long = 1_000_000_000L

/**
 * What a tile of pure [Species] weighs, at its real density.
 *
 * No scale factor. A tile is [TILE_LITRES] of room — a cube a little under a metre on a side — and a
 * tile of iron is what that much iron weighs, six and a half tonnes. Everything solid in the vessel
 * is now stated at this scale, which is the scale the *gas* was always at: [AirField.AMBIENT_AIR]
 * puts a real kilogram of air in a tile. Before this the solids were three orders under, and the
 * visible consequence was a steel ship that weighed about as much as the air inside it.
 */
val Species.solidGramsPerTile: Long
    get() = solidKgPerCubicMetre.toLong() * TILE_LITRES

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
fun gramsPerTileOf(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    // A pure pile is its species' density exactly. Not an optimisation: the fixed-point round trip
    // below is accurate to a few parts per million, and "a tile of iron weighs what a tile of iron
    // weighs" should not be approximate.
    mixture.dominant?.let { if (mixture[it] == total) return it.solidGramsPerTile }
    var volume = 0L
    for (species in Species.ALL) {
        val grams = mixture[species]
        if (grams <= 0L) continue
        // Whole part and remainder separately: `grams * VOLUME_UNIT` would overflow for a serious
        // pile of ore, and truncating the division would lose most of the value for a small one.
        val density = species.solidGramsPerTile
        volume += grams / density * VOLUME_UNIT + grams % density * VOLUME_UNIT / density
    }
    // `total * VOLUME_UNIT` breaks at 9.2e9 grams, and the only reason it never has is that both
    // call sites happen to pass per-mille compositions summing to about 1000 (Material.composition;
    // RockSpawner normalises). That is an *unstated invariant*: nothing declares it, nothing checks
    // it, and handing this function a real pile of ore — which its signature plainly invites — would
    // have wrapped it silently. Step 4 of PLAN_unit_rescale.md.
    //
    // ⚠️ Note this is [scaledRatio] and NOT the whole/remainder split the loop above uses, which was
    // the first thing tried and is a no-op here. That split moves the bound from `total` to
    // `volume` — but `volume` is itself proportional to `total`, and the ratio between them is a
    // density over VOLUME_UNIT, i.e. far *below* one. So the whole part is zero, the remainder is
    // the whole of `total`, and the expression is exactly what it was. The loop's split works
    // because its divisor is a fixed density; a divisor that grows with the dividend needs the
    // fraction reduced instead.
    return if (volume <= 0L) 0L else scaledRatio(total, volume, VOLUME_UNIT)
}

/**
 * Millijoules per kelvin for a tile of [mixture] — the same unit every other solid's capacity is in.
 *
 * Heat capacity *does* average by mass, unlike density: warming a tile means warming each gram in
 * it. So this is the mass-weighted specific heat times what the tile weighs, and the two weightings
 * being different is the whole reason they are written out separately here.
 */
fun capacityPerTileOf(mixture: Mixture): Long {
    if (mixture.total <= 0L) return 0L
    // Both factors are now bounded by construction: a tile of the densest solid there is (osmium,
    // 1.9e7 g) times the thirstiest conceivable average (water, 4.2e6 in milli-units). Their product
    // is 7.8e13 — five orders inside a Long, for *any* mixture, with no invariant to uphold.
    return gramsPerTileOf(mixture) * meanSpecificHeatMilli(mixture) / SPECIFIC_HEAT_SCALE
}

/** Millijoules per gram per kelvin: what [mixture] costs to warm, averaged by mass. */
fun specificHeatOf(mixture: Mixture): Long = meanSpecificHeatMilli(mixture) / SPECIFIC_HEAT_SCALE

/** Fixed-point unit for [meanSpecificHeatMilli]: keeps three digits the truncation would else lose. */
private const val SPECIFIC_HEAT_SCALE: Long = 1_000L

/**
 * The mass-averaged specific heat of [mixture], in thousandths of a J/kg/K.
 *
 * **Normalised per species rather than summed and then divided**, which is what makes every caller
 * above total-agnostic. A composition is a set of *proportions* — `Water to 1000` and `Water to 1`
 * are the same recipe and must give the same answer — so this function is only meaningful if it
 * depends on the ratios and not on the units they were stated in. Dividing at the end looks like it
 * achieves that and does not: `Σ grams × specificHeat` overflows on its own at about 2.2e15,
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
        val grams = mixture[species]
        if (grams > 0L) mean += scaledRatio(grams, total, species.specificHeat * SPECIFIC_HEAT_SCALE)
    }
    return mean
}
