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

/** The two streams out of a species processor: a concentrated product and its tailings. */
data class ProcessResult(val product: Mixture, val tailings: Mixture) {
    val totalMass: Long get() = product.total + tailings.total
}

/**
 * Concentrates [input] into a product stream and a tailings stream, each half its mass.
 *
 * [efficiencyPermille] is the machine's quality (1000 = perfect), but it is **capped by the input's
 * own purity**: you cannot concentrate what is not there, so a good machine fed bad ore behaves
 * like a bad machine. This one rule is what makes ore quality matter at every step instead of at a
 * lookup, and it is the best idea carried over from the Godot build.
 *
 * Consequence worth knowing: feeding in already-pure material simply halves it into two identical
 * piles. Processing is for dirty input; it does nothing useful to clean input.
 */
fun process(input: Mixture, efficiencyPermille: Int = 1000): ProcessResult {
    require(efficiencyPermille in 0..1000) { "efficiency must be 0..1000 permille, got $efficiencyPermille" }

    val total = input.total
    val dominant = input.dominant
    if (dominant == null || total == 0L) return ProcessResult(Mixture.EMPTY, Mixture.EMPTY)

    val dominantMass = input[dominant]

    // The effective efficiency is min(machine, purity), kept as an exact rational n/d so no float
    // enters the simulation. purity = dominantMass/total; machine = efficiencyPermille/1000.
    val machineIsLower = efficiencyPermille.toLong() * total <= dominantMass * 1000L
    val n: Long = if (machineIsLower) efficiencyPermille.toLong() else dominantMass
    val d: Long = if (machineIsLower) 1000L else total

    // Share of the impurities that stays with the product: (1 - efficiency) / 2.
    //
    // Through [scaledRatio] because `d` is `total` whenever the ore's own purity is the binding
    // constraint — which is the interesting half of the branch above — and `impurities × total` is
    // then a product of two masses. That is quadratic in the mass unit: at one microgram per unit a
    // twenty-tonne storage of dirty ore wraps a Long, and does so in the direction that invents
    // matter. The value is unchanged wherever the old form did not overflow, since [scaledRatio]
    // splits the same division into a whole part and a remainder rather than approximating it.
    val totalImpurities = total - dominantMass
    val impuritiesForProduct = scaledRatio(d - n, 2L * d, totalImpurities)

    // The product is half the total mass; whatever of that is not impurity is dominant species.
    // Both quantities are provably in range for exact arithmetic (flooring can only shrink them),
    // so the clamp is a guard rail rather than a correction.
    val dominantForProduct = (total / 2L - impuritiesForProduct).coerceIn(0L, dominantMass)

    val productMass = LongArray(Species.COUNT)
    productMass[dominant.ordinal] = dominantForProduct
    if (impuritiesForProduct > 0L) {
        // Spread the product's impurity allowance across the non-dominant species in proportion.
        val impurityWeights = LongArray(Species.COUNT)
        for (m in Species.ALL) if (m != dominant) impurityWeights[m.ordinal] = input[m]
        val share = apportion(impurityWeights, impuritiesForProduct)
        for (i in productMass.indices) if (i != dominant.ordinal) productMass[i] = share[i]
    }

    val productMixture = Mixture.of(productMass, input.energy)
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
