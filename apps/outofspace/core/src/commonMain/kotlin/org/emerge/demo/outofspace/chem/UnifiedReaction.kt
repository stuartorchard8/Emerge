package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * A reaction that does not claim to know where the matter is — increments 1 and 4 of
 * `PLAN_unified_reactions.md`, and the shape the other four collapse into.
 *
 * [Decomposition], [Oxidation], [Reduction] and [Combustion] each state, by which table they live
 * in, which **store** their matter is kept in: the first three are swept over the cargo layers and
 * the fourth over the fluid field. Nothing checks that claim, and for a fluid it is usually wrong —
 * `offGas` runs over the cargo layers on the same pass and empties them of anything the tile wants
 * as a gas. Three of twenty-two rows had onsets hundreds of kelvin above the point where their own
 * reactant is evicted, and so could only ever fire inside a sealed tile. See
 * `ReactionReachabilityTest`, which is the audit that found them.
 *
 * This type has no store. It states what reacts, what it becomes, how hot, how fast and at what
 * energy; the *pass* asks where the matter actually is.
 *
 * ### Placement: the principal's store, and nothing else
 *
 * [principal] is the reactant the rate is a fraction of and the enthalpy is quoted against — a
 * thing every one of the four tables already nominates, under four names ([Decomposition.reactant],
 * [Oxidation.reactant], [Reduction.oxide], [Combustion.fuel]). **Products go wherever the principal
 * came from.**
 *
 * That one rule reproduces all four current behaviours exactly: a calcining mineral is in the cargo
 * layer so its lime and its CO₂ land there, and a burning gas is in the air so its exhaust lands
 * there. It also keeps the property that fixed the sealed-tile bug — ⛔ **a reaction never decides
 * phase.** `offGas` and condensation do, afterwards, where they can see whether there is anywhere
 * for a gas to go. A reaction that vented its own product put 18.45 kg of a live save inside six
 * hull plates; see `SealedTileGasTest`.
 *
 * ⚠️ **A product the principal's store cannot hold is not representable**, and that is deliberate
 * rather than an oversight. `MassIndex(tile, Species.Carbon)` does not compile, so a gas-phase
 * reaction yielding soot has nowhere to put it. The answer when one is wanted is to *widen the
 * fluid field* so the atmosphere can hold that species — not a third store, and not a rule against
 * the row. It is parked until a row worth having needs it (plan, decision 4), and
 * `ReactionReachabilityTest` fails any row that would need it in the meantime.
 *
 * ### Reagents come from wherever they are
 *
 * A reagent is drawn from **every store that holds it**, pooled. That is what makes the Boudouard
 * reaction expressible: `CO₂ + C → 2 CO` has its principal in the room's air and its carbon on a
 * belt, and until increment 4 there was no shape that could say so — it sat in [REDUCTIONS], which
 * draws both reagents from one cargo layer, so it wanted CO₂ *as cargo* at 973 K and CO₂ is evicted
 * from a cargo layer above 304 K.
 *
 * ⚠️ **The principal is a reagent like any other and is contended like any other.** It is not
 * special in what it costs — only in what it decides: the temperature the rate is read at, and where
 * the products land.
 *
 * ### What is not here yet
 *
 * ⚠️ **[baseRate] is still stated rather than derived.** A solid burns at its surface and a gas
 * burns throughout, which is why `COMBUSTION_BASE_RATE` is eight times [BASE_RATE] — a fact about
 * the *phase of the principal at this tile*, which this model can now derive and does not. Increment
 * 4f, where it is a rate change and belongs with the pass that computes rates.
 */
