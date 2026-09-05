package org.emerge.demo.outofspace.chem

/**
 * **What each substance costs to make out of its elements** — and therefore, by Hess's law, the
 * enthalpy of every reaction in the game.
 *
 * This is `MineralTest`'s argument applied to energy. [MINERALS] states what a mineral is made of as
 * atoms and [Species.molarMass] is *derived* from it, so a mineral cannot quietly weigh the wrong
 * amount. Until this file existed the energy side had no such spine: every row of [REACTIONS] carried
 * a hand-typed `enthalpyPerKg`, and a wrong one is invisible — the row balances atom for atom, its
 * onset is plausible, every test passes, and it yields the wrong amount of heat for ever.
 *
 * ⛔ **It was not a hypothetical.** Scoring the twenty-eight rows that existed when this landed
 * against the table below reproduced eighteen of them to within 2 kJ/mol — which is the evidence
 * that they *were* computed this way, by hand, once — and found seven that had drifted
 * substantively. Two were wrong by factors of three and one had the wrong sign. See
 * `FormationTest.everyReactionIsWorthWhatItsFormationEnthalpiesSay`, which is now the only place a
 * reaction's energy is stated.
 *
 * ### The reference phase, which is not a free choice
 *
 * A formation enthalpy is only meaningful against a stated phase, and the game has already decided
 * this one — twice, in two ledgers, and they agree:
 *
 * - **`cohesionOf` measures a fluid's bond energy as zero when it is vapour** and negative when it
 *   is condensed. So for anything in the air, the energy baseline *is* the gas.
 * - **`offGas` charges [vaporisationHeat] to lift matter out of a cargo layer**, which is the
 *   transformation between the two baselines, and `settleCohesion` credits the same function back on
 *   the way down. The cycle closes because the charge and the credit are the same call.
 *
 * So: **a species that can be a fluid is quoted as a gas; everything else is quoted as a solid.** Not
 * a simplification — the alternative bills the phase change twice, once inside the reaction and again
 * when the matter actually changes phase.
 *
 * ⚠️ **This is why the fires are quoted at their lower heating value and must stay that way.** Methane
 * burning to *gaseous* water is −802 kJ/mol; to liquid water it is −891. The 89 kJ between them is the
 * water's latent heat, and in this game that is `vaporisationHeat`'s to charge, not this table's. A
 * row quoted at −891 would release the condensation into a room whose water never condensed.
 *
 * ⚠️ **The distinction only bites below a species' critical temperature**, and nearly every reaction
 * here runs far above the critical point of every gas it makes — CO₂'s is 304 K and carbon does not
 * burn until 1000 K, so the two baselines coincide and the choice costs nothing. The exceptions are
 * enumerated and tested: see `FormationTest.theReferencePhaseOnlyMattersForSpeciesNamedHere`.
 *
 * ⛔ **SULFUR IS THE OPEN ONE, and it is quoted as a solid.** It is a [Fluid] with a critical
 * temperature of 1314 K, and pyrite decomposes at 1000 K — so it is the one *mineral* product the
 * game makes below its critical point, where the baseline genuinely matters and the rule would say
 * "gas". It is quoted at 0 anyway, i.e. as native solid sulfur, for two reasons: the game's `Sulfur`
 * weighs 32 g/mol and is declared monatomic (see [ATOMIC_MASS]), which is S₂'s mass wearing S's
 * formula, so "the gas-phase value" is not a single number a textbook will hand you; and quoting it
 * as a gas would move `S + O₂ → SO₂` off −297, which is the textbook figure for burning the solid
 * sulfur a belt actually carries. The same question is waiting for zinc, cadmium and mercury, which
 * are fluids for the express purpose of leaving a roasting bed as vapour — **it must be answered
 * before roasting lands**, and it is Stu's to answer.
 *
 * ### Units and sign
 *
 * kJ per mole of the substance as [Species.molarMass] describes it, at 298 K. **Negative is
 * released** — the universal convention for a formation enthalpy, and the *opposite* of
 * [Reaction.enthalpyPerKg], where positive is endothermic. [hessEnthalpyKJ] is where the two meet and
 * it is the only place the flip happens.
 *
 * An element in its standard state is **zero by definition**, and that is why this is a map rather
 * than a column on [Species] with a default: a missing entry and a genuine zero are completely
 * different claims, and a default of zero would silently assert that every species nobody had got to
 * yet was an element.
 */
