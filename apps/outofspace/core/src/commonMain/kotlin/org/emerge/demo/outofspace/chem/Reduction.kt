package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * An oxide giving up its oxygen to something that wants it more — increment 5 of
 * `PLAN_ambient_chemistry.md`, and the **third reaction shape** rather than a row of either table.
 *
 * [Decomposition] is heat and nothing else, so nothing can compete for anything. [Oxidation] has one
 * reagent and it comes from the air, which is a field the whole tile shares. This has a reagent too
 * and it is a **solid sitting in the same layer as the oxide** — carbon in the charge, silicon in the
 * charge, magnesium in the charge — and that one difference is why it could not be a row:
 *
 *  - **The reagent is consumed from the layer, not from the atmosphere**, so it is finite in a way a
 *    room's oxygen is not. A charge that runs out of carbon stops, and no amount of ventilation
 *    helps it.
 *  - **Contention is per reagent species, not per tile.** Two oxidations always compete, because
 *    there is only ever one oxygen. Two reductions compete only if they want the *same* reductant —
 *    quartz and ilmenite are both after the carbon and neither of them cares how much magnesium is
 *    in the hopper. See `AmbientChemistry.kt`, which is where that distinction is spent.
 *  - **The player mixes the reagent in.** An oxidation's reagent is a property of the room; this one
 *    arrives on a belt, in a ratio somebody chose, which is what makes a reduction a *recipe* in
 *    everything but name while still being ambient chemistry that would happen just as readily on a
 *    belt that got too hot.
 *
 * ### Why this is what unlocks titanium
 *
 * Every metal the vessel is built from that does not occur native has to be *taken* from an oxide,
 * and heat alone will not do it — that is the whole content of [Decomposition] stopping at magnetite.
 * Reduction is the step that gets from a rock to a metal, and the four rows in [REDUCTIONS] are a
 * real industrial chain rather than four inventions: silicon out of sand, magnesium out of magnesia
 * with that silicon, synthetic rutile out of ilmenite, and titanium out of that rutile with that
 * magnesium.
 *
 * ⚠️ **Carbon will not reduce titania**, which is the constraint that shaped the chain. `TiO₂ + C`
 * gives titanium *carbide*, and a row saying otherwise would be exactly the hand-written fiction
 * [DECOMPOSITIONS] refuses to contain — plausible, unfalsifiable, and wrong for ever. The real route
 * needs a reductant stronger than carbon, so the chain has to go and *make* one first. That is not a
 * gameplay contrivance; it is why the Kroll process exists.
 *
 * ### Vacuum is not a rule here either
 *
 * Nothing in this file mentions oxygen and nothing needs to. Carbon, silicon and magnesium are all
 * things that burn, [OXIDATIONS] already says so, and the ambient pass runs both tables over the same
 * tile — so a reduction attempted in an airy room loses its reductant to the air before it can spend
 * it on the oxide. "Reduction wants a vacuum" is an outcome of two tables meeting, in the same way
 * "the oxygen attacks the carbon first" is an outcome of two base rates meeting, and `Reaction.kt`
 * predicted this one by name before there was anything here to predict.
 */
