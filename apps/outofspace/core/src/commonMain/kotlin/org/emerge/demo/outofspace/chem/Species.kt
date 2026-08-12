package org.emerge.demo.outofspace.chem

/**
 * World composition species (simulation granularity).
 * Ore = Mixture mostly-Iron (purity = property of pile, not a species — refining is real decision).
 * Declaration order = Mixture iteration order + dominant tie-break. **Append only.**
 * molarMass = g/mol (settling). specificHeat = J/kg/K (heating cost).
 * solidKgPerCubicMetre = measured density of the pure solid — what a lump of it weighs, and so what
 * a rock made of it weighs. Textbook numbers, not knobs; the world's units arrive in
 * [org.emerge.demo.outofspace.world.solidMassPerTile], which is the only place a scale is chosen.
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

    /**
     * The densest solid there is, and here for exactly that reason.
     *
     * Osmium is 22,590 kg/m³ — the top of the periodic table by density, with iridium a whisker
     * behind and no compound or alloy above either, since a compound necessarily dilutes with
     * something lighter. It is here so that the ceiling of the world's mass range is a **named
     * physical fact** rather than a side-effect of uranium happening to be the heaviest thing on the
     * list. See `NUMERIC_LIMITS.md` §3: a tile of it is 18,749,700 g, and nothing can ever be denser.
     *
     * [relativeAbundance] is **1**, the smallest number this table can say — the anchor is reachable,
     * but only just. Against iron's 410 that makes it roughly a quarter of a per cent of a rock,
     * which is nothing like the truth: osmium is about a part per billion of the crust, and no
     * integer weighting against iron can express that without giving iron a nine-digit weight. So
     * this is the rarest thing the scale can represent rather than the rarity osmium actually has,
     * and the gap is a property of the weighting scheme, not a tuning choice.
     *
     * ⚠️ Which is a hint about that scheme. Real ores are not element piles: they are compounds, and
     * abundance is a fact about the *mineral*, with smelting the step that decomposes it — iron(II)
     * oxide into iron and oxygen by molar mass. Under that model osmium is rare because osmium-
     * bearing minerals are rare, and a whole-number weighting stops having to carry nine orders of
     * magnitude. Not a problem now, but it is the reason this number reads wrong.
     */
    Osmium(190, 130, solidKgPerCubicMetre = 22590, relativeAbundance = 1),

    /**
     * The third gas in air, and the one whose absence was quietly distorting the atmosphere.
     *
     * Dry air is 1.29% argon by mass and 0.064% CO₂. Before this species existed, ambient air put
     * **13 g of CO₂** in a kilogram tile — which is argon's share, wearing carbon dioxide's name,
     * because there was no noble gas to give it to. The consequence was an atmosphere twenty times
     * too rich in CO₂, and a broad brush of exactly the kind the rebaseline exists to remove.
     *
     * Monatomic, so its specific heat is the lowest of the gases (520 J/kg/K against nitrogen's
     * 1040): there are no rotational modes to store energy in. That is a real behavioural difference
     * and not a rounding — argon warms twice as fast as the air around it.
     */
    Argon(40, 520, solidKgPerCubicMetre = 1616),
    ;

    companion object {
        /** Cached because `entries` allocates on some targets and this is read in inner loops. */
        val ALL: List<Species> = entries.toList()
        val COUNT: Int = ALL.size

        val NATURAL: List<Species> = ALL.filter { it.relativeAbundance > 0 }
    }
}