val FORMATION_ENTHALPY: Map<Species, Int> = mapOf(
    // ══ ELEMENTS ══════════════════════════════════════════════════════════════════════════════
    //
    // Zero by definition, every one of them — this is what a formation enthalpy is measured against.
    // Listed explicitly rather than defaulted, so that "absent" keeps meaning "nobody has sourced
    // this yet".
    Species.Iron to 0,
    Species.Carbon to 0, // graphite, the standard state
    Species.Silicon to 0,
    Species.Magnesium to 0,
    Species.Titanium to 0,
    Species.Nitrogen to 0,
    Species.Oxygen to 0,
    Species.Hydrogen to 0,
    // ⛔ Solid, not gas, and knowingly against the rule above — see the class doc.
    Species.Sulfur to 0,

    // ══ OXIDES ════════════════════════════════════════════════════════════════════════════════
    Species.Quartz to -911, // SiO2, alpha-quartz
    Species.Periclase to -602, // MgO
    Species.Lime to -635, // CaO
    Species.Rutile to -944, // TiO2
    Species.Hematite to -824, // Fe2O3
    Species.Magnetite to -1118, // Fe3O4

    // ══ SILICATES ═════════════════════════════════════════════════════════════════════════════
    Species.Forsterite to -2174, // Mg2SiO4
    Species.Enstatite to -1547, // MgSiO3
    Species.Fayalite to -1479, // Fe2SiO4
    Species.Ferrosilite to -1195, // FeSiO3
    Species.Serpentine to -4364, // Mg3Si2O5(OH)4, chrysotile
    Species.Ilmenite to -1237, // FeTiO3

    // ══ CARBONATES AND SULFIDES ═══════════════════════════════════════════════════════════════
    Species.Calcite to -1207, // CaCO3
    Species.Magnesite to -1113, // MgCO3
    Species.Pyrite to -178, // FeS2
    Species.Troilite to -100, // FeS

    // ══ GASES ═════════════════════════════════════════════════════════════════════════════════
    //
    // All quoted as gases, per the rule, and all of them far above their critical points at any
    // temperature that makes them.
    Species.CarbonDioxide to -394,
    Species.CarbonMonoxide to -111,
    Species.Water to -242, // ⚠️ GAS. -286 is the liquid, and using it would break every fire.
    Species.Methane to -75,
    Species.Ammonia to -46,
    Species.HydrogenSulfide to -20,
    Species.SulfurDioxide to -297,

    // ══ ORGANICS ══════════════════════════════════════════════════════════════════════════════
    Species.Algae to -1271, // glucose, C6H12O6, solid

    // ══ THE TWO THE VESSEL MAKES OUT OF ITSELF ════════════════════════════════════════════════
    //
    // ⚠️ **These two are defined rather than measured, and the definition is a claim already made
    // elsewhere in this codebase**: `REACTIONS` says of both steel and firebrick that "forming a
    // solid solution or a two-phase ceramic from its ingredients releases essentially nothing", and
    // that the energy a foundry spends is spent getting the charge to temperature. Stating each as
    // the sum of what it is made of is exactly that sentence, written where the arithmetic can read
    // it — and it keeps both rows at the zero they were hand-written to have.
    //
    // ⛔ They are the only two entries here that a textbook cannot check, because neither is a real
    // substance. Everything else must be sourced.
    Species.Steel to 0, // Fe99C, from 99 Fe + C, both elements at zero
    Species.Firebrick to -12088, // (MgO)11(SiO2)6, from 11*(-602) + 6*(-911)
)

/**
 * [FORMATION_ENTHALPY] by [Species] ordinal — the table [hessEnthalpyKJ] actually reads.
 *
 * An array for the reason [CRITICAL_OF] and `DECOMPOSITION_OF` are arrays, though the pressure is
 * milder here: this is read while [REACTIONS] is being constructed, once, rather than inside a tick.
 * It is an array anyway so that nobody has to work out which kind of table this is.
 */
private val FORMATION_OF: Array<Int?> = arrayOfNulls<Int>(Species.COUNT).also {
    for ((species, kJ) in FORMATION_ENTHALPY) it[species.ordinal] = kJ
}

/** [FORMATION_ENTHALPY] for [species], or null if nobody has sourced one yet. */
fun formationEnthalpyOf(species: Species): Int? = FORMATION_OF[species.ordinal]

/**
 * The enthalpy of a reaction **as it is written**, in kJ, or null if any participant is unsourced.
 *
 * Hess's law: the enthalpy of a reaction is the formation enthalpies of what comes out less the
 * formation enthalpies of what goes in, and it does not care by what route. That is the whole of the
 * arithmetic, and it is the reason this file replaces twenty-four hand-typed numbers with one
 * expression.
 *
 * ⚠️ **The sign flips here and only here.** A formation enthalpy is negative when energy is released;
 * [Reaction.enthalpyPerKg] is *positive* when the reaction is endothermic, which is this package's
 * convention everywhere else. `products − reagents` already produces the latter — a reaction that
 * makes strongly-bound products out of weakly-bound ones comes out negative, and negative is
 * exothermic on both conventions — so no negation is written. This paragraph exists because that
 * coincidence is worth stating rather than rediscovering.
 *
 * ⛔ **Null is not zero.** A row with an unsourced participant has an *unknown* enthalpy, and
 * defaulting it to zero would make that row athermal — a fire that does not warm the room, which is
 * precisely the failure `perKilogram` documents and the hardest kind to notice. Callers must decide
 * what to do about null; [Reaction] refuses to be constructed.
 */
fun hessEnthalpyKJ(
    reagents: List<Pair<Species, Int>>,
    products: List<Pair<Species, Int>>,
): Int? {
    var total = 0
    for ((species, units) in products) total += (formationEnthalpyOf(species) ?: return null) * units
    for ((species, units) in reagents) total -= (formationEnthalpyOf(species) ?: return null) * units
    return total
}