class Reduction(
    /** The oxide being stripped. The rate is a fraction of *this*, and so is [enthalpyPerKg]. */
    val oxide: Species,
    val oxideUnits: Int,
    /** The solid that takes the oxygen. Finite, contended, and mixed in by the player. */
    val reductant: Species,
    val reductantUnits: Int,
    val products: List<Pair<Species, Int>>,
    val onsetKelvin: Int,
    val catalyst: Species? = null,
    val catalystUnits: Int = 1,
    /** Positive is **endothermic**, as in both other tables. Most of these are; one is not. */
    val enthalpyPerKg: Long,
    val baseRate: Long = REDUCTION_BASE_RATE,
) {
    /** Mass of reductant per mass of oxide, as the exact ratio of formula-unit masses. */
    internal val reductantNumerator: Long = reductantUnits.toLong() * reductant.molarMass
    internal val reductantDenominator: Long = oxideUnits.toLong() * oxide.molarMass
    internal val catalystCapacityDenominator: Long = catalystUnits.toLong() * (catalyst?.molarMass?.toLong() ?: 1L)

    /**
     * The mass of each of [products], in order, from [totalMass] of oxide **and reductant together**.
     *
     * ⚠️ **The whole of both reagents is what gets handed out**, which is the one place this differs
     * from [Decomposition.split] in more than naming. A decomposition redistributes one substance;
     * this combines two, and the products account for every atom of both — the carbon that left the
     * charge is in the CO, the silicon that left it is in the slag. Apportioning only the oxide would
     * lose the reductant's mass silently, and the ledger would read it as matter that never existed.
     *
     * [apportion] makes the shares sum to exactly [totalMass], so the row closes whatever the
     * rounding does, exactly as everywhere else in `chem`.
     */
    fun split(totalMass: Long): LongArray = apportion(weights, totalMass)

    /**
     * The energy [mass] of **oxide** takes out of the matter to be reduced — negative if it puts
     * energy in instead.
     *
     * ⚠️ **One of these rows is exothermic and that is not a typo.** Magnesium is a violent enough
     * reductant that taking titania apart with it releases energy rather than costing it, which is
     * precisely why it works where carbon does not. So unlike [DECOMPOSITIONS], this table's sign
     * test cannot simply assert "positive" — see `ReductionTest`, which checks each row against its
     * own stated direction instead.
     */
    fun enthalpy(mass: Long): Long = perKilogram(mass, enthalpyPerKg)

    /**
     * How much **reductant** this reaction wants at [kelvin] with [oxideMass] and [catalystMass] present — what it would
     * take if nothing else in the tile were after the same species.
     *
     * Half of the Jacobi rule, and the same half [Oxidation.demand] is: asked of every row against
     * one snapshot, before anything has been taken, so no row's answer depends on when it was asked.
     */
    fun demand(oxideMass: Long, catalystMass: Long, kelvin: Int): Long {
        if (oxideMass <= 0L || kelvin < onsetKelvin) return 0L
        if (catalyst != null && catalystMass == 0L) return 0L

        val fraction = reactionFraction(kelvin, onsetKelvin, baseRate)
        var consumed = scaledRatio(fraction, SCALE, oxideMass)
        if (consumed <= 0L) return 0L

        if (catalyst != null) {
            // Max oxide mass this much catalyst can process in one tick
            val maxMassByCatalyst = scaledRatio(reductantDenominator, catalystCapacityDenominator, catalystMass)
            consumed = minOf(consumed, maxMassByCatalyst)
            if (consumed <= 0L) return 0L
        }

        return scaledRatio(reductantNumerator, reductantDenominator, consumed)
    }

    /**
     * What one pass consumes, given the oxide present, how hot it is, and **how much reductant this
     * row is allowed** — which in a contended tile is less than [demand] asked for.
     *
     * The starvation path is [Oxidation.react]'s, down to the double flooring: when the allowance
     * binds, the oxide is re-derived from the reductant and then the reductant re-derived from *that*
     * oxide, so the pair sits exactly on the stoichiometric line and no reagent is taken for matter
     * that did not react. A reduction that ran rich would break the atom balance in the direction
     * where it still looks like it is working.
     */
    fun react(oxideMass: Long, reductantAllowed: Long, kelvin: Int): Reduced {
        if (oxideMass <= 0L || reductantAllowed <= 0L || kelvin < onsetKelvin) return NOTHING

        val fraction = reactionFraction(kelvin, onsetKelvin, baseRate)
        var consumed = scaledRatio(fraction, SCALE, oxideMass)
        if (consumed <= 0L) return NOTHING

        var reagent = scaledRatio(reductantNumerator, reductantDenominator, consumed)

        if (reagent > reductantAllowed) {
            reagent = reductantAllowed
            consumed = scaledRatio(reductantDenominator, reductantNumerator, reagent)
            if (consumed <= 0L) return NOTHING
            reagent = scaledRatio(reductantNumerator, reductantDenominator, consumed)
            if (reagent <= 0L) return NOTHING
        }

        return Reduced(consumed, reagent)
    }

    /** Mass each product's formula units account for — the weights [split] apportions by. */
    private val weights: LongArray =
        LongArray(products.size) { products[it].second.toLong() * products[it].first.molarMass }

    companion object {
        private val NOTHING = Reduced(0L, 0L)
    }
}

/**
 * What one pass of a [Reduction] consumed at one place.
 *
 * Two masses and a derived third, which is [Reacted]'s construction and its reason: [total] is what
 * the two reagents weighed between them rather than a separately computed quantity, so the mass
 * handed to [Reduction.split] is the mass that actually left the layer and no arithmetic path can
 * put the two out of step.
 */
class Reduced(val oxide: Long, val reductant: Long) {
    /** ⚠️ Derived, never stated: everything that went in, and so everything that comes out. */
    val total: Long get() = oxide + reductant

