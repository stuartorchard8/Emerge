package org.emerge.demo.outofspace.chem

/**
 * A pile of matter: what it has been made into, and what it is made of.
 *
 * [mixture] is not decoration. A [Form.SteelAlloy] smelted from dirty ore carries the impurities
 * that came with it, and they follow it up the whole crafting tree — so the quality of what you mine
 * is still legible in what you build.
 */
data class Resource(val form: Form, val mixture: Mixture) {
    val mass: Long get() = mixture.total
    val isEmpty: Boolean get() = mixture.isEmpty

    override fun toString(): String = "$form ${mass}g $mixture"
}

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

/** The two streams out of a smelter. Either may be empty. */
data class SmeltResult(val refined: Resource, val slag: Resource) {
    val totalMass: Long get() = refined.mass + slag.mass
}

/** The two streams out of a mineral processor: a concentrated product and its tailings. */
data class ProcessResult(val product: Resource, val tailings: Resource) {
    val totalMass: Long get() = product.mass + tailings.mass
}

/**
 * Smelts [input], yielding a refined product of its dominant mineral plus slag.
 *
 * The impurities do not merely dilute the product — they *consume* it: the refined mass is
 * `dominant - impurities`. Smelting ore that is half rubbish gives you almost nothing, and smelting
 * ore that is more rubbish than metal gives you nothing at all. That is the pressure that makes a
 * mineral processor worth building upstream.
 *
 * Deviation from the Godot original: the all-slag case triggers at `impurities >= dominant` rather
 * than `>`, because the boundary case produced a zero-mass product that every downstream consumer
 * would have had to special-case.
 */
fun smelt(input: Resource): SmeltResult {
    val dominant = input.mixture.dominant
        ?: return SmeltResult(Resource(Form.Slag, Mixture.EMPTY), Resource(Form.Slag, Mixture.EMPTY))

    val dominantMass = input.mixture[dominant]
    val impurities = input.mixture.total - dominantMass

    // Too dirty to be worth refining: the whole lot is slag.
    if (impurities >= dominantMass) {
        return SmeltResult(
            refined = Resource(Form.Slag, Mixture.EMPTY),
            slag = Resource(Form.Slag, input.mixture),
        )
    }

    val refinedMixture = Mixture.of(dominant to (dominantMass - impurities))
    return SmeltResult(
        refined = Resource(SMELT_PRODUCTS.getValue(dominant), refinedMixture),
        // The remainder, so nothing can go missing: the impurities plus an equal mass of the
        // dominant mineral that they dragged out with them.
        slag = Resource(Form.Slag, input.mixture - refinedMixture),
    )
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
fun process(input: Resource, efficiencyPermille: Int = 1000): ProcessResult {
    require(efficiencyPermille in 0..1000) { "efficiency must be 0..1000 permille, got $efficiencyPermille" }

    val total = input.mixture.total
    val dominant = input.mixture.dominant
    if (dominant == null || total == 0L) {
        val empty = Resource(input.form, Mixture.EMPTY)
        return ProcessResult(empty, empty)
    }

    val dominantMass = input.mixture[dominant]

    // The effective efficiency is min(machine, purity), kept as an exact rational n/d so no float
    // enters the simulation. purity = dominantMass/total; machine = efficiencyPermille/1000.
    val machineIsLower = efficiencyPermille.toLong() * total <= dominantMass * 1000L
    val n: Long = if (machineIsLower) efficiencyPermille.toLong() else dominantMass
    val d: Long = if (machineIsLower) 1000L else total

    // Share of the impurities that stays with the product: (1 - efficiency) / 2.
    val totalImpurities = total - dominantMass
    val impuritiesForProduct = totalImpurities * (d - n) / (2L * d)

    // The product is half the total mass; whatever of that is not impurity is dominant mineral.
    // Both quantities are provably in range for exact arithmetic (flooring can only shrink them),
    // so the clamp is a guard rail rather than a correction.
    val dominantForProduct = (total / 2L - impuritiesForProduct).coerceIn(0L, dominantMass)

    val productGrams = LongArray(Mineral.COUNT)
    productGrams[dominant.ordinal] = dominantForProduct
    if (impuritiesForProduct > 0L) {
        // Spread the product's impurity allowance across the non-dominant minerals in proportion.
        val impurityWeights = LongArray(Mineral.COUNT)
        for (m in Mineral.ALL) if (m != dominant) impurityWeights[m.ordinal] = input.mixture[m]
        val share = apportion(impurityWeights, impuritiesForProduct)
        for (i in productGrams.indices) if (i != dominant.ordinal) productGrams[i] = share[i]
    }

    val productMixture = Mixture.ofGrams(productGrams)
    return ProcessResult(
        product = Resource(input.form, productMixture),
        tailings = Resource(input.form, input.mixture - productMixture),
    )
}

/**
 * Combines two resources per [RECIPES], or returns null if they are not a recipe. Composition is
 * simply summed — nothing is lost in assembly, so impurities ride all the way up the tree.
 */
fun craft(a: Resource, b: Resource): Resource? {
    val output = recipeFor(a.form, b.form) ?: return null
    return Resource(output, a.mixture + b.mixture)
}

/** Pours two piles of the same form together, or returns null if the forms differ. */
fun merge(a: Resource, b: Resource): Resource? =
    if (a.form != b.form) null else Resource(a.form, a.mixture + b.mixture)

/**
 * Splits [amount] grams off [input], proportionally across its minerals — what a belt, a grabber or
 * a machine input buffer does. Returns `(taken, left)`, which always sum back to [input].
 */
fun takeFrom(input: Resource, amount: Long): Pair<Resource, Resource> {
    val taken = input.mixture.take(amount)
    return Resource(input.form, taken) to Resource(input.form, input.mixture - taken)
}

/**
 * Per-mineral difference between what went in and what came out — all zeroes when an operation
 * conserved mass. Tests assert on this rather than on totals alone, because a total can balance
 * while iron quietly turns into copper.
 */
fun conservationOf(inputs: List<Mixture>, outputs: List<Mixture>): LongArray {
    val delta = LongArray(Mineral.COUNT)
    for (m in Mineral.ALL) {
        var sum = 0L
        for (i in inputs) sum += i[m]
        for (o in outputs) sum -= o[m]
        delta[m.ordinal] = sum
    }
    return delta
}
