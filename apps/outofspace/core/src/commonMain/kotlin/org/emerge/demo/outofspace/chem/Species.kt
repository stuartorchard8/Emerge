package org.emerge.demo.outofspace.chem

/**
 * Class estimates for [Species.milliWattsPerMetreKelvin], and **the way this table tells you which
 * numbers are measured and which are not.**
 *
 * ⛔ **The convention is the transparency mechanism: a bare integer in the table is a measured value
 * and one of these names is a class estimate.** There is no flag and no second column to fall out of
 * step — you can see which is which by looking, and a value that is later measured stops being an
 * estimate by being written as a number. 128 of the 170 species carry measured conductivities; the
 * 42 that carry one of these do not.
 *
 * ⚠️ **This is the same standard [Species] already holds its specific heats to**, and for the same
 * stated reason: measured thermal conductivities for obscure minerals are not something to invent
 * precision about, and a value honest to its class is what the simulation actually needs. The
 * classes are real — conduction in a non-metal is phonon transport, and it is structure that sets
 * it, which is why silicates cluster and sulfides cluster somewhere else.
 *
 * ⚠️ **They are deliberately coarse.** A single number per structural class is a claim that any two
 * silicates conduct within a factor of two of each other, which is roughly true and is a great deal
 * better than the alternative the game had until now, which was that only five substances had a
 * conductivity at all.
 */
private const val K_SILICATE = 3_000

/** Dense oxides. Higher than the silicates: simpler lattices scatter phonons less. */
private const val K_OXIDE = 5_000

/** Sulfides, which are semiconductors as often as they are insulators. */
private const val K_SULFIDE = 5_000

/** Carbonates. */
private const val K_CARBONATE = 3_500

/** Phosphates, the poorest conductors of the rock-forming classes. */
private const val K_PHOSPHATE = 2_000

/** Sulfates. */
private const val K_SULFATE = 1_500

/** Halide salts, which conduct better than any other mineral class here. */
private const val K_SALT = 6_000

/** Living tissue, which is mostly water and conducts like it. */
private const val K_ORGANIC = 500

/**
 * Everything the world can be made of: elements, the minerals that carry them, and the ices.
 *
 * ### The model, and why it changed
 *
 * This table used to be a list of elements with [relativeAbundance] weights against iron, so a rock
 * was a pile of loose atoms. That is not what a rock is, and the old [Osmium] doc said so: real ores
 * are **compounds**, abundance is a fact about the *mineral*, and smelting is the step that takes one
 * apart. Under the old scheme osmium needed an abundance of 1 against iron's 410 — a quarter of a
 * per cent, where the truth is a part per billion — because no integer weighting against iron could
 * carry nine orders of magnitude.
 *
 * So the model is now two-tier:
 *
 *  - **Minerals** and **ices** are what asteroids are made of, and they carry [relativeAbundance].
 *  - **Elements** are what you get by taking a mineral apart, and almost all of them have abundance
 *    zero — they do not occur loose. The exceptions are real and interesting: iron and nickel occur
 *    native as kamacite, carbon as graphite, sulfur as native sulfur, and the noble metals as
 *    nuggets, which is exactly why those are the metals humans found first.
 *
 * Osmium's rarity now falls out of the model instead of fighting it: it is rare because it rides in
 * trace amounts on platinum-group minerals, not because a weighting says so.
 *
 * ⚠️ **Nothing decomposes minerals yet.** [org.emerge.demo.outofspace.chem.smelt] still picks a
 * mixture's dominant species and subtracts impurities, which was the right rule for a pile of atoms
 * and is the wrong one for a pile of hematite — a pure mineral reads as perfectly clean ore and
 * smelts at full yield into a nonexistent ingot. [MINERALS] holds the formulae that a decomposition
 * step needs, and `MineralTest` proves they are self-consistent, but wiring them into smelting is
 * still to do. Until then a mineral is a thing you can mine and move, not a thing you can refine.
 *
 * ### Reading the numbers
 *
 * [molarMass] is g/mol, and for the diatomic gases it is the **molecular** mass — `Nitrogen` is N₂ at
 * 28, not N at 14 — because that is what sets pressure, which is the only thing the sim asks it for.
 * [MINERALS] knows the atomic masses separately, since a formula counts atoms.
 *
 * [specificHeat] is J/kg/K and [solidKgPerCubicMetre] is the measured density of the pure solid.
 * Element values are textbook. **Mineral specific heats are good to about ±15%**, and a few are
 * class estimates read off their structure — silicates land near 800, sulfides near 500, dense oxides
 * near 250 — because measured values for the obscure ones are not something to invent precision
 * about. They are honest to their class, which is what the sim actually needs; if one ever drives a
 * decision, measure it then.
 *
 * [relativeAbundance] is **parts per hundred million of a reference rock, by mass**. A chunk is rolled
 * by drawing against these across [NATURAL], so only the ratios matter — but stating them on an
 * absolute scale is what lets them be real numbers instead of invented ones. Forsterite at 28,000,000
 * is 28% of the rock; osmium at 49 is 490 parts per billion, which is what osmium actually is in a
 * chondrite.
 *
 * ⚠️ **The scale was 1..380 and that was too coarse to be meaningful**, in a way that was invisible
 * until it was measured. The spawner modulates abundance by noise (`RockSpawner.mixtureForChunk`)
 * with `abundance + 15 × noise.scaleInt(abundance)`, and at `abundance = 1` that expression can only
 * return 1 or 16 — the noise degenerates into a **coin flip**. Every one of the fifty-one species
 * that sat at 1 or 2 therefore behaved identically: present in about 2% of chunks, always at exactly
 * one part per thousand, never any other value and never concentrated anywhere. Gold, osmium,
 * uraninite and xenon were the same mineral wearing four names. The fix is resolution, not tuning:
 * with five to seven significant figures the noise has something to vary and a rare mineral can have
 * a *deposit* rather than a dice roll.
 *
 * ⚠️ The weights sum to roughly 1.15, not 1.0, because this one table describes both a **rocky** body
 * and an **icy** one — the silicates and the ices are each stated at their share of the body that
 * has them, and no single rock is both. Normalising them together would make every asteroid a bland
 * average of the two, which is the spawner's problem to solve and not this table's to pre-empt.
 *
 * ⚠️ **Headroom, before anyone raises these further.** `RockSpawner` holds the modulated weight in an
 * `IntArray`, and the modulation multiplies by up to 16 — so the largest safe abundance is about
 * 1.3×10⁸, and forsterite at 2.8×10⁷ leaves roughly 4× of margin. Past that the array has to become
 * a `LongArray` first. `Frac.scaleInt` is fine either way: it computes `raw × o / Int.MAX` in `Long`,
 * and with `noise` in [0,1] the worst product is ~9.4×10¹⁷ against `Long`'s 9.2×10¹⁸.
 *
 * ### Declaration order
 *
 * Order is [Mixture] iteration order and the [Mixture.dominant] tie-break, and it is **no longer
 * append-only** — saves write species by name ([org.emerge.demo.outofspace.world.Save] emits
 * `NAME=mass` and reads by lookup), so nothing on disk depends on an ordinal. Reordering does move
 * two things: which species wins an exact tie in [Mixture.dominant], and which index `apportion`
 * hands a spare unit to. Both are deterministic, neither is load-bearing.
 */