    val isNothing: Boolean get() = oxide <= 0L || reductant <= 0L
}

/**
 * Every reduction in the game, and the road from a rock to a metal the vessel can be built out of.
 *
 * Read as a chain rather than a list — each row's product is the next row's reagent, and the last two
 * hand each other's by-products back:
 *
 * ```
 *   Quartz    + carbon    ─→ Silicon                     (and CO)
 *   Periclase + silicon   ─→ Magnesium                   (and quartz back)
 *   Ilmenite  + carbon    ─→ synthetic rutile, and iron  (and CO)
 *   Rutile    + magnesium ─→ TITANIUM                    (and periclase back)
 * ```
 *
 * ⚠️ **The loop is the point.** Periclase reduced to magnesium comes back as periclase when the
 * magnesium spends itself on the titania, and the quartz does the same one row up. Neither is
 * consumed on balance — they *circulate*, and what the chain actually eats is carbon and heat. That
 * is a genuinely different thing to build than a straight line of machines, and it is what the real
 * processes do.
 *
 * ⚠️ **The onsets are real temperatures**, near enough, and they are far apart on purpose: silicon
 * carbothermy needs a furnace hotter than anything else in the game, while magnesiothermic titanium
 * is comparatively mild. A single setpoint cannot run this chain, so it is four settings and four
 * decisions rather than one machine somebody leaves on.
 *
 * ⚠️ Order is for reproducibility, not priority — [OXIDATIONS]' rule and for [OXIDATIONS]' reason.
 * Contention is settled by demand before anything is taken, so which row comes first fixes only the
 * rounding, and it must stay fixed for the simulation to be deterministic.
 */
