package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES

/**
 * The one scale factor between a real density and a tile of solid in this world.
 *
 * [Material] already keeps its `gramsPerTile` roughly two orders under real, because those numbers
 * are thermal time constants as much as they are masses, and the flight model is tuned against
 * them. So this is chosen the same way and anchored to what already exists: it is the number that
 * makes a rock of the ore field's **natural abundance** weigh exactly what every rock weighed when
 * they were all [Material.Firebrick] — 3 kg a tile. Nothing about the average rock moves; what
 * moves is that a uranium rock is now heavy and an ice rock is now light, in the real ratios.
 *
 * ⚠️ Changing this changes the mass of every rock in the world, and therefore every thrust,
 * contact and ore-budget number measured against one.
 */
const val SOLID_DENSITY_SCALE: Long = 1319L

/** What a tile of pure [Species] weighs. See [SOLID_DENSITY_SCALE] for where the scale comes from. */
val Species.solidGramsPerTile: Long
    get() = solidKgPerCubicMetre.toLong() * TILE_LITRES / SOLID_DENSITY_SCALE

/**
 * Fixed-point unit for the volume sum below. Volumes are ratios, so they need somewhere to keep
 * their fraction; a million is far more resolution than a species fraction ever carries and leaves
 * ~10^12 grams of headroom in a `Long`.
 */
private const val VOLUME_UNIT: Long = 1_000_000L

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
    var volume = 0L
    for (species in Species.ALL) {
        val grams = mixture[species]
        if (grams <= 0L) continue
        volume += grams * VOLUME_UNIT / species.solidGramsPerTile
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
    var weighted = 0L
    for (species in Species.ALL) {
        val grams = mixture[species]
        if (grams > 0L) weighted += grams * species.specificHeat
    }
    return gramsPerTileOf(mixture) * weighted / total
}