enum class Species(
    val molarMass: Int,
    val specificHeat: Int,
    val solidKgPerCubicMetre: Int,
    val relativeAbundance: Int = 0,
    /**
     * Thermal conductivity of the solid at room temperature, in **milliwatts per metre per kelvin**.
     *
     * Milliwatts because the range this has to cover is enormous and the bottom of it matters:
     * silver is 429 W/m/K and solid chlorine is 0.089, which is four and a half orders of magnitude,
     * and rounding the insulators to whole watts would make every ice, every salt and half the
     * non-metals identical at zero. As integers in milliwatts the whole table has at least two
     * significant figures.
     *
     * ⚠️ **A bare integer here is measured; a `K_*` constant is a class estimate** — see the
     * constants at the top of this file, which is where that convention is stated and counted.
     *
     * ⛔ **Nothing reads this yet**, and that is deliberate. A material's time constant was five
     * hand-tuned game numbers that do *not* follow from physics — they span a factor of thirteen
     * against `(ρ·c)/k`, because they were back-derived from conductances tuned before densities were
     * real. Wiring this in therefore re-tunes every thermal behaviour in the game, which is a
     * decision and not a refactor. The table lands first so that decision can be made against real
     * numbers.
     */
    val milliWattsPerMetreKelvin: Int = 0,
    /**
     * The temperature at which this stops being a solid, in kelvin.
     *
     * ⛔ **"Stops being a solid" and not "melts", because for a good many of these there is no
     * liquid.** Graphite sublimes, arsenic and cinnabar sublime, calcite and magnesite and pyrite
     * *decompose* well below any melting point, gypsum dehydrates, and helium does not solidify at
     * one atmosphere at any temperature at all. Each of those carries a comment saying which it is.
     * What the number means uniformly is: above here, a structure built out of this is no longer a
     * structure.
     *
     * ⚠️ **For the 22 species in [CRITICAL] this is the triple point, and `SpeciesTest` asserts they
     * agree.** The triple point *is* the melting point for practical purposes — they differ by a
     * fraction of a kelvin at one atmosphere — so stating it twice would be two numbers free to
     * drift. Stating it twice and *checking* is the arrangement the rest of this table already uses
     * for molar mass against [MINERALS].
     *
     * ⛔ **Nothing reads this yet either.** It exists so that a building can be made of something
     * that melts, which is the next increment and not this one.
     */
    val meltingKelvin: Int = 0,
) {
    // ══ ELEMENTS ══════════════════════════════════════════════════════════════════════════════
    //
    // Abundance is zero unless the element genuinely occurs native. Everything else arrives by
    // taking a mineral apart.

    // ── The structural metals ──
    Iron(56, 450, solidKgPerCubicMetre = 7870, relativeAbundance = 7000000, milliWattsPerMetreKelvin = 80_000, meltingKelvin = 1811),   // kamacite/taenite
    Nickel(59, 444, solidKgPerCubicMetre = 8908, relativeAbundance = 1200000, milliWattsPerMetreKelvin = 91_000, meltingKelvin = 1728),   // alloyed with the above
    Cobalt(59, 421, solidKgPerCubicMetre = 8900, milliWattsPerMetreKelvin = 100_000, meltingKelvin = 1768),
    Aluminum(27, 900, solidKgPerCubicMetre = 2700, milliWattsPerMetreKelvin = 237_000, meltingKelvin = 933),
    Titanium(48, 520, solidKgPerCubicMetre = 4510, milliWattsPerMetreKelvin = 22_000, meltingKelvin = 1941),
    Magnesium(24, 1023, solidKgPerCubicMetre = 1738, milliWattsPerMetreKelvin = 156_000, meltingKelvin = 923),
    Silicon(28, 705, solidKgPerCubicMetre = 2330, milliWattsPerMetreKelvin = 150_000, meltingKelvin = 1687),
    Chromium(52, 449, solidKgPerCubicMetre = 7150, milliWattsPerMetreKelvin = 94_000, meltingKelvin = 2180),
    Manganese(55, 479, solidKgPerCubicMetre = 7440, milliWattsPerMetreKelvin = 8_000, meltingKelvin = 1519),
    Vanadium(51, 489, solidKgPerCubicMetre = 6110, milliWattsPerMetreKelvin = 31_000, meltingKelvin = 2183),
    Copper(64, 385, solidKgPerCubicMetre = 8960, relativeAbundance = 100, milliWattsPerMetreKelvin = 401_000, meltingKelvin = 1358),   // native copper, rare
    Zinc(65, 388, solidKgPerCubicMetre = 7140, milliWattsPerMetreKelvin = 116_000, meltingKelvin = 693),
    Lead(207, 127, solidKgPerCubicMetre = 11340, milliWattsPerMetreKelvin = 35_000, meltingKelvin = 601),
    Tin(119, 228, solidKgPerCubicMetre = 7310, milliWattsPerMetreKelvin = 67_000, meltingKelvin = 505),

    // ── Non-metals and metalloids ──
    Carbon(12, 710, solidKgPerCubicMetre = 2260, relativeAbundance = 500000, milliWattsPerMetreKelvin = 130_000, meltingKelvin = 3915),   // graphite, which sublimes -- no liquid at one atmosphere
    Sulfur(32, 710, solidKgPerCubicMetre = 2070, relativeAbundance = 200000, milliWattsPerMetreKelvin = 270, meltingKelvin = 388),   // native sulfur
    Phosphorus(31, 769, solidKgPerCubicMetre = 1820, milliWattsPerMetreKelvin = 240, meltingKelvin = 317),
    Boron(11, 1026, solidKgPerCubicMetre = 2340, milliWattsPerMetreKelvin = 27_000, meltingKelvin = 2349),
    Arsenic(75, 329, solidKgPerCubicMetre = 5727, milliWattsPerMetreKelvin = 50_000, meltingKelvin = 887),   // sublimes
    Antimony(122, 207, solidKgPerCubicMetre = 6697, milliWattsPerMetreKelvin = 24_000, meltingKelvin = 904),
    Bismuth(209, 122, solidKgPerCubicMetre = 9780, milliWattsPerMetreKelvin = 8_000, meltingKelvin = 545),
    Selenium(79, 321, solidKgPerCubicMetre = 4810, milliWattsPerMetreKelvin = 520, meltingKelvin = 494),
    Tellurium(128, 202, solidKgPerCubicMetre = 6240, milliWattsPerMetreKelvin = 3_000, meltingKelvin = 723),
    Germanium(73, 320, solidKgPerCubicMetre = 5323, milliWattsPerMetreKelvin = 60_000, meltingKelvin = 1211),
    Gallium(70, 371, solidKgPerCubicMetre = 5910, milliWattsPerMetreKelvin = 41_000, meltingKelvin = 303),
    Indium(115, 233, solidKgPerCubicMetre = 7310, milliWattsPerMetreKelvin = 82_000, meltingKelvin = 430),
    Thallium(204, 129, solidKgPerCubicMetre = 11850, milliWattsPerMetreKelvin = 46_000, meltingKelvin = 577),
    Cadmium(112, 232, solidKgPerCubicMetre = 8650, milliWattsPerMetreKelvin = 97_000, meltingKelvin = 594),
    Mercury(201, 140, solidKgPerCubicMetre = 13534, milliWattsPerMetreKelvin = 8_300, meltingKelvin = 234),

    // ── The reactive light elements: never native, always locked in a salt or a silicate ──
    Lithium(7, 3580, solidKgPerCubicMetre = 534, milliWattsPerMetreKelvin = 85_000, meltingKelvin = 454),
    Beryllium(9, 1825, solidKgPerCubicMetre = 1850, milliWattsPerMetreKelvin = 200_000, meltingKelvin = 1560),
    Sodium(23, 1228, solidKgPerCubicMetre = 971, milliWattsPerMetreKelvin = 140_000, meltingKelvin = 371),
    Potassium(39, 757, solidKgPerCubicMetre = 862, milliWattsPerMetreKelvin = 100_000, meltingKelvin = 337),
    Calcium(40, 631, solidKgPerCubicMetre = 1550, milliWattsPerMetreKelvin = 200_000, meltingKelvin = 1115),
    Rubidium(85, 363, solidKgPerCubicMetre = 1532, milliWattsPerMetreKelvin = 58_000, meltingKelvin = 312),
    Strontium(88, 301, solidKgPerCubicMetre = 2640, milliWattsPerMetreKelvin = 35_000, meltingKelvin = 1050),
    Cesium(133, 242, solidKgPerCubicMetre = 1930, milliWattsPerMetreKelvin = 36_000, meltingKelvin = 302),
    Barium(137, 204, solidKgPerCubicMetre = 3510, milliWattsPerMetreKelvin = 18_000, meltingKelvin = 1000),

    // ── The refractory transition metals ──
    Scandium(45, 568, solidKgPerCubicMetre = 2985, milliWattsPerMetreKelvin = 16_000, meltingKelvin = 1814),
    Yttrium(89, 298, solidKgPerCubicMetre = 4472, milliWattsPerMetreKelvin = 17_000, meltingKelvin = 1799),
    Zirconium(91, 278, solidKgPerCubicMetre = 6520, milliWattsPerMetreKelvin = 23_000, meltingKelvin = 2128),
    Hafnium(178, 144, solidKgPerCubicMetre = 13310, milliWattsPerMetreKelvin = 23_000, meltingKelvin = 2506),
    Niobium(93, 265, solidKgPerCubicMetre = 8570, milliWattsPerMetreKelvin = 54_000, meltingKelvin = 2750),
    Tantalum(181, 140, solidKgPerCubicMetre = 16650, milliWattsPerMetreKelvin = 57_000, meltingKelvin = 3290),
    Molybdenum(96, 251, solidKgPerCubicMetre = 10280, milliWattsPerMetreKelvin = 138_000, meltingKelvin = 2896),
    Tungsten(184, 132, solidKgPerCubicMetre = 19250, milliWattsPerMetreKelvin = 173_000, meltingKelvin = 3695),
    Rhenium(186, 137, solidKgPerCubicMetre = 21020, milliWattsPerMetreKelvin = 48_000, meltingKelvin = 3459),
    /**
     * The noble metals, which occur native for the same reason they are noble — nothing oxidises
     * them, so they sit in the rock as metal. That is why [relativeAbundance] is non-zero here and
     * zero for, say, [Aluminum], which is far more common but never loose.
     */
    Gold(197, 129, solidKgPerCubicMetre = 19300, relativeAbundance = 14, milliWattsPerMetreKelvin = 318_000, meltingKelvin = 1337),
    Silver(108, 235, solidKgPerCubicMetre = 10490, relativeAbundance = 5, milliWattsPerMetreKelvin = 429_000, meltingKelvin = 1235),
    Platinum(195, 133, solidKgPerCubicMetre = 21450, relativeAbundance = 50, milliWattsPerMetreKelvin = 72_000, meltingKelvin = 2041),
    Palladium(106, 244, solidKgPerCubicMetre = 12020, relativeAbundance = 55, milliWattsPerMetreKelvin = 72_000, meltingKelvin = 1828),
    Rhodium(103, 243, solidKgPerCubicMetre = 12410, milliWattsPerMetreKelvin = 150_000, meltingKelvin = 2237),
    Ruthenium(101, 238, solidKgPerCubicMetre = 12450, milliWattsPerMetreKelvin = 117_000, meltingKelvin = 2607),
    /** Native, as osmiridium grains alongside [Osmium] — the two do not occur apart. */
    Iridium(192, 131, solidKgPerCubicMetre = 22560, relativeAbundance = 47, milliWattsPerMetreKelvin = 147_000, meltingKelvin = 2719),
    /**
     * The densest solid there is, and the ceiling of the world's mass range.
     *
     * Osmium is 22,590 kg/m³ — the top of the periodic table by density, with iridium a whisker
     * behind and no compound or alloy above either, since a compound necessarily dilutes with
     * something lighter. It is here so that the ceiling is a **named physical fact** rather than a
     * side-effect of uranium happening to be the heaviest thing on the list. See `NUMERIC_LIMITS.md`
     * §3: a tile of it is 18,749,700 g, and nothing can ever be denser.
     *
     * The abundance of 1 is no longer a fiction. Osmium occurs **native**, as osmiridium grains
     * with [Iridium], and 1 against olivine's 380 is now a weight among *minerals* rather than
     * against loose iron atoms — so the old doc's complaint, that no integer could express a
     * part-per-billion against iron's 410, simply stops applying. The two-tier model absorbed it.
     */
    Osmium(190, 130, solidKgPerCubicMetre = 22590, relativeAbundance = 49, milliWattsPerMetreKelvin = 88_000, meltingKelvin = 3306),

    // ── The lanthanides, which replace the fictional `RareEarth` ──
    //
    // They are here as individuals because that is the whole interest of rare earths: they are
    // chemically near-identical, occur together in the same two minerals, and separating them is the
    // hard and valuable step. A single blended species threw that away.
    Lanthanum(139, 195, solidKgPerCubicMetre = 6146, milliWattsPerMetreKelvin = 13_000, meltingKelvin = 1193),
    Cerium(140, 192, solidKgPerCubicMetre = 6770, milliWattsPerMetreKelvin = 11_000, meltingKelvin = 1068),
    Praseodymium(141, 193, solidKgPerCubicMetre = 6770, milliWattsPerMetreKelvin = 13_000, meltingKelvin = 1208),
    Neodymium(144, 190, solidKgPerCubicMetre = 7010, milliWattsPerMetreKelvin = 17_000, meltingKelvin = 1297),
    Samarium(150, 197, solidKgPerCubicMetre = 7520, milliWattsPerMetreKelvin = 13_000, meltingKelvin = 1345),
    Europium(152, 182, solidKgPerCubicMetre = 5244, milliWattsPerMetreKelvin = 14_000, meltingKelvin = 1099),
    Gadolinium(157, 236, solidKgPerCubicMetre = 7900, milliWattsPerMetreKelvin = 11_000, meltingKelvin = 1585),
    Terbium(159, 182, solidKgPerCubicMetre = 8230, milliWattsPerMetreKelvin = 11_000, meltingKelvin = 1629),
    Dysprosium(163, 170, solidKgPerCubicMetre = 8540, milliWattsPerMetreKelvin = 11_000, meltingKelvin = 1680),
    Holmium(165, 165, solidKgPerCubicMetre = 8790, milliWattsPerMetreKelvin = 16_000, meltingKelvin = 1734),
    Erbium(167, 168, solidKgPerCubicMetre = 9066, milliWattsPerMetreKelvin = 15_000, meltingKelvin = 1802),
    Thulium(169, 160, solidKgPerCubicMetre = 9320, milliWattsPerMetreKelvin = 17_000, meltingKelvin = 1818),
    Ytterbium(173, 155, solidKgPerCubicMetre = 6900, milliWattsPerMetreKelvin = 39_000, meltingKelvin = 1097),
    Lutetium(175, 154, solidKgPerCubicMetre = 9841, milliWattsPerMetreKelvin = 16_000, meltingKelvin = 1925),

    // ── The actinides that actually occur ──
    Thorium(232, 113, solidKgPerCubicMetre = 11700, milliWattsPerMetreKelvin = 54_000, meltingKelvin = 2115),
    Uranium(238, 116, solidKgPerCubicMetre = 19100, milliWattsPerMetreKelvin = 27_000, meltingKelvin = 1405),

    // ── Halogens, as elements. Never native; here as smelting products of the salts ──
    Fluorine(38, 824, solidKgPerCubicMetre = 1500, milliWattsPerMetreKelvin = 280, meltingKelvin = 53),
    Chlorine(71, 479, solidKgPerCubicMetre = 2030, milliWattsPerMetreKelvin = 89, meltingKelvin = 172),
    Bromine(160, 474, solidKgPerCubicMetre = 3100, milliWattsPerMetreKelvin = 120, meltingKelvin = 266),
    Iodine(254, 214, solidKgPerCubicMetre = 4930, milliWattsPerMetreKelvin = 449, meltingKelvin = 387),

    // ══ THE SILICATES ═════════════════════════════════════════════════════════════════════════
    //
    // Most of an ordinary chondrite by mass, and so the anchor of the abundance scale. They are the
    // boring rock the interesting things are buried in — which is the point, because a game about
    // extraction needs the common case to be genuinely common.

    Forsterite(140, 840, solidKgPerCubicMetre = 3270, relativeAbundance = 28000000, milliWattsPerMetreKelvin = 5_000, meltingKelvin = 2163),   // Mg2SiO4, olivine
    Fayalite(204, 700, solidKgPerCubicMetre = 4390, relativeAbundance = 12000000, milliWattsPerMetreKelvin = 3_200, meltingKelvin = 1478),   // Fe2SiO4, olivine
    Enstatite(100, 800, solidKgPerCubicMetre = 3200, relativeAbundance = 18000000, milliWattsPerMetreKelvin = 4_500, meltingKelvin = 1830),   // MgSiO3, pyroxene
    Ferrosilite(132, 690, solidKgPerCubicMetre = 4000, relativeAbundance = 7000000, milliWattsPerMetreKelvin = K_SILICATE, meltingKelvin = 1550),   // FeSiO3, pyroxene
    Anorthite(278, 800, solidKgPerCubicMetre = 2730, relativeAbundance = 3500000, milliWattsPerMetreKelvin = 1_700, meltingKelvin = 1826),   // plagioclase, Ca
    Albite(262, 800, solidKgPerCubicMetre = 2620, relativeAbundance = 2500000, milliWattsPerMetreKelvin = 2_000, meltingKelvin = 1391),   // plagioclase, Na
    Orthoclase(278, 790, solidKgPerCubicMetre = 2560, relativeAbundance = 800000, milliWattsPerMetreKelvin = 2_300, meltingKelvin = 1473),   // feldspar, K

    /** SiO₂. Was called `Silica` — same numbers, a mineral's name instead of an oxide's. */
    Quartz(60, 700, solidKgPerCubicMetre = 2650, relativeAbundance = 1500000, milliWattsPerMetreKelvin = 7_700, meltingKelvin = 1983),
    /**
     * Mg₃Si₂O₅(OH)₄ — and the most quietly important mineral on this list.
     *
     * Serpentine is *hydrated* silicate: the water is chemically bound, not frozen. That makes it the
     * one water source that survives close to the sun, where an ice would have sublimed away long
     * ago, and so the difference between a dry inner-system rock and a wet one. It is 13% water by
     * mass and gives it up when heated.
     */
    Serpentine(276, 1000, solidKgPerCubicMetre = 2550, relativeAbundance = 6000000, milliWattsPerMetreKelvin = 2_500, meltingKelvin = 900),   // dehydrates rather than melts
    Zircon(183, 610, solidKgPerCubicMetre = 4650, relativeAbundance = 780, milliWattsPerMetreKelvin = 5_500, meltingKelvin = 2823),   // Zr, and Hf with it
    Thortveitite(258, 620, solidKgPerCubicMetre = 3450, relativeAbundance = 1700, milliWattsPerMetreKelvin = K_SILICATE, meltingKelvin = 1800),   // Sc2Si2O7 -- estimate
    Beryl(537, 830, solidKgPerCubicMetre = 2760, relativeAbundance = 50, milliWattsPerMetreKelvin = 4_000, meltingKelvin = 1650),   // Be3Al2Si6O18
    Spodumene(186, 900, solidKgPerCubicMetre = 3150, relativeAbundance = 3900, milliWattsPerMetreKelvin = K_SILICATE, meltingKelvin = 1700),   // LiAlSi2O6 -- estimate
    Pollucite(312, 700, solidKgPerCubicMetre = 2900, relativeAbundance = 44, milliWattsPerMetreKelvin = K_SILICATE, meltingKelvin = 1900),   // CsAlSi2O6 -- estimate

    // ══ THE OXIDES ════════════════════════════════════════════════════════════════════════════

    Magnetite(232, 620, solidKgPerCubicMetre = 5150, relativeAbundance = 1200000, milliWattsPerMetreKelvin = 5_100, meltingKelvin = 1811),   // Fe3O4
    Hematite(160, 650, solidKgPerCubicMetre = 5260, relativeAbundance = 800000, milliWattsPerMetreKelvin = 11_300, meltingKelvin = 1838),   // Fe2O3, iron(III)
    Wustite(72, 700, solidKgPerCubicMetre = 5745, relativeAbundance = 150000, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 1650),   // FeO, iron(II)
    Chromite(224, 590, solidKgPerCubicMetre = 4790, relativeAbundance = 350000, milliWattsPerMetreKelvin = 2_500, meltingKelvin = 2500),   // FeCr2O4
    Ilmenite(152, 620, solidKgPerCubicMetre = 4720, relativeAbundance = 120000, milliWattsPerMetreKelvin = 2_400, meltingKelvin = 1640),   // FeTiO3
    Rutile(80, 690, solidKgPerCubicMetre = 4230, relativeAbundance = 20000, milliWattsPerMetreKelvin = 8_800, meltingKelvin = 2116),   // TiO2
    Spinel(142, 820, solidKgPerCubicMetre = 3580, relativeAbundance = 250000, milliWattsPerMetreKelvin = 9_500, meltingKelvin = 2408),   // MgAl2O4

    // ⚠️ **Made, never found.** Both are what a carbonate leaves behind when it is calcined, and
    // neither survives in a rock that has ever met water or carbon dioxide — so both take the
    // default [relativeAbundance] of zero and no asteroid will ever contain either. They exist
    // because `Calcite -> lime + CO2` was named in this table's own documentation and in the
    // furnace's, and was impossible until now.
    Lime(56, 750, solidKgPerCubicMetre = 3340, milliWattsPerMetreKelvin = 8_000, meltingKelvin = 2886),   // CaO, quicklime
    Periclase(40, 925, solidKgPerCubicMetre = 3580, milliWattsPerMetreKelvin = 45_000, meltingKelvin = 3125),   // MgO
    Corundum(102, 775, solidKgPerCubicMetre = 3980, relativeAbundance = 50000, milliWattsPerMetreKelvin = 35_000, meltingKelvin = 2345),   // Al2O3
    Pyrolusite(87, 620, solidKgPerCubicMetre = 5060, relativeAbundance = 30000, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 808),   // MnO2 -- decomposes
    Cassiterite(151, 350, solidKgPerCubicMetre = 6990, relativeAbundance = 215, milliWattsPerMetreKelvin = 12_000, meltingKelvin = 1903),   // SnO2
    Molybdite(144, 527, solidKgPerCubicMetre = 4690, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 1075),   // MoO3 -- made by roasting molybdenite, never mined
    Zincite(81, 494, solidKgPerCubicMetre = 5606, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 2247),   // ZnO -- made by roasting sphalerite, never mined
    Monteponite(128, 339, solidKgPerCubicMetre = 8150, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 1500),   // CdO -- decomposes; made by roasting greenockite
    Baddeleyite(123, 460, solidKgPerCubicMetre = 5680, relativeAbundance = 100, milliWattsPerMetreKelvin = 2_000, meltingKelvin = 2988),   // ZrO2, the Hf carrier
    Columbite(338, 330, solidKgPerCubicMetre = 5300, relativeAbundance = 45, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 1600),   // FeNb2O6 -- estimate
    Tantalite(514, 240, solidKgPerCubicMetre = 8000, relativeAbundance = 2, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 1700),   // FeTa2O6 -- estimate
    Uraninite(270, 240, solidKgPerCubicMetre = 10970, relativeAbundance = 1, milliWattsPerMetreKelvin = 8_000, meltingKelvin = 3138),   // UO2
    Thorianite(264, 230, solidKgPerCubicMetre = 9860, relativeAbundance = 3, milliWattsPerMetreKelvin = 10_000, meltingKelvin = 3573),   // ThO2

    // ══ THE SULFIDES ══════════════════════════════════════════════════════════════════════════
    //
    // Where most of the interesting metals actually live. Troilite is abundant enough to be a real
    // sulfur source; the rest are the ore minerals a mining game is about.

    Troilite(88, 570, solidKgPerCubicMetre = 4740, relativeAbundance = 5000000, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1461),   // FeS
    Pyrite(120, 517, solidKgPerCubicMetre = 5010, relativeAbundance = 400000, milliWattsPerMetreKelvin = 20_000, meltingKelvin = 1016),   // FeS2 -- decomposes
    Pentlandite(775, 550, solidKgPerCubicMetre = 4800, relativeAbundance = 250000, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1200),   // Fe4Ni5S8, the nickel ore -- estimate
    Chalcopyrite(184, 540, solidKgPerCubicMetre = 4200, relativeAbundance = 60000, milliWattsPerMetreKelvin = 9_000, meltingKelvin = 1223),   // CuFeS2, the copper ore
    Sphalerite(97, 470, solidKgPerCubicMetre = 4090, relativeAbundance = 46300, milliWattsPerMetreKelvin = 26_000, meltingKelvin = 2038),   // ZnS, and Ga/Ge/Cd/In with it
    Galena(239, 210, solidKgPerCubicMetre = 7600, relativeAbundance = 290, milliWattsPerMetreKelvin = 2_300, meltingKelvin = 1391),   // PbS, and Ag/Se/Tl with it
    Molybdenite(160, 380, solidKgPerCubicMetre = 5060, relativeAbundance = 150, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 2098),   // MoS2, and Re with it
    Arsenopyrite(163, 400, solidKgPerCubicMetre = 6070, relativeAbundance = 390, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1200),   // FeAsS -- estimate
    Cobaltite(166, 430, solidKgPerCubicMetre = 6330, relativeAbundance = 500, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1400),   // CoAsS -- estimate
    Niccolite(134, 380, solidKgPerCubicMetre = 7770, relativeAbundance = 200, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1230),   // NiAs
    Stibnite(340, 210, solidKgPerCubicMetre = 4640, relativeAbundance = 19, milliWattsPerMetreKelvin = 2_000, meltingKelvin = 823),   // Sb2S3
    Bismuthinite(514, 190, solidKgPerCubicMetre = 6780, relativeAbundance = 14, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1050),   // Bi2S3
    Cinnabar(233, 150, solidKgPerCubicMetre = 8100, relativeAbundance = 35, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 853),   // HgS -- sublimes
    Argentite(248, 240, solidKgPerCubicMetre = 7230, relativeAbundance = 23, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1098),   // Ag2S
    Clausthalite(286, 190, solidKgPerCubicMetre = 8100, relativeAbundance = 800, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1338),   // PbSe
    Calaverite(453, 180, solidKgPerCubicMetre = 9240, relativeAbundance = 33, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 733),   // AuTe2
    Sperrylite(345, 250, solidKgPerCubicMetre = 10600, relativeAbundance = 170, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1800),   // PtAs2 -- estimate
    Laurite(165, 300, solidKgPerCubicMetre = 6990, relativeAbundance = 110, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1500),   // RuS2 -- estimate

    // ══ THE TRACE MINERALS ════════════════════════════════════════════════════════════════════
    //
    // Every one of these exists to be the *only* source of one element, and they are all real
    // minerals rather than inventions. They are the tail of the distribution: an abundance of 1
    // against olivine's 380 means a rock carrying any of them is a find, and it is the difference
    // between an asteroid worth stripping and one worth remembering.
    //
    // In fact most of these elements are recovered as by-products of smelting a commoner ore —
    // indium out of sphalerite, rhenium out of molybdenite — rather than from their own mineral.
    // Giving each its own species is the modelling choice that keeps decomposition a pure function
    // of the formula; a by-product yield would make it a function of the *process* instead. If
    // by-product recovery ever becomes a mechanic, these become the concentrated form rather than
    // the only form.

    Scheelite(288, 240, solidKgPerCubicMetre = 6010, relativeAbundance = 14, milliWattsPerMetreKelvin = K_OXIDE, meltingKelvin = 1855),   // CaWO4 — tungsten
    Rheniite(250, 200, solidKgPerCubicMetre = 7500, relativeAbundance = 5, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1300),   // ReS2 — rhenium -- estimate
    Hafnon(270, 300, solidKgPerCubicMetre = 7200, relativeAbundance = 17, milliWattsPerMetreKelvin = K_SILICATE, meltingKelvin = 2750),   // HfSiO4 — hafnium
    Boracite(392, 900, solidKgPerCubicMetre = 2910, relativeAbundance = 370, milliWattsPerMetreKelvin = K_SILICATE, meltingKelvin = 1368),   // Mg3B7O13Cl — boron
    Gallite(198, 420, solidKgPerCubicMetre = 4200, relativeAbundance = 2700, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1300),   // CuGaS2 — gallium -- estimate
    Argyrodite(1129, 250, solidKgPerCubicMetre = 6270, relativeAbundance = 300, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1100),   // Ag8GeS6 — germanium -- estimate
    Roquesite(243, 350, solidKgPerCubicMetre = 4700, relativeAbundance = 17, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1300),   // CuInS2 — indium -- estimate
    Greenockite(144, 340, solidKgPerCubicMetre = 4820, relativeAbundance = 90, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1750),   // CdS — cadmium
    Bowieite(302, 280, solidKgPerCubicMetre = 6500, relativeAbundance = 19, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 1500),   // Rh2S3 — rhodium -- estimate
    Lorandite(343, 240, solidKgPerCubicMetre = 5530, relativeAbundance = 24, milliWattsPerMetreKelvin = K_SULFIDE, meltingKelvin = 750),   // TlAsS2 — thallium
    Bromargyrite(188, 270, solidKgPerCubicMetre = 6470, relativeAbundance = 200, milliWattsPerMetreKelvin = K_SALT, meltingKelvin = 705),   // AgBr — bromine
    Iodargyrite(235, 230, solidKgPerCubicMetre = 5680, relativeAbundance = 60, milliWattsPerMetreKelvin = K_SALT, meltingKelvin = 831),   // AgI — iodine
    Rubicline(324, 620, solidKgPerCubicMetre = 2860, relativeAbundance = 880, milliWattsPerMetreKelvin = K_SILICATE, meltingKelvin = 1400),   // RbAlSi3O8 — rubidium -- estimate

    // ══ CARBONATES, PHOSPHATES, SULFATES AND SALTS ════════════════════════════════════════════

    Calcite(100, 830, solidKgPerCubicMetre = 2710, relativeAbundance = 500000, milliWattsPerMetreKelvin = 3_600, meltingKelvin = 1170),   // CaCO3 -- decomposes
    Dolomite(184, 900, solidKgPerCubicMetre = 2840, relativeAbundance = 200000, milliWattsPerMetreKelvin = 5_500, meltingKelvin = 1000),   // CaMg(CO3)2 -- decomposes
    Magnesite(84, 900, solidKgPerCubicMetre = 3000, relativeAbundance = 250000, milliWattsPerMetreKelvin = K_CARBONATE, meltingKelvin = 810),   // MgCO3 -- decomposes
    Rhodochrosite(115, 780, solidKgPerCubicMetre = 3700, relativeAbundance = 20000, milliWattsPerMetreKelvin = K_CARBONATE, meltingKelvin = 900),   // MnCO3 -- decomposes
    Apatite(504, 770, solidKgPerCubicMetre = 3190, relativeAbundance = 300000, milliWattsPerMetreKelvin = 1_400, meltingKelvin = 1917),   // Ca5(PO4)3F — the P source
    Monazite(235, 380, solidKgPerCubicMetre = 5150, relativeAbundance = 270, milliWattsPerMetreKelvin = K_PHOSPHATE, meltingKelvin = 2345),   // CePO4 — light rare earths
    Xenotime(184, 480, solidKgPerCubicMetre = 4450, relativeAbundance = 250, milliWattsPerMetreKelvin = K_PHOSPHATE, meltingKelvin = 2000),   // YPO4 — heavy rare earths -- estimate
    Bastnasite(219, 420, solidKgPerCubicMetre = 4950, relativeAbundance = 150, milliWattsPerMetreKelvin = K_CARBONATE, meltingKelvin = 900),   // CeCO3F -- decomposes
    Gypsum(172, 1070, solidKgPerCubicMetre = 2310, relativeAbundance = 100000, milliWattsPerMetreKelvin = 1_300, meltingKelvin = 400),   // CaSO4·2H2O -- dehydrates
    Celestine(184, 570, solidKgPerCubicMetre = 3970, relativeAbundance = 1600, milliWattsPerMetreKelvin = K_SULFATE, meltingKelvin = 1878),   // SrSO4
    Barite(233, 450, solidKgPerCubicMetre = 4480, relativeAbundance = 390, milliWattsPerMetreKelvin = 1_300, meltingKelvin = 1853),   // BaSO4
    Vanadinite(1415, 200, solidKgPerCubicMetre = 6880, relativeAbundance = 30, milliWattsPerMetreKelvin = K_PHOSPHATE, meltingKelvin = 1000),   // Pb5(VO4)3Cl -- estimate
    Halite(58, 880, solidKgPerCubicMetre = 2170, relativeAbundance = 100000, milliWattsPerMetreKelvin = 6_500, meltingKelvin = 1074),   // NaCl
    Sylvite(74, 690, solidKgPerCubicMetre = 1990, relativeAbundance = 20000, milliWattsPerMetreKelvin = 6_500, meltingKelvin = 1043),   // KCl, and Rb with it
    Fluorite(78, 850, solidKgPerCubicMetre = 3180, relativeAbundance = 20000, milliWattsPerMetreKelvin = 9_500, meltingKelvin = 1691),   // CaF2

    // ══ THE MANUFACTURED MATERIALS ════════════════════════════════════════════════════════════
    //
    // ⛔ **Made, never found, and neither of them is a compound.** Steel is a solid solution and a
    // magnesia-silica refractory is a two-phase ceramic; a rock contains neither, so both take the
    // default [relativeAbundance] of zero and no asteroid will ever hold one.
    //
    // They are species rather than named-material compositions because
    // a *recipe* is a thing the player should have to arrange once, in a furnace, rather than for
    // ever, on every belt. As a mixture, steel obliged a construction site to be fed iron and carbon
    // in the right ratio all the way from the ore field — nine hundred and ninety to ten, held
    // across a network that has no way to say so — and the tolerance that made that survivable is
    // exactly the tolerance that let a microgram of water ice into a hull plate. One species, one
    // reaction, one thing to route.
    //
    // ⚠️ **Their formulae in [MINERALS] state a ratio, not a molecule**, and that is the whole
    // licence being taken here. Fe₉₉C is not a phase anybody has ever isolated; it is the integer
    // pair that makes 0.22% carbon by mass come out exactly, so the alloying reaction closes atom by
    // atom against the same oracle every real mineral does. The alternative was a mass-fraction row
    // type, which would have put the one table in the game whose conservation is *structural*
    // alongside one whose conservation is a rounding rule.

    /**
     * Carbon steel: [Iron] with about a fifth of a per cent of [Carbon] dissolved in it.
     *
     * ⚠️ **The recipe moved, slightly and on purpose.** As a [Mixture] this was 990:10 by mass — one
     * per cent carbon, which is a high-carbon tool steel rather than the structural stuff a hull is
     * plate of. Stated as formula units it is 99 iron atoms to one of carbon, which is 0.216% by
     * mass: mild steel, and the integer pair closest to what the old recipe was reaching for.
     *
     * ⚠️ [specificHeat] and [solidKgPerCubicMetre] are **the textbook values for carbon steel, not
     * the mass-weighted average of its two components**, and the difference is real rather than a
     * rounding: 490 against iron-and-carbon's 451, and 7850 against their 7828. An alloy is not a
     * pile of its ingredients — that is what makes it worth having a name. The cost is that a hull
     * plate now holds about 11% more heat and weighs about 2% more than it did while steel was a
     * mixture, which is a change to the ship and is meant to be one.
     *
     * ⛔ **Steel does not oxidise, and nothing has been lost by that.** Iron scales to hematite at
     * [org.emerge.demo.outofspace.chem.IRON_OXIDATION_KELVIN] and the hull was 99% `Iron`, so it
     * looks as though a rusting hull is being retired here. It is not: the ambient sweep is given
     * `rail.stuff` and `buffers.stuff` and never the deck layer (`OutofspaceSim`), so a hull plate's
     * own metal has never been offered to a reaction and hull oxidation has never once fired.
     */
    Steel(5556, 490, solidKgPerCubicMetre = 7850, milliWattsPerMetreKelvin = 50_000, meltingKelvin = 1723),
    /**
     * Refractory brick: [Periclase] and [Quartz] fired together, 55:45 by mass.
     *
     * The same 550:450 the old `Material` mixture stated, and the same
     * numbers underneath it — 11 MgO to 6 SiO₂ is 55.0% to 45.0%, [specificHeat] is their
     * mass-weighted mean (823.75, rounded) and [solidKgPerCubicMetre] their harmonic one (3091.7,
     * rounded), so a furnace lining weighs and warms what it did before to within about a part in
     * ten thousand. ⚠️ **Not to the unit**, because both figures are integers and neither of those
     * means is one; the residual is a rounding and is named here rather than claimed away. Nothing
     * else about this machine moved except what has to be routed to build one.
     *
     * ⚠️ **A mass-weighted specific heat is the right answer here and a wrong one for [Steel]**, and
     * the asymmetry is not an inconsistency. Two oxides sharing a tile are two oxides sharing a tile
     * — Neumann-Kopp, and their heat capacities add. Carbon dissolved in iron changes the lattice it
     * is dissolved in, which is why steel's own measured value is 9% off the average of its parts.
     *
     * ⚠️ 3092 kg/m³ is the **fully dense** solid, not what a brick off a shelf weighs. A real
     * refractory is 20–30% porosity, and the game already says so somewhere else:
     * `DeckMachineKind.fillPermille` puts a quarter of a tile of this in a furnace wall.
     */
    Firebrick(800, 824, solidKgPerCubicMetre = 3092, milliWattsPerMetreKelvin = 2_500, meltingKelvin = 1890),   // softening point of a forsterite refractory, NOT a melting point

    // ══ THE VOLATILES ═════════════════════════════════════════════════════════════════════════
    //
    // Stated as the solids they are when cold enough to be part of a rock: ices, not gases. The
    // densities here are the *ice* densities, which is what a tile of a comet weighs.
    //
    // [molarMass] is the molecular mass for the diatomics — N₂ at 28, not N at 14 — because pressure
    // is the only thing that reads it. [MINERALS] counts atoms separately.

    Water(18, 4182, solidKgPerCubicMetre = 917, relativeAbundance = 12000000, milliWattsPerMetreKelvin = 2_200, meltingKelvin = 273),
    CarbonDioxide(44, 844, solidKgPerCubicMetre = 1560, relativeAbundance = 2000000, milliWattsPerMetreKelvin = 500, meltingKelvin = 217),
    Ammonia(17, 2060, solidKgPerCubicMetre = 817, relativeAbundance = 1000000, milliWattsPerMetreKelvin = 2_500, meltingKelvin = 195),
    Methane(16, 2220, solidKgPerCubicMetre = 422, relativeAbundance = 700000, milliWattsPerMetreKelvin = 400, meltingKelvin = 91),
    CarbonMonoxide(28, 1040, solidKgPerCubicMetre = 890, relativeAbundance = 500000, milliWattsPerMetreKelvin = 500, meltingKelvin = 68),
    HydrogenSulfide(34, 1000, solidKgPerCubicMetre = 1120, relativeAbundance = 200000, milliWattsPerMetreKelvin = 400, meltingKelvin = 188),
    SulfurDioxide(64, 640, solidKgPerCubicMetre = 1920, relativeAbundance = 80000, milliWattsPerMetreKelvin = 500, meltingKelvin = 198),
    Nitrogen(28, 1040, solidKgPerCubicMetre = 1030, relativeAbundance = 300000, milliWattsPerMetreKelvin = 300, meltingKelvin = 63),
    Hydrogen(2, 14300, solidKgPerCubicMetre = 88, relativeAbundance = 100000, milliWattsPerMetreKelvin = 400, meltingKelvin = 14),
    /** No abundance: free oxygen does not occur in nature. Every gram of it is refined out of a rock. */
    Oxygen(32, 918, solidKgPerCubicMetre = 1300, milliWattsPerMetreKelvin = 300, meltingKelvin = 54),
    /**
     * The third gas in air, and the one whose absence was quietly distorting the atmosphere.
     *
     * Dry air is 1.29% argon by mass and 0.064% CO₂. Before this species existed, ambient air put
     * **13 g of CO₂** in a kilogram tile — which is argon's share, wearing carbon dioxide's name,
     * because there was no noble gas to give it to. The consequence was an atmosphere twenty times
     * too rich in CO₂.
     *
     * Monatomic, so its specific heat is the lowest of the gases (520 J/kg/K against nitrogen's
     * 1040): there are no rotational modes to store energy in. Argon warms twice as fast as the air
     * around it, and that is a real behavioural difference rather than a rounding.
     */
    Argon(40, 520, solidKgPerCubicMetre = 1616, relativeAbundance = 100, milliWattsPerMetreKelvin = 400, meltingKelvin = 84),   // The other noble gases, trapped in ice in trace amounts. Individually near-worthless and
    // collectively the reason a gas-rich comet is worth visiting twice.
    Helium(4, 5193, solidKgPerCubicMetre = 145, relativeAbundance = 10, milliWattsPerMetreKelvin = 30, meltingKelvin = 1),   // does not solidify at any temperature at one atmosphere
    Neon(20, 1030, solidKgPerCubicMetre = 1440, relativeAbundance = 5, milliWattsPerMetreKelvin = 400, meltingKelvin = 25),
    Krypton(84, 248, solidKgPerCubicMetre = 2900, relativeAbundance = 2, milliWattsPerMetreKelvin = 500, meltingKelvin = 116),
    Xenon(131, 158, solidKgPerCubicMetre = 3100, relativeAbundance = 1, milliWattsPerMetreKelvin = 500, meltingKelvin = 161),
    /**
     * Biologically active species. Set to low abundance for now - will make accessible via economy in the future
     */
    Algae(180, 1250, solidKgPerCubicMetre = 1540, relativeAbundance = 1, milliWattsPerMetreKelvin = K_ORGANIC, meltingKelvin = 373),   // denatures
    ;

    companion object {
        /** Cached because `entries` allocates on some targets and this is read in inner loops. */
        val ALL: List<Species> = entries.toList()
        val COUNT: Int = ALL.size

        /** What a rock can be made of: the minerals, the ices, and the few native elements. */
        val NATURAL: List<Species> = ALL.filter { it.relativeAbundance > 0 }
    }
}