class Reaction(
    /** What the rate is a fraction of, what the enthalpy is per kilogram of, and where products go. */
    val principal: Species,
    /** Everything consumed, [principal] included, as formula units. */
    val reagents: List<Pair<Species, Int>>,
    val products: List<Pair<Species, Int>>,
    val onsetKelvin: Int,
    /** Positive is **endothermic**, the sign convention of every table in this package. */
    val enthalpyPerKg: Long,
    val baseRate: Long,
) {
    /** Formula units of [principal], for the mass arithmetic and for what the reference prints. */
    val principalUnits: Int = reagents.first { it.first == principal }.second

    /** Mass of one formula unit's worth of [principal] — the denominator of every ratio here. */
    private val principalMass: Long = principalUnits.toLong() * principal.molarMass

    /** Mass each reagent's formula units account for, in [reagents] order. */
    private val reagentMasses: LongArray =
        LongArray(reagents.size) { reagents[it].second.toLong() * reagents[it].first.molarMass }

    /** Which entry of [reagents] is the principal, so the react path can skip its own ratio. */
    val principalIndex: Int = reagents.indexOfFirst { it.first == principal }

    /**
     * How much of reagent [i] goes with [principalMass] of the principal, on the stoichiometric
     * line.
     *
     * Exact ratios of formula-unit masses, as everywhere else in this package — ⚠️ **never a
     * hand-written mass fraction**, which would be a second source of truth for a number the species
     * table already answers and would be wrong silently.
     */
    fun reagentFor(i: Int, principalMass: Long): Long =
        if (i == principalIndex) principalMass
        else scaledRatio(reagentMasses[i], this.principalMass, principalMass)

    /** The inverse: how much principal [mass] of reagent [i] would support. */
    fun principalFor(i: Int, mass: Long): Long =
        if (i == principalIndex) mass
        else scaledRatio(this.principalMass, reagentMasses[i], mass)

    /**
     * What one pass consumes, given the principal present, how hot it is, and **how much of each
     * reagent this row is allowed** — which in a contended tile is less than it asked for.
     *
     * Writes the mass of each reagent into [out] and returns the principal's own share, or zero if
     * nothing happens.
     *
     * ### The starvation path
     *
     * [Oxidation.react]'s, generalised from one scarce reagent to all of them, and with its double
     * flooring intact. Every reagent is proportional to the principal, so shrinking the principal to
     * whatever the tightest allowance supports and then re-deriving *every* reagent from that
     * shrunken figure puts the whole row exactly on the stoichiometric line.
     *
     * ⛔ **Re-derived, never the allowance itself.** Taking `allowed[i]` directly for a reagent that
     * bound would leave that one reagent over-consumed relative to the rest — a reaction running
     * rich, which breaks the atom balance in the direction where it still looks like it is working.
     *
     * ⚠️ **The shrink is a single left-to-right pass and that is sufficient**, because shrinking for
     * one reagent can only reduce what every other reagent wants. Whichever allowance binds hardest
     * is the one still binding at the end.
     */
    fun react(principalPresent: Long, allowed: LongArray, kelvin: Int, out: LongArray): Long {
        var consumed = consumed(principalPresent, kelvin)
        if (consumed <= 0L) return 0L

        for (i in reagents.indices) {
            val want = reagentFor(i, consumed)
            if (want > allowed[i]) {
                consumed = principalFor(i, allowed[i])
                if (consumed <= 0L) return 0L
            }
        }

        for (i in reagents.indices) {
            val mass = reagentFor(i, consumed)
            // Flooring twice can only shrink, never inflate — but a reagent that floors to nothing
            // is a reaction that would take the others and give nothing back for them.
            if (mass <= 0L) return 0L
            out[i] = mass
        }
        return consumed
    }

    /** Everything [out] holds after a [react], which is the mass the products account for. */
    fun totalConsumed(out: LongArray): Long {
        var sum = 0L
        for (i in reagents.indices) sum += out[i]
        return sum
    }

    /**
     * How much of [mass] reacts in one pass at [kelvin], and nothing below the onset.
     *
     * [Decomposition.decomposed]'s arithmetic, against the same shared Arrhenius climb in reduced
     * temperature — the rate law was already one implementation across all four tables and this
     * does not make it a fifth.
     */
    fun consumed(mass: Long, kelvin: Int): Long {
        if (mass <= 0L || kelvin < onsetKelvin) return 0L
        return scaledRatio(reactionFraction(kelvin, onsetKelvin, baseRate), SCALE, mass)
    }

    /**
     * The mass of each of [products], in order, from [totalMass] of everything consumed.
     *
     * [apportion] makes the shares sum to exactly [totalMass], so the row closes whatever the
     * rounding does — the telescoping construction every other split in this package uses, and the
     * reason no arithmetic path here can invent or lose a gram.
     */
    fun split(totalMass: Long): LongArray = apportion(weights, totalMass)

    /** [split] writing into a caller's array, for a sweep that must not allocate per tile. */
    fun splitInto(totalMass: Long, out: LongArray) = apportionInto(weights, totalMass, out)

    /** The energy [mass] of the **principal** takes to react, or gives back if it is negative. */
    fun enthalpy(mass: Long): Long = perKilogram(mass, enthalpyPerKg)

    private val weights: LongArray =
        LongArray(products.size) { products[it].second.toLong() * products[it].first.molarMass }
}

