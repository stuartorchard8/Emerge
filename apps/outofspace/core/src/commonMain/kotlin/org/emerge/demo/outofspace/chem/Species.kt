package org.emerge.demo.outofspace.chem

/**
 * Phase: solid/liquid/gas at current conditions (phase is fixed; future: function of temp+pressure).
 * Logistics split: solid (belts) vs fluid (pipes carry both).
 */
enum class Phase {
    Solid,
    Liquid,
    Gas,
    ;

    /** Liquids and gases share a transport network; solids do not. */
    val isFluid: Boolean get() = this != Solid
}

/**
 * World composition species (simulation granularity).
 * Ore = Mixture mostly-Iron (purity = property of pile, not a species — refining is real decision).
 * Declaration order = Mixture iteration order + dominant tie-break. **Append only.**
 * molarMass = g/mol (settling). specificHeat = J/kg/K (heating cost).
 */
enum class Species(val phase: Phase, val molarMass: Int, val specificHeat: Int) {
    // ── Minerals: everything that comes out of the ground ──
    Iron(Phase.Solid, 56, 450),
    Aluminum(Phase.Solid, 27, 900),
    Copper(Phase.Solid, 64, 385),
    Titanium(Phase.Solid, 48, 520),
    Silica(Phase.Solid, 60, 700),
    Carbon(Phase.Solid, 12, 710),
    RareEarth(Phase.Solid, 140, 200),
    Uranium(Phase.Solid, 238, 116),

    // ── Fluids. Present so the fluid transport path is exercised by real species rather than by a
    // test fixture; the set will grow as life support and coolant loops need it. ──
    Oxygen(Phase.Gas, 32, 918),
    Nitrogen(Phase.Gas, 28, 1040),
    CarbonDioxide(Phase.Gas, 44, 844),
    Water(Phase.Liquid, 18, 4182),
    ;

    val isFluid: Boolean get() = phase.isFluid
    val isSolid: Boolean get() = phase == Phase.Solid

    companion object {
        /** Cached because `entries` allocates on some targets and this is read in inner loops. */
        val ALL: List<Species> = entries.toList()
        val COUNT: Int = ALL.size

        val SOLIDS: List<Species> = ALL.filter { it.isSolid }
        val FLUIDS: List<Species> = ALL.filter { it.isFluid }

        /** The species that make up a breathable — or unbreathable — atmosphere. */
        val GASES: List<Species> = ALL.filter { it.phase == Phase.Gas }
    }
}