val REDUCTIONS: List<Reduction> = listOf(
    // ── Silicon out of sand: the bottom of the chain, and the hottest thing in the game ──
    //
    // SiO2 + 2 C -> Si + 2 CO. How silicon metal is actually made, in a submerged-arc furnace, at a
    // temperature that is a problem in itself. Quartz is native and abundant, so this is the row
    // that needs no other row to have run first.
    Reduction(
        oxide = Species.Quartz, oxideUnits = 1,
        reductant = Species.Carbon, reductantUnits = 2,
        products = listOf(Species.Silicon to 1, Species.CarbonMonoxide to 2),
        onsetKelvin = 2000,
        enthalpyPerKg = 690L * kJPerMolAt(60),
    ),

    // ── Periclase out of magnesia: the Pidgeon process, and the reason the chain needs a vacuum ──
    //
    // 2 MgO + Si -> 2 Mg + SiO2. Real, and really done under vacuum — the magnesium comes off as a
    // vapour and is condensed. Here it stays a solid, because no metal in this game melts or boils
    // and inventing a phase for this one would be a rule that applies to nothing else.
    //
    // Note what it gives back: the quartz returns, so the silicon is the only thing spent.
    Reduction(
        oxide = Species.Periclase, oxideUnits = 2,
        reductant = Species.Silicon, reductantUnits = 1,
        products = listOf(Species.Magnesium to 2, Species.Quartz to 1),
        onsetKelvin = 1500,
        enthalpyPerKg = 293L * kJPerMolAt(80),
    ),

    // ── Forsterite cracking: the high-temp loop that avoids shock-cooling ──
    //
    // Mg2SiO4 + 4 C -> [2 Mg + Si + 4 CO] -> 2 MgO + 2 C + Si + 2 CO. Driven at extreme heat to
    // force carbothermic reduction, but then allowed to revert on slow cooling. The un-reverted
    // carbon monoxide is vented as a gas, leaving behind an intimate solid mixture of magnesia,
    // silicon metal, and carbon soot.
    Reduction(
        oxide = Species.Forsterite, oxideUnits = 1,
        reductant = Species.Carbon, reductantUnits = 4,
        products = listOf(Species.Periclase to 2, Species.Silicon to 1, Species.Carbon to 2, Species.CarbonMonoxide to 2),
        onsetKelvin = 1800,
        enthalpyPerKg = 750L * kJPerMolAt(140),
    ),

    /**
     * `C + CO₂ → 2 CO` — The Boudouard reaction.
     * Solid carbon reduces carbon dioxide gas into flammable carbon monoxide.
     * Strongly endothermic (+172.4 kJ/mol of carbon), acting as a natural thermal brake
     * in high-temperature environments.
     */
    Reduction(
        oxide = Species.CarbonDioxide, oxideUnits = 1,
        reductant = Species.Carbon, reductantUnits = 1,
        products = listOf(Species.CarbonMonoxide to 2),
        // Becomes thermodynamically favorable around 973 Kelvin (700°C),
        // and completely dominates above 1200 Kelvin.
        onsetKelvin = 973,
        enthalpyPerKg = 172L * kJPerMolAt(44),
    ),

    /**
     * `100 C6H12O6 + 6 H₂O + 6 CO₂ → 101 C6H12O6 + 6 O₂` — photosynthesis, written as a reduction
     * with the algae as its own catalyst: a hundred units of it have to be present for one more to
     * be made, which is what makes a bloom grow in proportion to itself rather than at a flat rate.
     *
     * Net of the catalyst it is the textbook reaction, `6 H₂O + 6 CO₂ → C6H12O6 + 6 O₂`, and its
     * enthalpy is the textbook one: **+2803 kJ per mole of glucose formed**, which is per mole of
     * *reaction* and so per the 6 units of water this row is quoted against.
     *
     * ⚠️ **Quoted against its own formula mass, 6 × 18 = 108 g/mol, not against one water.** Written
     * as `kJPerMolAt(18)` the divisor was six times too small, so the row claimed six times the
     * energy it should and `everyEnthalpyIsQuotedAgainstItsOwnOxideFormulaMass` could not divide it
     * back into whole kJ/mol. Every other row in this table is quoted against `oxideUnits ×
     * molarMass`, and the test exists precisely because the one number a reader cannot check by
     * eye is which unit an enthalpy is per.
     */
    Reduction(
        oxide = Species.Water, oxideUnits = 6,
        reductant = Species.CarbonDioxide, reductantUnits = 6,
        catalyst = Species.Algae, catalystUnits = 100,
        products = listOf(Species.Algae to 1, Species.Oxygen to 6),
        onsetKelvin = 273, // ~0°C.
        enthalpyPerKg = 2803L * kJPerMolAt(108),
    ),

    // ── Enstatite cracking: the high-silicon alternative ──
    //
    // MgSiO3 + 3 C -> [Mg + Si + 3 CO] -> MgO + C + Si + 2 CO. Pyroxene processing at high heat.
    // Because enstatite contains far more silica than forsterite, its slow-cooled reversion yields a
    // massive structural surplus of silicon metal.
    //
    // This feeds one unit of periclase into the loop, while venting a cleaner ratio of carbon monoxide.
    Reduction(
        oxide = Species.Enstatite, oxideUnits = 1,
        reductant = Species.Carbon, reductantUnits = 3,
        products = listOf(Species.Periclase to 1, Species.Silicon to 1, Species.Carbon to 1, Species.CarbonMonoxide to 2),
        onsetKelvin = 1800,
        enthalpyPerKg = 890L * kJPerMolAt(100),
    ),

    // ── Fayalite cracking: the iron-olivine low-temp shortcut ──
    //
    // Fe2SiO4 + 2 C -> 2 Fe + Si + 2 CO2. Because iron holds oxygen loosely compared to magnesium,
    // this olivine variant cracks at drastically lower temperatures. Iron drops out safely as a solid
    // metal without any gaseous reversion madness, while leaving behind a clean unit of silicon metal.
    Reduction(
        oxide = Species.Fayalite, oxideUnits = 1,
        reductant = Species.Carbon, reductantUnits = 2,
        products = listOf(Species.Iron to 2, Species.Silicon to 1, Species.CarbonDioxide to 2),
        onsetKelvin = 1250,
        enthalpyPerKg = 210L * kJPerMolAt(204),
    ),

    // ── Ferrosilite cracking: the heavy iron-silicon pyroxene ──
    //
    // 2 FeSiO3 + 3 C -> 2 Fe + 2 Si + 3 CO2. The iron twin to enstatite. Just like its magnesium counterpart,
    // its 1:1 mineral structure guarantees a massive structural surplus of silicon metal relative
    // to the iron produced, but unlocks at mid-tier furnace temperatures.
    //
    // ⚠️ **The only row that consumes two units of its oxide, so the only one where the formula mass
    // and the molar mass are different numbers.** 240 kJ per FeSiO3 is 480 kJ per pass of this
    // reaction, against 2 x 132 g/mol -- the same energy per kilogram either way, but quoted the way
    // every other row is and the way `everyEnthalpyIsQuotedAgainstItsOwnOxideFormulaMass` reads it.
    Reduction(
        oxide = Species.Ferrosilite, oxideUnits = 2,
        reductant = Species.Carbon, reductantUnits = 3,
        products = listOf(Species.Iron to 2, Species.Silicon to 2, Species.CarbonDioxide to 3),
        onsetKelvin = 1200,
        enthalpyPerKg = 480L * kJPerMolAt(264),
    ),

    // ── Synthetic rutile: the Becher process, and where the iron comes out ──
    //
    // FeTiO3 + C -> Fe + TiO2 + CO. Carbon takes the *iron's* oxygen and leaves the titanium's alone,
    // which is the whole trick — carbon cannot touch titania and does not have to. Two useful solids
    // out of one common rock, and ilmenite is six times commoner than rutile.
    Reduction(
        oxide = Species.Ilmenite, oxideUnits = 1,
        reductant = Species.Carbon, reductantUnits = 1,
        products = listOf(Species.Iron to 1, Species.Rutile to 1, Species.CarbonMonoxide to 1),
        onsetKelvin = 1200,
        enthalpyPerKg = 180L * kJPerMolAt(152),
    ),

    // ── Titanium at last, and the one row that gives energy back ──
    //
    // TiO2 + 2 Mg -> Ti + 2 MgO. Magnesiothermic reduction, and **exothermic** — magnesium wants
    // oxygen badly enough that this pays for itself once it is lit, which is exactly the property
    // that makes magnesium the reductant and carbon not. The periclase returns to feed row two.
    Reduction(
        oxide = Species.Rutile, oxideUnits = 1,
        reductant = Species.Magnesium, reductantUnits = 2,
        products = listOf(Species.Titanium to 1, Species.Periclase to 2),
        onsetKelvin = 1100,
        enthalpyPerKg = -259L * kJPerMolAt(80),
    ),
)

