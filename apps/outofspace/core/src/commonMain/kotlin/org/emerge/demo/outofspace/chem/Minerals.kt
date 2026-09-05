package org.emerge.demo.outofspace.chem

/**
 * What each mineral is made of, as atoms — the data a decomposition step needs.
 *
 * ⚠️ **Nothing consumes this yet.** [smelt] still picks a mixture's dominant species and subtracts
 * impurities, which cannot express "hematite becomes iron and oxygen". This file is the input that
 * a real refining step will read; it is here now because the [Species] table is meaningless without
 * it — "iron(II) oxide is 1:1 and iron(III) is 2:3" is a claim about *these numbers*, and without
 * them the table is a list of names.
 *
 * `MineralTest` proves every entry is self-consistent: the molar masses in [Species] are **derived**
 * from these formulae, not asserted alongside them, so a typo in either is a test failure rather
 * than a mineral that quietly weighs the wrong amount.
 *
 * ### Atoms, not molecules
 *
 * [Species.molarMass] is the *molecular* mass for the diatomic gases — `Oxygen` is O₂ at 32, because
 * pressure is what reads it. A formula counts atoms, so oxygen contributes 16 per atom here. That is
 * what [ATOMIC_MASS] is for, and it is the only place the distinction exists.
 *
 * The mass a decomposition yields is unaffected by any of this: a gram is a gram whether the oxygen
 * leaves as O or as O₂, and the sim conserves mass, not molecules. Molecularity only ever changes
 * what the *pressure* of the resulting gas is.
 */

/**
 *   Refinement methods
 *
 *   Four that fall out of the data and would each be a different machine:
 *
 *   1. Thermal decomposition — carbonates and hydrates give up CO₂/H₂O on heating alone. Calcite → lime + CO₂, serpentine → olivine +
 *   water. No reagent, just heat, which makes it the natural tier-1 refinery and a good sink for waste heat from the reactor.
 *   2. Carbothermic reduction — the iron one. Hematite + carbon → iron + CO. Consumes graphite, and the FeO-vs-Fe₂O₃ ratio decides how
 *   much, so ore choice has a running cost rather than just a yield.
 *   3. Roasting — sulfides + O₂ → oxide + SO₂. Two-stage (roast then reduce), produces a genuinely nasty gas you have to vent or scrub,
 *   which gives your atmosphere sim something to do that the player actually cares about.
 *   4. Fractional separation — for the rare earths, and deliberately not a smelt. LANTHANIDE_SUITE is a mixture that no single-pass process
 *   separates; it wants a cascade where each stage improves purity slightly, so the player builds a long chain of identical units. That's
 *   the marquee endgame build, and it's the one the table was designed to make possible.
 */

/**
 * Per-atom mass for the species whose [Species.molarMass] is molecular rather than atomic.
 *
 * Everything absent from this map contributes its [Species.molarMass] directly, which is correct for
 * every metal and metalloid — they are monatomic and the two masses coincide.
 *
 * ⚠️ Chlorine is 35 here, not 35.45. The rounding is deliberate and it is **self-consistent**: every
 * chloride in [MINERALS] has its [Species.molarMass] derived from this same 35, so halite is 58 in
 * both places and the ledger closes. It is wrong against a textbook by half a per cent and right
 * against itself, which is the property that matters when the number's job is to conserve mass.
 */
val ATOMIC_MASS: Map<Species, Int> = mapOf(
    Species.Hydrogen to 1,
    Species.Nitrogen to 14,
    Species.Oxygen to 16,
    Species.Fluorine to 19,
    Species.Chlorine to 35,
    Species.Bromine to 80,
    Species.Iodine to 127,
)

/** [Species.molarMass] for a monatomic species, the per-atom mass for a molecular one. */
val Species.atomicMass: Int get() = ATOMIC_MASS[this] ?: molarMass