/**
 * Every reaction that has been moved off the four store-claiming tables, in a fixed order.
 *
 * One row today. It grows as increments 3 and 4 migrate the rest, and when the four tables are
 * empty this is the whole of the game's chemistry.
 *
 * ⚠️ **Order is for reproducibility, not priority** — the rule every other table in this package
 * states, and it will start to mean something once contention arrives in increment 3. For now
 * nothing here competes for anything.
 */
val REACTIONS: List<Reaction> = listOf(
    /**
     * `2 NH₃ → N₂ + 3 H₂` — ammonia cracking, and the first reaction in the game that is swept over
     * the store its matter is actually in.
     *
     * ⚠️ **The awkward case on purpose**, exactly as `CARBON_BURN` was the awkward one for
     * `PLAN_ambient_chemistry.md`. Its principal is a *fluid*, which is the case the old shape got
     * wrong: it sat in [DECOMPOSITIONS] with an onset of 1100 K while ammonia is evicted from a
     * cargo layer above its critical point of 405 K, so it has never fired anywhere but inside a
     * bulkhead, where `offGas` is forbidden to run.
     *
     * It is also **endothermic**, which makes `applyAirEnthalpy`'s clamp a mechanism rather than the
     * guard against a hypothetical future it was written as: this is the first gas reaction that
     * takes energy out of a room instead of putting it in.
     *
     * Methane pyrolysis is the same fix and is *not* here — its carbon is not something the air can
     * hold. See the class doc, and the plan's decision 4.
     */
    Reaction(
        principal = Species.Ammonia,
        reagents = listOf(Species.Ammonia to 2),
        products = listOf(Species.Nitrogen to 1, Species.Hydrogen to 3),
        onsetKelvin = 1100,
        // +46 kJ per 2 mol of ammonia, which is 34 g of it — the figure the row carried in
        // [DECOMPOSITIONS], quoted against the same formula mass so the move changes no number.
        enthalpyPerKg = 46L * kJPerMolAt(34),
        baseRate = COMBUSTION_BASE_RATE,
    ),

    /**
     * `CO₂ + C → 2 CO` — the Boudouard reaction, and **the row that could not be written down
     * before**: its principal is in the room's air and its other reagent is on a belt.
     *
     * ⚠️ **The proving case for increment 4.** Every earlier shape draws its reagents from one
     * store. [Reduction] takes two solids out of one cargo layer, which is where this row sat — so
     * it wanted carbon dioxide *as cargo* at 973 K, and CO₂ is evicted from a cargo layer above its
     * critical point of 304 K. `Combustion.kt` credits it with quietly filling the vessel's rooms
     * with carbon monoxide since `14306ded`; it has never fired outside a bulkhead.
     *
     * ⛔ **It crosses a ledger, and that is the point.** The carbon leaves the cargo identity and
     * comes back as part of the CO in the air identity, so a pass of it has to tell both. That is
     * the crossing `oxidise` deliberately closed off in one direction and `offGas` owns in the
     * other; here it is a consequence of where the reagents were, which is what the whole plan is
     * for.
     *
     * Strongly endothermic — a natural thermal brake on a hot room, and the second half of the
     * story a starved fire starts.
     */
    Reaction(
        principal = Species.CarbonDioxide,
        reagents = listOf(Species.CarbonDioxide to 1, Species.Carbon to 1),
        products = listOf(Species.CarbonMonoxide to 2),
        // Favourable around 973 K, dominant above 1200 K. Quoted per kg of CO2, the principal,
        // exactly as the row was quoted in [REDUCTIONS] — the move changes no number.
        onsetKelvin = 973,
        enthalpyPerKg = 172L * kJPerMolAt(44),
        baseRate = COMBUSTION_BASE_RATE,
    ),
)

/** The widest [REACTIONS] gets, for the per-reagent scratch a sweep hoists once. */
val WIDEST_REACTION: Int = REACTIONS.maxOf { it.reagents.size }

/** The coldest row in [REACTIONS], so a cool tile is rejected without asking each one. */
val LOWEST_REACTION_ONSET: Int = REACTIONS.minOf { it.onsetKelvin }

/** The width of [REACTIONS], for the scratch arrays a sweep hoists once. */
val REACTION_COUNT: Int = REACTIONS.size