/**
 * [REDUCTIONS] grouped by the species they compete for — what the sweep iterates.
 *
 * ⚠️ **This grouping is the whole difference between this table and [OXIDATIONS].** A tile has one
 * oxygen supply and every oxidation drinks from it, so that contention is a single apportionment. A
 * tile may hold carbon *and* silicon *and* magnesium, and the rows after the carbon have no claim on
 * the silicon whatever — apportioning all of them against one pooled number would starve rows that
 * were never in competition, and would do it in a way that changed when an unrelated row was added.
 *
 * Built from [REDUCTIONS] rather than written beside it, so a new row joins its own group by saying
 * what it eats and there is no second list to keep in step.
 */
val REDUCTION_GROUPS: List<ReductionGroup> =
    REDUCTIONS.groupBy { it.reductant }.map { (reductant, rows) -> ReductionGroup(reductant, rows) }

/** One contended reagent and every [Reduction] that wants it. */
class ReductionGroup(val reductant: Species, val rows: List<Reduction>)

/**
 * The largest number of rows after any one reagent — how wide the sweep's scratch has to be.
 *
 * Lets `oxidise` size its arrays once for the whole table rather than per group, which is the same
 * argument as the arrays already hoisted there: a few longs at every occupied tile of every layer
 * every pass is a shape of cost that only ever reads as "the chemistry is slow".
 *
 * ⚠️ **Counted off [REDUCTIONS], not off [REDUCTION_GROUPS], and it has to stay that way.** As a
 * `companion object` field on [ReductionGroup] reading the grouped list, this deadlocked the class
 * loader: building [REDUCTION_GROUPS] constructs a [ReductionGroup], which initialises its companion,
 * which reads the list still being built. The same count off the ungrouped table has no cycle, and
 * `groupBy` is the only thing between the two.
 */
val WIDEST_REDUCTION_GROUP: Int =
    REDUCTIONS.groupBy { it.reductant }.maxOfOrNull { it.value.size } ?: 0

/**
 * The coldest temperature at which anything in [REDUCTIONS] happens at all.
 *
 * Derived rather than written down, for [LOWEST_DECOMPOSITION_ONSET]'s reason: a row added below a
 * hand-written constant would be a reaction that silently never ran.
 */
val LOWEST_REDUCTION_ONSET: Int = REDUCTIONS.minOf { it.onsetKelvin }

/**
 * The share of the oxide that is reduced in one pass **at exactly the onset** — the slowest any of
 * these ever goes.
 *
 * The third of the three dials, and deliberately the same number as the other two: it stands in for
 * exposed surface area, which is a property of how finely the feed is ground rather than of which
 * reaction is running. Expected to move, and expected to move for all three at once.
 */
const val REDUCTION_BASE_RATE: Long = SCALE / 400L