/**
 * How many atoms are in one molecule of this — 1 for a metal, 2 for the diatomic gases, 3 for water.
 *
 * Derived from the two tables that already exist rather than stated, which is [MINERALS]' whole
 * argument: a compound counts the atoms in its formula, and an element divides the mass of the
 * molecule [Species.molarMass] describes by the mass of one atom. Nothing new has to be maintained,
 * and a species cannot acquire a formula and keep a stale atom count.
 *
 * ⚠️ **It answers for the molecule the game's molar mass describes, which is not always the one
 * chemistry would name.** `Species.Sulfur` weighs 32 — one atom — so this says monatomic, where real
 * sulfur vapour is S₈ near its boiling point and S₂ when hot. That is the same trade [ATOMIC_MASS]
 * makes for chlorine at 35: wrong against a textbook, right against itself, and self-consistency is
 * the property that matters because `millimolesOf` reads that same 32 when it works out what a
 * roomful of sulfur vapour presses at. Fixing it means giving sulfur a *molecular* molar mass, which
 * is a change to the mass ledger and not to this function.
 */
val Species.atomsPerMolecule: Int
    get() = MINERALS[this]?.values?.sum() ?: (molarMass / atomicMass)

/**
 * `2γ/(γ−1)` for this species — **5 monatomic, 7 diatomic, 8 polyatomic.**
 *
 * The adiabatic index itself is never wanted; every use of it in a rocket is this group, and stating
 * the group is what keeps the arithmetic in integers. γ = 5/3, 7/5 and 4/3 put `2γ/(γ−1)` at exactly
 * 5, 7 and 8 with nothing left over, so an exhaust velocity is an [org.emerge.demo.outofspace.num.isqrt]
 * of whole numbers and there is no fixed-point scale to calibrate. See `PLAN_fluid_thrusters.md` §4.
 *
 * γ falls out of how a molecule can hold energy: an atom has three ways to move and nothing else, a
 * dumbbell adds two ways to tumble, and anything bent or bigger adds the third. So this is a fact
 * about the *shape* of the molecule and correctly derived from its atom count rather than measured
 * per species — which also means it needs no entry when a species is added.
 *
 * ⚠️ **`f + 2` is the arithmetic to check this against, not `2γ/(γ−1)`.** They are the same number —
 * `K = 2·Cp/R` and `Cp = ((f+2)/2)·R` — but one of them is three integers and the other is a
 * division of two fractions that is easy to get wrong by hand. It was, once: 5 was written as 4,
 * which is not a value `f + 2` can produce for any whole number of degrees of freedom.
 *
 * ⛔ **Mole-weighted, when a mixture needs one — and that is exact rather than an approximation.**
 * `K = 2·Cp/R`, and a molar heat capacity is additive over moles, so the mixture's K is the
 * mole-weighted mean of its species'. There is no averaging of γ anywhere, which is the other reason
 * this group and not γ is what gets stored.
 *
 * ⚠️ **Three classes and no more, so it is exact at the ends and approximate in the middle.** Real
 * γ drifts with temperature as vibration wakes up, and a big polyatomic sits nearer 1.2 than 4/3 —
 * hydrolox at 3500 K really runs about 1.2, which this reads as 4/3 and so under-reads its exhaust
 * velocity by around a fifth. Deliberate: the alternative is a measured table per species per
 * temperature, and the whole span of this term is a factor of √2 in the answer.
 */
val Species.adiabaticK: Int
    get() = when (atomsPerMolecule) {
        1 -> 5
        2 -> 7
        else -> 8
    }

/**
 * Mineral → the elements in one formula unit, by atom count.
 *
 * Only minerals appear as keys. An element is not made of anything, and an ice is listed because it
 * is a compound the player will want to crack — water into hydrogen and oxygen is a real and useful
 * operation, and the table would be lying by omission if it said water had no parts.
 *
 * The rare-earth phosphates and the fluorocarbonate are written with a single representative
 * lanthanide, which is what makes their molar mass exact. Real ones are solid solutions across the
 * whole lanthanide site — see [LANTHANIDE_SUITE], which is the distribution a refining step should
 * actually use, and the reason rare-earth separation is a hard problem rather than a smelt.
 */
