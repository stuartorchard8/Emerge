package org.emerge.demo.outofspace.chem

/**
 * World composition species (simulation granularity).
 * Ore = Mixture mostly-Iron (purity = property of pile, not a species — refining is real decision).
 * Declaration order = Mixture iteration order + dominant tie-break. **Append only.**
 * molarMass = g/mol (settling). specificHeat = J/kg/K (heating cost).
 */
enum class Species(val molarMass: Int, val specificHeat: Int, val relativeAbundance: Int = 0) {
    Iron(56, 450, relativeAbundance = 410),
    Aluminum(27, 900),
    Copper(64, 385, relativeAbundance = 180),
    Titanium(48, 520, relativeAbundance = 110),
    Silica(60, 700, relativeAbundance = 300),
    Carbon(12, 710),
    RareEarth(140, 200),
    Uranium(238, 116),
    Oxygen(32, 918),
    Nitrogen(28, 1040),
    CarbonDioxide(44, 844),
    Water(18, 4182),
    ;

    companion object {
        /** Cached because `entries` allocates on some targets and this is read in inner loops. */
        val ALL: List<Species> = entries.toList()
        val COUNT: Int = ALL.size

        val NATURAL: List<Species> = ALL.filter { it.relativeAbundance > 0 }
    }
}
