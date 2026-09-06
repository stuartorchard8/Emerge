package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * Chemistry: the pure rules for turning matter into other matter.
 *
 * Every function here is total, deterministic and free of engine types, so the whole economy can be
 * tested headlessly in microseconds. That is deliberate — this is the layer the rest of the game is
 * built to serve, and it should stay the easiest layer to reason about.
 *
 * **Conservation is structural.** Wherever an operation divides matter, exactly one output is
 * computed and the other is `input - output`. No arithmetic path can lose or invent a gram, and
 * [conservationOf] exists so tests can say so out loud.
 */

/** The two streams out of a species concentrator: a concentrated product and its tailings. */
data class ProcessResult(val product: Mixture, val tailings: Mixture) {
    val totalMass: Long get() = product.total + tailings.total
}

/**
 * Draws the dominant species out of [input] **pure**, and returns it with what is left over.
 *
 * [efficiencyPermille] is the machine's quality (1000 = perfect) and it is a **recovery rate**: the
 * share of the dominant species the machine manages to pick out of the charge. Everything it misses
 * stays in the tailings along with every other species, so what a pass costs you is *yield*, never
 * purity.
 *
 * ### Why the product is pure rather than merely purer
 *
 * This used to be a weighted halving whose effective efficiency was `min(machine, input purity)`,
 * and purity converged on 100% without ever arriving — a geometric tail that took five machines in
 * a chain and a snap-to-pure threshold to terminate at all. See
 * `reference_oos_processor_purity_ladder` for that whole apparatus and what it cost.
 *
 * The ladder was academic. Nothing downstream wants 87% iron: `BUILD_PURITY_PERCENT` is 100, an
 * electrolyzer takes pure water and nothing else, and a sell order is priced per species. So the
 * interesting decision was never *how* to reach pure — it was what to do with the concentrate once
 * you had it, and five machines of plumbing stood in front of that decision.
 *
 * ⛔ **Purity cannot be invented here, and that is now structural rather than argued.** The product
 * is some quantity of one species that the input demonstrably held, so no cap on efficiency is
 * needed to stop a good machine beating bad ore — bad ore simply yields less. That deletes the
 * `min(machine, purity)` rational, the impurity allowance, the apportionment of that allowance
 * across the other species, and the snap threshold, all of which existed to keep a *fraction*
 * honest.
 *
 * ⚠️ **Feeding in pure material now costs you.** It comes back as [efficiencyPermille] of itself
 * with the remainder in the tailings, where a halving used to give two identical piles. The demand
 * work is what keeps that from happening by accident: a concentrator asks for `SpeciesFilter.MIXED`
 * and the network never routes pure metal to one.
 *
 * Conservation is unchanged and still structural: the product is computed and the tailings are
 * `input - product`, so no arithmetic path can lose or invent a gram.
 */
fun process(input: Mixture, efficiencyPermille: Int = 1000): ProcessResult {
    require(efficiencyPermille in 0..1000) { "efficiency must be 0..1000 permille, got $efficiencyPermille" }

    val dominant = input.dominant
    if (dominant == null || input.total == 0L) return ProcessResult(Mixture.EMPTY, Mixture.EMPTY)

    // Through [scaledRatio] rather than `mass * eff / 1000` because this is called on whatever a
    // caller hands it, not only on a machine's charge: at one microgram per unit a storage-sized
    // mixture times a thousand is within a factor of a few of wrapping a Long, and wrapping here
    // would invent matter. The value is identical wherever the plain form does not overflow.
    val drawn = scaledRatio(efficiencyPermille.toLong(), 1000L, input[dominant])

    val productMass = LongArray(Species.COUNT)
    productMass[dominant.ordinal] = drawn

    // Split thermal energy by heat capacity, not by mass. Both streams leave at the same
    // temperature, so the product takes energy proportional to its heat capacity and the
    // tailings takes the rest — which is exactly what `input - product` computes.
    var inputCapacity = 0L
    for (s in Species.ALL) inputCapacity += input[s] * s.specificHeat
    val productCapacity = drawn * dominant.specificHeat
    val productEnergy = if (inputCapacity > 0L) scaledRatio(productCapacity, inputCapacity, input.energy) else 0L

    val productMixture = Mixture.of(productMass, productEnergy)
    return ProcessResult(product = productMixture, tailings = input - productMixture)
}

/**
 * Splits [amount] mass off [input], proportionally across its species — what a belt, a grabber or
 * a machine input buffer does. Returns `(taken, left)`, which always sum back to [input].
 */
fun takeFrom(input: Mixture, amount: Long): Pair<Mixture, Mixture> {
    val taken = input.take(amount)
    return taken to (input - taken)
}

/**
 * Per-species difference between what went in and what came out — all zeroes when an operation
 * conserved mass. Tests assert on this rather than on totals alone, because a total can balance
 * while iron quietly turns into copper.
 */
fun conservationOf(inputs: List<Mixture>, outputs: List<Mixture>): LongArray {
    val delta = LongArray(Species.COUNT)
    for (m in Species.ALL) {
        var sum = 0L
        for (i in inputs) sum += i[m]
        for (o in outputs) sum -= o[m]
        delta[m.ordinal] = sum
    }
    return delta
}

// ⛔ **`Electrolysed` and `electrolyse` moved to `Cell.kt` and stopped being about water.**
// They were a hand-written `2 H2O -> 2 H2 + O2` and the machine's own private copy of a reaction.
// Increment 1 of `PLAN_electrochemistry.md` replaced them with a competition over `HALF_REACTIONS`,
// which produces the same split for a charge of water and a different one for anything else.