val MINERALS: Map<Species, Map<Species, Int>> = mapOf(
    // ── Silicates ──
    Species.Forsterite to mapOf(Species.Magnesium to 2, Species.Silicon to 1, Species.Oxygen to 4),
    Species.Fayalite to mapOf(Species.Iron to 2, Species.Silicon to 1, Species.Oxygen to 4),
    Species.Enstatite to mapOf(Species.Magnesium to 1, Species.Silicon to 1, Species.Oxygen to 3),
    Species.Ferrosilite to mapOf(Species.Iron to 1, Species.Silicon to 1, Species.Oxygen to 3),
    Species.Anorthite to mapOf(Species.Calcium to 1, Species.Aluminum to 2, Species.Silicon to 2, Species.Oxygen to 8),
    Species.Albite to mapOf(Species.Sodium to 1, Species.Aluminum to 1, Species.Silicon to 3, Species.Oxygen to 8),
    Species.Orthoclase to mapOf(Species.Potassium to 1, Species.Aluminum to 1, Species.Silicon to 3, Species.Oxygen to 8),
    Species.Quartz to mapOf(Species.Silicon to 1, Species.Oxygen to 2),
    // Mg3Si2O5(OH)4 — the hydroxyl is where the bound water comes from.
    Species.Serpentine to mapOf(Species.Magnesium to 3, Species.Silicon to 2, Species.Oxygen to 9, Species.Hydrogen to 4),
    Species.Zircon to mapOf(Species.Zirconium to 1, Species.Silicon to 1, Species.Oxygen to 4),
    Species.Hafnon to mapOf(Species.Hafnium to 1, Species.Silicon to 1, Species.Oxygen to 4),
    Species.Thortveitite to mapOf(Species.Scandium to 2, Species.Silicon to 2, Species.Oxygen to 7),
    Species.Beryl to mapOf(Species.Beryllium to 3, Species.Aluminum to 2, Species.Silicon to 6, Species.Oxygen to 18),
    Species.Spodumene to mapOf(Species.Lithium to 1, Species.Aluminum to 1, Species.Silicon to 2, Species.Oxygen to 6),
    Species.Pollucite to mapOf(Species.Cesium to 1, Species.Aluminum to 1, Species.Silicon to 2, Species.Oxygen to 6),
    Species.Rubicline to mapOf(Species.Rubidium to 1, Species.Aluminum to 1, Species.Silicon to 3, Species.Oxygen to 8),

    // ── Oxides ──
    //
    // The pair the whole model exists to distinguish: FeO is 1:1 and Fe2O3 is 2:3, so a tonne of
    // wustite yields 778 kg of iron and a tonne of hematite 700 kg. Same element, different rock,
    // different answer — which is the decision a refinery is supposed to be about.
    Species.Wustite to mapOf(Species.Iron to 1, Species.Oxygen to 1),
    Species.Hematite to mapOf(Species.Iron to 2, Species.Oxygen to 3),
    Species.Magnetite to mapOf(Species.Iron to 3, Species.Oxygen to 4),
    Species.Chromite to mapOf(Species.Iron to 1, Species.Chromium to 2, Species.Oxygen to 4),
    Species.Ilmenite to mapOf(Species.Iron to 1, Species.Titanium to 1, Species.Oxygen to 3),
    Species.Rutile to mapOf(Species.Titanium to 1, Species.Oxygen to 2),
    Species.Spinel to mapOf(Species.Magnesium to 1, Species.Aluminum to 2, Species.Oxygen to 4),
    Species.Corundum to mapOf(Species.Aluminum to 2, Species.Oxygen to 3),
    // The two calcination products. Simple oxides, and here for the same reason every other entry
    // is: their molar masses in [Species] are derived from these formulae rather than stated beside
    // them, so `Calcite -> Lime + CO2` closing atom by atom is something a test can check.
    Species.Lime to mapOf(Species.Calcium to 1, Species.Oxygen to 1),
    Species.Periclase to mapOf(Species.Magnesium to 1, Species.Oxygen to 1),
    Species.Pyrolusite to mapOf(Species.Manganese to 1, Species.Oxygen to 2),
    Species.Cassiterite to mapOf(Species.Tin to 1, Species.Oxygen to 2),
    Species.Molybdite to mapOf(Species.Molybdenum to 1, Species.Oxygen to 3),
    Species.Zincite to mapOf(Species.Zinc to 1, Species.Oxygen to 1),
    Species.Monteponite to mapOf(Species.Cadmium to 1, Species.Oxygen to 1),
    Species.Tenorite to mapOf(Species.Copper to 1, Species.Oxygen to 1),
    Species.Baddeleyite to mapOf(Species.Zirconium to 1, Species.Oxygen to 2),
    Species.Columbite to mapOf(Species.Iron to 1, Species.Niobium to 2, Species.Oxygen to 6),
    Species.Tantalite to mapOf(Species.Iron to 1, Species.Tantalum to 2, Species.Oxygen to 6),
    Species.Uraninite to mapOf(Species.Uranium to 1, Species.Oxygen to 2),
    Species.Thorianite to mapOf(Species.Thorium to 1, Species.Oxygen to 2),
    Species.Boracite to mapOf(Species.Magnesium to 3, Species.Boron to 7, Species.Oxygen to 13, Species.Chlorine to 1),

    // ── Sulfides, arsenides, selenides, tellurides, halides of silver ──
    Species.Troilite to mapOf(Species.Iron to 1, Species.Sulfur to 1),
    Species.Pyrite to mapOf(Species.Iron to 1, Species.Sulfur to 2),
    Species.Pentlandite to mapOf(Species.Iron to 4, Species.Nickel to 5, Species.Sulfur to 8),
    Species.Chalcopyrite to mapOf(Species.Copper to 1, Species.Iron to 1, Species.Sulfur to 2),
    Species.Sphalerite to mapOf(Species.Zinc to 1, Species.Sulfur to 1),
    Species.Galena to mapOf(Species.Lead to 1, Species.Sulfur to 1),
    Species.Molybdenite to mapOf(Species.Molybdenum to 1, Species.Sulfur to 2),
    Species.Rheniite to mapOf(Species.Rhenium to 1, Species.Sulfur to 2),
    Species.Arsenopyrite to mapOf(Species.Iron to 1, Species.Arsenic to 1, Species.Sulfur to 1),
    Species.Cobaltite to mapOf(Species.Cobalt to 1, Species.Arsenic to 1, Species.Sulfur to 1),
    Species.Niccolite to mapOf(Species.Nickel to 1, Species.Arsenic to 1),
    Species.Stibnite to mapOf(Species.Antimony to 2, Species.Sulfur to 3),
    Species.Bismuthinite to mapOf(Species.Bismuth to 2, Species.Sulfur to 3),
    Species.Cinnabar to mapOf(Species.Mercury to 1, Species.Sulfur to 1),
    Species.Argentite to mapOf(Species.Silver to 2, Species.Sulfur to 1),
    Species.Greenockite to mapOf(Species.Cadmium to 1, Species.Sulfur to 1),
    Species.Gallite to mapOf(Species.Copper to 1, Species.Gallium to 1, Species.Sulfur to 2),
    Species.Roquesite to mapOf(Species.Copper to 1, Species.Indium to 1, Species.Sulfur to 2),
    Species.Argyrodite to mapOf(Species.Silver to 8, Species.Germanium to 1, Species.Sulfur to 6),
    Species.Bowieite to mapOf(Species.Rhodium to 2, Species.Sulfur to 3),
    Species.Laurite to mapOf(Species.Ruthenium to 1, Species.Sulfur to 2),
    Species.Lorandite to mapOf(Species.Thallium to 1, Species.Arsenic to 1, Species.Sulfur to 2),
    Species.Sperrylite to mapOf(Species.Platinum to 1, Species.Arsenic to 2),
    Species.Clausthalite to mapOf(Species.Lead to 1, Species.Selenium to 1),
    Species.Calaverite to mapOf(Species.Gold to 1, Species.Tellurium to 2),
    Species.Bromargyrite to mapOf(Species.Silver to 1, Species.Bromine to 1),
    Species.Iodargyrite to mapOf(Species.Silver to 1, Species.Iodine to 1),

    // ── Carbonates, phosphates, sulfates, tungstates, vanadates, salts ──
    Species.Calcite to mapOf(Species.Calcium to 1, Species.Carbon to 1, Species.Oxygen to 3),
    Species.Magnesite to mapOf(Species.Magnesium to 1, Species.Carbon to 1, Species.Oxygen to 3),
    Species.Rhodochrosite to mapOf(Species.Manganese to 1, Species.Carbon to 1, Species.Oxygen to 3),
    Species.Dolomite to mapOf(Species.Calcium to 1, Species.Magnesium to 1, Species.Carbon to 2, Species.Oxygen to 6),
    Species.Apatite to mapOf(Species.Calcium to 5, Species.Phosphorus to 3, Species.Oxygen to 12, Species.Fluorine to 1),
    Species.Monazite to mapOf(Species.Cerium to 1, Species.Phosphorus to 1, Species.Oxygen to 4),
    Species.Xenotime to mapOf(Species.Yttrium to 1, Species.Phosphorus to 1, Species.Oxygen to 4),
    Species.Bastnasite to mapOf(Species.Cerium to 1, Species.Carbon to 1, Species.Oxygen to 3, Species.Fluorine to 1),
    // CaSO4·2H2O — the two waters of crystallisation are just four more hydrogens and two more
    // oxygens as far as mass is concerned, which is the only thing this map is for.
    Species.Gypsum to mapOf(Species.Calcium to 1, Species.Sulfur to 1, Species.Oxygen to 6, Species.Hydrogen to 4),
    Species.Celestine to mapOf(Species.Strontium to 1, Species.Sulfur to 1, Species.Oxygen to 4),
    Species.Barite to mapOf(Species.Barium to 1, Species.Sulfur to 1, Species.Oxygen to 4),
    Species.Scheelite to mapOf(Species.Calcium to 1, Species.Tungsten to 1, Species.Oxygen to 4),
    Species.Vanadinite to mapOf(Species.Lead to 5, Species.Vanadium to 3, Species.Oxygen to 12, Species.Chlorine to 1),
    Species.Halite to mapOf(Species.Sodium to 1, Species.Chlorine to 1),
    Species.Sylvite to mapOf(Species.Potassium to 1, Species.Chlorine to 1),
    Species.Fluorite to mapOf(Species.Calcium to 1, Species.Fluorine to 2),

    // ── The ices, which are compounds too ──
    Species.Water to mapOf(Species.Hydrogen to 2, Species.Oxygen to 1),
    Species.CarbonDioxide to mapOf(Species.Carbon to 1, Species.Oxygen to 2),
    Species.CarbonMonoxide to mapOf(Species.Carbon to 1, Species.Oxygen to 1),
    Species.Ammonia to mapOf(Species.Nitrogen to 1, Species.Hydrogen to 3),
    Species.Methane to mapOf(Species.Carbon to 1, Species.Hydrogen to 4),
    Species.HydrogenSulfide to mapOf(Species.Hydrogen to 2, Species.Sulfur to 1),
    Species.SulfurDioxide to mapOf(Species.Sulfur to 1, Species.Oxygen to 2),

    // ── Biology ──
    Species.Algae to mapOf(Species.Carbon to 6, Species.Hydrogen to 12, Species.Oxygen to 6),

    // ── The manufactured materials, which are ratios rather than molecules ──
    //
    // ⛔ **Neither of these is a compound and the table knows it.** Steel is a solid solution and
    // firebrick is a two-phase ceramic; what is written here is the integer formula unit whose mass
    // split *is* the recipe, so that an alloying reaction closes atom by atom against the same
    // oracle every real mineral answers to. Fe₉₉C is 0.216% carbon by mass and (MgO)₁₁(SiO₂)₆ is
    // 55.0% magnesia — the two recipes, with no second place to state them and no rounding rule
    // standing between the recipe and what conserves.
    //
    // ⚠️ They are keys here for that arithmetic and for nothing else. Both take
    // [Species.relativeAbundance] of zero, so no rock rolls either, and `everyMineralIsMinedOrMade`
    // holds them to the same standard as lime and periclase: something in `REACTIONS` has to make
    // them or they are dead weight.
    Species.Steel to mapOf(Species.Iron to 99, Species.Carbon to 1),
    Species.Firebrick to mapOf(Species.Magnesium to 11, Species.Silicon to 6, Species.Oxygen to 23),
)

