package org.emerge.demo.outofspace.chem

/**
 * World composition species (simulation granularity).
 * Ore = Mixture mostly-Iron (purity = property of pile, not a species — refining is real decision).
 * Declaration order = Mixture iteration order + dominant tie-break. **Append only.**
 * molarMass = g/mol (settling). specificHeat = J/kg/K (heating cost).
 * solidKgPerCubicMetre = measured density of the pure solid — what a lump of it weighs, and so what
 * a rock made of it weighs. Textbook numbers, not knobs; the world's units arrive in
 * [org.emerge.demo.outofspace.world.solidGramsPerTile], which is the only place a scale is chosen.
 */
enum class Species(
    val molarMass: Int,
    val specificHeat: Int,
    val solidKgPerCubicMetre: Int,
    val relativeAbundance: Int = 0,
) {
    Iron(56, 450, solidKgPerCubicMetre = 7870, relativeAbundance = 410),
    Aluminum(27, 900, solidKgPerCubicMetre = 2700),
    Copper(64, 385, solidKgPerCubicMetre = 8960, relativeAbundance = 180),
    Titanium(48, 520, solidKgPerCubicMetre = 4510, relativeAbundance = 110),
    Silica(60, 700, solidKgPerCubicMetre = 2650, relativeAbundance = 300),
    Carbon(12, 710, solidKgPerCubicMetre = 2260),
    RareEarth(140, 200, solidKgPerCubicMetre = 7010),
    Uranium(238, 116, solidKgPerCubicMetre = 19100),

    // The volatiles, as the solids they are when cold enough to be part of a rock: ices, not gases.
    Oxygen(32, 918, solidKgPerCubicMetre = 1300),
    Nitrogen(28, 1040, solidKgPerCubicMetre = 1030),
    CarbonDioxide(44, 844, solidKgPerCubicMetre = 1560),
    Water(18, 4182, solidKgPerCubicMetre = 917),
    ;

    companion object {
        /** Cached because `entries` allocates on some targets and this is read in inner loops. */
        val ALL: List<Species> = entries.toList()
        val COUNT: Int = ALL.size

        val NATURAL: List<Species> = ALL.filter { it.relativeAbundance > 0 }
    }
}
