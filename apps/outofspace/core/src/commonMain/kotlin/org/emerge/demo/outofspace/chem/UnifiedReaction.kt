package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * A reaction that does not claim to know where the matter is — increment 1 of
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
 * ### What is not here yet
 *
 * ⛔ **One reagent only.** [reagents] is a list because the target shape has several and contention
 * between them is the whole of increment 3 — but nothing here allocates a scarce reagent between
 * competing rows, so a row with two would silently take as much of the second as it liked. Until
 * that pass exists, `UnifiedReactionTest` asserts every row has exactly one, which is a failure at
 * build time rather than a reaction that quietly runs rich.
 *
 * ⚠️ **[baseRate] is still stated rather than derived.** A solid burns at its surface and a gas
 * burns throughout, which is why `COMBUSTION_BASE_RATE` is eight times [BASE_RATE] — a fact about
 * the *phase of the principal at this tile*, which this model can now derive and does not. Increment
 * 3, where it is a rate change and belongs with the pass that computes rates.
 */
class Reaction(
    /** What the rate is a fraction of, what the enthalpy is per kilogram of, and where products go. */
    val principal: Species,
    /** Everything consumed, [principal] included. ⛔ Exactly one entry until increment 3. */
    val reagents: List<Pair<Species, Int>>,
    val products: List<Pair<Species, Int>>,
    val onsetKelvin: Int,
    /** Positive is **endothermic**, the sign convention of every table in this package. */
    val enthalpyPerKg: Long,
    val baseRate: Long,
) {
    /** Formula units of [principal], for the mass arithmetic and for what the reference prints. */
    val principalUnits: Int = reagents.first { it.first == principal }.second

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
)

/** The coldest row in [REACTIONS], so a cool tile is rejected without asking each one. */
val LOWEST_REACTION_ONSET: Int = REACTIONS.minOf { it.onsetKelvin }

/** The width of [REACTIONS], for the scratch arrays a sweep hoists once. */
val REACTION_COUNT: Int = REACTIONS.size