/**
 * How the lanthanide site of a rare-earth mineral is actually occupied, in parts per thousand.
 *
 * [MINERALS] writes monazite as CePO₄ so that its molar mass is an exact integer. Real monazite is a
 * solid solution: the site holds whichever lanthanide was to hand when the crystal grew, and the
 * suite it ends up with is a property of the mineral. That is the entire reason rare earths are
 * hard — they are chemically almost indistinguishable, so you cannot smelt them apart, you have to
 * separate them, and the ore decides what you are separating.
 *
 * The split between the two families is the real one: phosphates of the light lanthanides (monazite,
 * bastnäsite) and of the heavy ones plus yttrium (xenotime). An asteroid with one is not a substitute
 * for an asteroid with the other, which is what makes prospecting for them worth doing.
 *
 * Each list sums to 1000. `MineralTest` says so, because a suite that quietly summed to 998 would
 * lose two parts in a thousand of every rare earth in the game.
 */
val LANTHANIDE_SUITE: Map<Species, Map<Species, Int>> = mapOf(
    // Light rare earths. Cerium dominates, which is why cerium was found first and is still cheap.
    Species.Monazite to mapOf(
        Species.Lanthanum to 230,
        Species.Cerium to 460,
        Species.Praseodymium to 55,
        Species.Neodymium to 190,
        Species.Samarium to 55,
        Species.Europium to 10,
    ),
    Species.Bastnasite to mapOf(
        Species.Lanthanum to 320,
        Species.Cerium to 490,
        Species.Praseodymium to 45,
        Species.Neodymium to 140,
        Species.Samarium to 5,
    ),
    // Heavy rare earths, on an yttrium backbone. The valuable end: dysprosium and terbium are the
    // ones that are actually scarce, and they only ever turn up here.
    Species.Xenotime to mapOf(
        Species.Yttrium to 600,
        Species.Gadolinium to 40,
        Species.Terbium to 10,
        Species.Dysprosium to 85,
        Species.Holmium to 20,
        Species.Erbium to 65,
        Species.Thulium to 10,
        Species.Ytterbium to 60,
        Species.Lutetium to 10,
        Species.Neodymium to 100,
    ),
)

