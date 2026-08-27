package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * Matter coming apart because of how hot it is — increment 4 of `PLAN_ambient_chemistry.md`, and the
 * point at which chemistry stops being one reaction and becomes a **table**.
 *
 * A carbonate gives up its CO₂, a hydrate gives up its water, a sulfide gives up its sulfur. No
 * reagent, nothing to compete for, nothing to allocate: heat alone, which is why these are the
 * tier-1 refining reactions and why the thermal decomposer is a box with a temperature dial and no
 * recipe. Everything here would happen just as readily on a belt that got too hot, and does.
 *
 * ### The formula is what is written down
 *
 * A row states **formula units** — `6 Fe₂O₃ → 4 Fe₃O₄ + O₂` is `reactantUnits = 6` and products of
 * `Magnetite to 4, Oxygen to 1` — and every mass in the arithmetic is derived from those counts and
 * the molar masses [Species] already holds.
 *
 * ⚠️ **Never a hand-written mass fraction.** That would be a second source of truth for a number the
 * species table already answers, with no oracle to catch it: "calcite yields lime" would still be
 * true and it would yield the wrong amount of it, for ever, silently. This is `MineralTest`'s
 * argument about mineral formulae applied to reactions, and `DecompositionTest` closes every row
 * atom by atom against [MINERALS] for exactly that reason.
 *
 * ⚠️ **Conservation is structural**, as everywhere else in `chem`: the products are shares of the
 * reactant's own mass handed out by [apportion], whose sum is the total *by construction*. No
 * arithmetic path can invent or lose a gram, and no row can be written that yields more than it
 * consumed even if its formula is nonsense — the atom-closure test is what catches that.
 *
 * ### Where the products go is not a field
 *
 * A product that is a [Fluid] joins the tile's air; anything else stays where the reactant was. So
 * calcite's CO₂ leaves and its lime does not, pyrite's sulfur leaves as vapour and its troilite does
 * not, and no row had to say so. Same rule as [Oxidation], same single place for the phase of a
 * species to be recorded.
 */
class Decomposition(
    val reactant: Species,
    val reactantUnits: Int,
    val products: List<Pair<Species, Int>>,
    val onsetKelvin: Int,
    /** Positive is **endothermic** — energy the reaction takes out of the matter to happen. */
    val enthalpyPerKg: Long,
    val baseRate: Long = DECOMPOSITION_BASE_RATE,
) {
    /**
     * The mass of each of [products], in order, from [reactantMass] of the reactant.
     *
     * Shares of the reactant's own mass, weighted by the mass each product's formula units account
     * for. [apportion] makes them sum to exactly [reactantMass], so the reaction closes whatever the
     * rounding does — the same telescoping construction every other split in `chem` uses.
     */
    fun split(reactantMass: Long): LongArray = apportion(weights, reactantMass)

    /**
     * How much of [reactantMass] comes apart in one pass at [kelvin], and nothing below the onset.
     *
     * The same Arrhenius climb in reduced temperature that [Oxidation] uses, because it is the same
     * physics and the table of it is shared — see [rateMultiplier].
     */
    fun decomposed(reactantMass: Long, kelvin: Int): Long {
        if (reactantMass <= 0L || kelvin < onsetKelvin) return 0L
        return scaledRatio(reactionFraction(kelvin, onsetKelvin, baseRate), SCALE, reactantMass)
    }

    /**
     * The energy [mass] of this reactant takes out of the matter to come apart — negative if it puts
     * energy in instead.
     *
     * ⚠️ **This is the term increment 1 deliberately did not invent.** A reaction there carried the
     * heat its matter already had and released none of its own, because deciding where the heat went
     * for one reaction and then deciding it again for the table is two answers to one question. The
     * table is here, so this is the answer.
     */
    fun enthalpy(mass: Long): Long = perKilogram(mass, enthalpyPerKg)

    /** Mass each product's formula units account for — the weights [split] apportions by. */
    private val weights: LongArray =
        LongArray(products.size) { products[it].second.toLong() * products[it].first.molarMass }
}

/**
 * Every thermal decomposition in the game, and the whole of tier-1 refining.
 *
 * ⚠️ **Order is for reproducibility, not for priority.** Nothing here competes for anything — that
 * is what "no reagent, just heat" means — so a tile holding two reactants runs both, in full, and
 * which came first changes only the rounding. It must stay fixed for the simulation to be
 * deterministic and it means nothing else.
 *
 * ⚠️ **The onsets are real temperatures**, near enough: calcite calcines around 900 °C, magnesite
 * near 540 °C, serpentine gives up its hydroxyl water around 600 °C. They are what make the setpoint
 * on a decomposer a *decision* — a temperature that cracks magnesite leaves calcite alone, so a
 * mixed feed separates by heat rather than by a filter somebody wrote.
 */
