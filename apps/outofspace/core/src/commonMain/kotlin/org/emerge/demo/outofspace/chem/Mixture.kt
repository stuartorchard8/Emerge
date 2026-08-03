package org.emerge.demo.outofspace.chem

/**
 * A quantity of matter: how many grams of each [Species] are present.
 *
 * **Mass is an integer.** Floats would make the same world diverge between two machines, and would
 * make conservation approximate — and "where did the mass go" is the only bug this simulation is
 * really capable of having. Integers make conservation checkable exactly, which is the whole point.
 *
 * Every operation that divides a mixture is defined as *"compute one output; the other is the
 * remainder"* ([minus]). That makes conservation structural rather than something to be tested for:
 * there is no arithmetic path that can lose a gram. Splits use [apportion], which distributes an
 * exact total by largest-remainder, so proportions are as close as integers allow without the sum
 * ever drifting.
 *
 * Instances are immutable. The backing array is never handed out.
 */
class Mixture private constructor(private val grams: LongArray) {

    init {
        require(grams.size == Species.COUNT) { "expected ${Species.COUNT} species, got ${grams.size}" }
    }

    operator fun get(species: Species): Long = grams[species.ordinal]

    /** Total mass in grams. */
    val total: Long get() {
        var sum = 0L
        for (g in grams) sum += g
        return sum
    }

    val isEmpty: Boolean get() = total == 0L

    /**
     * The species present in the greatest quantity, or null if this is empty. Ties go to the
     * earliest-declared species, so the result never depends on iteration luck.
     */
    val dominant: Species?
        get() {
            var best = -1
            var bestMass = 0L
            for (i in grams.indices) {
                if (grams[i] > bestMass) { bestMass = grams[i]; best = i }
            }
            return if (best < 0) null else Species.ALL[best]
        }

    /** Mass of everything that is not [dominant] — the impurities, for refining purposes. */
    val impurities: Long get() = dominant?.let { total - this[it] } ?: 0L

    /**
     * True when nothing present is a fluid — i.e. this can ride a belt. Empty counts as both this
     * and [isAllFluid]: nothing is the wrong phase for anything.
     */
    val isAllSolid: Boolean get() {
        for (s in Species.FLUIDS) if (grams[s.ordinal] > 0L) return false
        return true
    }

    /** True when nothing present is a solid — i.e. this can go down a pipe. */
    val isAllFluid: Boolean get() {
        for (s in Species.SOLIDS) if (grams[s.ordinal] > 0L) return false
        return true
    }

    operator fun plus(other: Mixture): Mixture {
        val out = LongArray(Species.COUNT)
        for (i in out.indices) out[i] = grams[i] + other.grams[i]
        return Mixture(out)
    }

    /**
     * Removes [other] from this mixture. Requires that this contains at least as much of every
     * species — a negative mass is never a meaningful state, so it fails loudly rather than
     * silently inventing matter.
     */
    operator fun minus(other: Mixture): Mixture {
        val out = LongArray(Species.COUNT)
        for (i in out.indices) {
            out[i] = grams[i] - other.grams[i]
            require(out[i] >= 0L) { "subtracting more ${Species.ALL[i]} than present: ${grams[i]} - ${other.grams[i]}" }
        }
        return Mixture(out)
    }

    /**
     * Takes [amount] grams spread across the species in proportion to what is here — the operation
     * behind "grab a shovelful" and "the belt can only carry so much". Returns everything if
     * [amount] is at least [total]; returns empty for a non-positive amount.
     *
     * The complement is `this - result`, and the two always sum back to this exactly.
     */
    fun take(amount: Long): Mixture {
        if (amount <= 0L) return EMPTY
        if (amount >= total) return this
        return Mixture(apportion(grams, amount))
    }

    /**
     * This mixture's *proportions* rendered at a different total — the "recipe" operation. An orebody
     * described as 410g iron / 300g silica per kilogram becomes any number of grams of the same
     * stuff. Unlike [take] this may scale up, because a recipe is a ratio and not a pile.
     */
    fun scaledTo(grams: Long): Mixture {
        if (grams <= 0L || isEmpty) return EMPTY
        return Mixture(apportion(this.grams, grams))
    }

    /** This mixture with only [species] kept, at [amount] grams. */
    fun onlyOf(species: Species, amount: Long): Mixture = of(species to amount)

    /** Human-readable, dominant species first — for debug output and test failures. */
    override fun toString(): String {
        if (isEmpty) return "Mixture(empty)"
        val parts = Species.ALL.filter { this[it] > 0L }.sortedByDescending { this[it] }
        return parts.joinToString(prefix = "Mixture(", postfix = ")") { "${it.name}=${this[it]}g" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Mixture && grams.contentEquals(other.grams))

    override fun hashCode(): Int = grams.contentHashCode()

    companion object {
        val EMPTY: Mixture = Mixture(LongArray(Species.COUNT))

        fun of(vararg parts: Pair<Species, Long>): Mixture {
            val out = LongArray(Species.COUNT)
            for ((species, mass) in parts) {
                require(mass >= 0L) { "negative mass for $species: $mass" }
                out[species.ordinal] += mass
            }
            return Mixture(out)
        }

        /** Builds from raw per-species grams, indexed by [Species] ordinal. The array is copied. */
        fun ofGrams(grams: LongArray): Mixture = Mixture(grams.copyOf())
    }
}

/**
 * Distributes exactly [target] across [weights] in proportion to them — the largest-remainder
 * (Hamilton) method.
 *
 * Each entry gets its floored share, then the leftover units go one each to the entries with the
 * largest discarded fractions, ties broken by index. The result sums to [target] exactly, which is
 * what lets a proportional split conserve mass; naive rounding would lose or invent a gram per
 * split, and this simulation performs a great many splits.
 *
 * [target] may exceed the sum of the weights — this is proportional *distribution*, so scaling a
 * recipe up is as valid as splitting a pile down. Callers that must not exceed what is actually
 * present enforce that themselves ([Mixture.take] does).
 */
internal fun apportion(weights: LongArray, target: Long): LongArray {
    val out = LongArray(weights.size)
    if (target <= 0L) return out

    var sum = 0L
    for (w in weights) sum += w
    if (sum == 0L) return out

    var assigned = 0L
    // Remainder of the exact share, kept as an integer numerator over `sum` so no float is involved.
    val remainders = LongArray(weights.size)
    for (i in weights.indices) {
        val exact = weights[i] * target
        out[i] = exact / sum
        remainders[i] = exact % sum
        assigned += out[i]
    }

    // Hand out what flooring discarded, largest fractional part first, index order breaking ties.
    var leftover = target - assigned
    while (leftover > 0L) {
        var best = -1
        var bestRemainder = -1L
        for (i in weights.indices) {
            if (remainders[i] > bestRemainder) { bestRemainder = remainders[i]; best = i }
        }
        if (best < 0 || bestRemainder <= 0L) {
            // Every remainder is zero: the split was exact. Anything still unassigned would be a
            // bug in the arithmetic above, so put it somewhere deterministic rather than lose it.
            for (i in weights.indices) {
                if (weights[i] > 0L) { out[i] += leftover; break }
            }
            break
        }
        out[best]++
        remainders[best] = -1L
        leftover--
    }
    return out
}