/** The species that are made of something — the keys of [MINERALS], as a set, for quick membership. */
val COMPOUNDS: Set<Species> = MINERALS.keys

/** A species with no formula: an element, and so the end of any decomposition chain. */
val Species.isElement: Boolean get() = this !in COMPOUNDS

/**
 * What one formula unit of [mineral] weighs, derived from its atoms.
 *
 * This is the oracle [Species.molarMass] is checked against rather than a second copy of it, which
 * is the only arrangement in which a typo is detectable.
 */
fun derivedMolarMass(mineral: Species): Int =
    MINERALS[mineral]?.entries?.sumOf { (element, atoms) -> element.atomicMass * atoms } ?: mineral.molarMass

/**
 * How a formula unit of [mineral] splits by **mass**, in parts per thousand of the whole.
 *
 * The shape a refining step wants: multiply by the mass of ore and you have the yield. Returns an
 * empty map for an element, which has nothing to split into.
 *
 * ⚠️ Parts are floored individually and therefore sum to **at most** 1000, not exactly — the
 * remainder is the rounding, and a caller that must conserve mass should hand the shortfall to the
 * gangue rather than assume it away. [apportion] is the tool for doing that exactly; this function
 * is for display and for deciding whether a rock is worth mining.
 */
fun massPartsPerThousand(mineral: Species): Map<Species, Int> {
    val formula = MINERALS[mineral] ?: return emptyMap()
    val total = derivedMolarMass(mineral)
    if (total <= 0) return emptyMap()
    return formula.mapValues { (element, atoms) -> element.atomicMass * atoms * 1000 / total }
}
