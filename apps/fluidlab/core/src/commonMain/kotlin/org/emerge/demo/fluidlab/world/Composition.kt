package org.emerge.demo.fluidlab.world

import org.emerge.demo.fluidlab.chem.Mixture
import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.chem.TILE_LITRES

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
    return if (volume <= 0L) 0L else total * VOLUME_UNIT / volume
}

/**
 * Millijoules per kelvin for a tile of [mixture] — the same unit every other solid's capacity is in.
 *
 * Heat capacity *does* average by mass, unlike density: warming a tile means warming each gram in
 * it. So this is the mass-weighted specific heat times what the tile weighs, and the two weightings
 * being different is the whole reason they are written out separately here.
 */
fun capacityPerTileOf(mixture: Mixture): Long {
    val total = mixture.total
    if (total <= 0L) return 0L
    // Divided last. Rounding the specific heat down to a whole millijoule per gram first is worth
    // 0.04% on a wet rock, which is small until it is the difference between two ledgers.
    return gramsPerTileOf(mixture) * weightedSpecificHeat(mixture) / total
}

/** Millijoules per gram per kelvin: what [mixture] costs to warm, averaged by mass. */
fun specificHeatOf(mixture: Mixture): Long {
    val total = mixture.total
    return if (total <= 0L) 0L else weightedSpecificHeat(mixture) / total
}

/** The sum before the divide, so [capacityPerTileOf] can put the divide last. */
private fun weightedSpecificHeat(mixture: Mixture): Long {
    var weighted = 0L
    for (species in Species.ALL) {
        val grams = mixture[species]
        if (grams > 0L) weighted += grams * species.specificHeat
    }
    return weighted
}