val DECOMPOSITIONS: List<Decomposition> = listOf(
    // ── The carbonates: calcining, and the reason Lime and Periclase exist ──
    //
    // CaCO₃ → CaO + CO₂. The marquee tier-1 reaction, named in `ThermalDecomposer`'s own
    // documentation for as long as it has been impossible.
    Decomposition(
        reactant = Species.Calcite, reactantUnits = 1,
        products = listOf(Species.Lime to 1, Species.CarbonDioxide to 1),
        onsetKelvin = 1170,
        enthalpyPerKg = 178L * kJPerMolAt(100),
    ),
    // MgCO₃ → MgO + CO₂, and at a much lower temperature than calcite — which is what makes a
    // dolomitic feed separable by setpoint alone.
    Decomposition(
        reactant = Species.Magnesite, reactantUnits = 1,
        products = listOf(Species.Periclase to 1, Species.CarbonDioxide to 1),
        onsetKelvin = 810,
        enthalpyPerKg = 118L * kJPerMolAt(84),
    ),

    // ── The hydrate: the difference between a wet rock and a dry one ──
    //
    // Mg₃Si₂O₅(OH)₄ → Mg₂SiO₄ + MgSiO₃ + 2 H₂O. Serpentine is 13% water by mass and this is how a
    // vessel gets it out — the marquee reason an outer-system rock is worth hauling.
    Decomposition(
        reactant = Species.Serpentine, reactantUnits = 1,
        products = listOf(Species.Forsterite to 1, Species.Enstatite to 1, Species.Water to 2),
        onsetKelvin = 900,
        enthalpyPerKg = 250L * kJPerMolAt(276),
    ),

    // ── The sulfide: sulfur as a vapour, which is why `Fluid` has it ──
    //
    // FeS₂ → FeS + S. The sulfur leaves as vapour and has to go somewhere, which is the first time
    // a refining step makes the room a problem rather than a backdrop.
    Decomposition(
        reactant = Species.Pyrite, reactantUnits = 1,
        products = listOf(Species.Troilite to 1, Species.Sulfur to 1),
        onsetKelvin = 1000,
        enthalpyPerKg = 40L * kJPerMolAt(120),
    ),

    // ── The oxide: iron ore giving up oxygen on heat alone ──
    //
    // 6 Fe₂O₃ → 4 Fe₃O₄ + O₂, and it takes a serious temperature. Note what it does to a room: it
    // *makes* oxygen, so a hot bed of hematite is a slow air supply and also a reason for anything
    // carbon nearby to catch.
    Decomposition(
        reactant = Species.Hematite, reactantUnits = 6,
        products = listOf(Species.Magnetite to 4, Species.Oxygen to 1),
        onsetKelvin = 1730,
        enthalpyPerKg = 472L * kJPerMolAt(960),
    ),

    // ── The ices, cracked ──
    //
    // ⛔ **Ammonia cracking has moved to [REACTIONS]** (`PLAN_unified_reactions.md`, increment 1).
    // It was here, at 1100 K, and ammonia is evicted from a cargo layer above its critical point of
    // 405 K — so this table swept it over a store it could not be in, and it never fired outside a
    // sealed tile. It is now swept over the fluid field, where the ammonia actually is.
    //
    // ⚠️ **CH₄ → C + 2 H₂ has the same bug and is still here**, because the fix is not the same: its
    // carbon is not something the atmosphere can hold, so moving it needs the fluid field widened
    // first. Parked deliberately — see the plan's decision 4, and `ReactionReachabilityTest`, which
    // pins it as known-dead rather than letting it read as a route the player can plan around.
    Decomposition(
        reactant = Species.Methane, reactantUnits = 1,
        products = listOf(Species.Carbon to 1, Species.Hydrogen to 2),
        onsetKelvin = 1300,
        enthalpyPerKg = 75L * kJPerMolAt(16),
    ),

    // ── The bio-matter: pyrolysis and thermal death ──
    //
    // C₆H₁₂O₆ → CH₄ + CO₂ + 4 H₂O + 4 C. Cooking or pyrolyzing algae cracks it into
    // volatile gases, water vapour, and a solid char residue. This allows players to use
    // dead crops as a complex fuel/refining source, or accidentally ruin their life-support
    // tanks by letting them overheat.
    Decomposition(
        reactant = Species.Algae, reactantUnits = 1,
        products = listOf(
            Species.Methane to 1,
            Species.CarbonDioxide to 1,
            Species.Water to 4,
            Species.Carbon to 4
        ),
        // Algae dies and cooks well below mineral cracking points.
        onsetKelvin = 353, // ~80°C.
        // +65 kJ/mol of glucose. Mildly endothermic; baking or charring biomass draws
        // a small amount of heat out of the tile. Molar mass is 180g/mol.
        enthalpyPerKg = 65L * kJPerMolAt(180),
    ),
)

/**
 * [DECOMPOSITIONS] by [Species] ordinal, for the sweep.
 *
 * An array rather than a `Map` for the reason `CRITICAL` and `MINERALS` get away with being maps and
 * this does not: those are queried per species present at setup, and this is read inside a tick loop
 * over every occupied tile of every layer.
 */
val DECOMPOSITION_OF: Array<Decomposition?> = arrayOfNulls<Decomposition>(Species.COUNT).also {
    for (d in DECOMPOSITIONS) it[d.reactant.ordinal] = d
}

/**
 * The coldest temperature at which anything in [DECOMPOSITIONS] happens at all.
 *
 * Derived rather than written down: a row added below a hand-written constant would be a reaction
 * that silently never ran.
 */
val LOWEST_DECOMPOSITION_ONSET: Int = DECOMPOSITIONS.minOf { it.onsetKelvin }

/**
 * The share of a reactant that comes apart in one pass **at exactly its onset** — the slowest any of
 * these ever goes.
 *
 * The twin of [BASE_RATE], and deliberately the same number: it stands in for exposed surface area
 * (see `Reaction.kt`), which is a property of how finely the feed is divided rather than of which
 * reaction is running. A dial, and expected to move.
 */
const val DECOMPOSITION_BASE_RATE: Long = SCALE / 400L
