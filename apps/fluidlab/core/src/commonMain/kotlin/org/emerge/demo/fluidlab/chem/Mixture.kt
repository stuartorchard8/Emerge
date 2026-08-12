package org.emerge.demo.fluidlab.chem

import org.emerge.render.torus.RgbColor

/**
 * Grams of each Species. Mass = integer (exact conservation, reproducible across machines).
 * Immutable. Splits use apportion() (largest-remainder). Operations: +, -, take, scaledTo.
 */
class Mixture private constructor(private val mass: LongArray) {

    init {
        require(mass.size == Species.COUNT) { "expected ${Species.COUNT} species, got ${mass.size}" }
    }

    operator fun get(species: Species): Long = mass[species.ordinal]

    /** Total mass in mass. */
    val total: Long get() {
        var sum = 0L
        for (g in mass) sum += g
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
            for (i in mass.indices) {
                if (mass[i] > bestMass) { bestMass = mass[i]; best = i }
            }
            return if (best < 0) null else Species.ALL[best]
        }

    val color: Int
        get() {
            var r = 0x00
            var g = 0x00
            var b = 0x00
            val a = 0xFF
            val total = total
            for (i in mass.indices) {
                val v = mass[i]*0xFF/total
                if (v > 0) {
                    val sc = speciesColor(Species.ALL[i])
                    val scR = sc.shr(24) and 0xFF
                    val scG = sc.shr(16) and 0xFF
                    val scB = sc.shr(8) and 0xFF

                    r += ((scR*v)/0xFF).toInt()
                    g += ((scG*v)/0xFF).toInt()
                    b += ((scB*v)/0xFF).toInt()
                }
            }
            return r.shl(24) or g.shl(16) or b.shl(8) or a
        }

    /** Mass of everything that is not [dominant] — the impurities, for refining purposes. */
    val impurities: Long get() = dominant?.let { total - this[it] } ?: 0L

    operator fun plus(other: Mixture): Mixture {
        val out = LongArray(Species.COUNT)
        for (i in out.indices) out[i] = mass[i] + other.mass[i]
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
            out[i] = mass[i] - other.mass[i]
            require(out[i] >= 0L) { "subtracting more ${Species.ALL[i]} than present: ${mass[i]} - ${other.mass[i]}" }
        }
        return Mixture(out)
    }

    /**
     * Takes [amount] mass spread across the species in proportion to what is here — the operation
     * behind "grab a shovelful" and "the belt can only carry so much". Returns everything if
     * [amount] is at least [total]; returns empty for a non-positive amount.
     *
     * The complement is `this - result`, and the two always sum back to this exactly.
     */
    fun take(amount: Long): Mixture {
        if (amount <= 0L) return EMPTY
        if (amount >= total) return this
        return Mixture(apportion(mass, amount))
    }

    /**
     * This mixture's *proportions* rendered at a different total — the "recipe" operation. An orebody
     * described as 410g iron / 300g silica per kilogram becomes any number of mass of the same
     * stuff. Unlike [take] this may scale up, because a recipe is a ratio and not a pile.
     */
    fun scaledTo(mass: Long): Mixture {
        if (mass <= 0L || isEmpty) return EMPTY
        return Mixture(apportion(this.mass, mass))
    }

    /** This mixture with only [species] kept, at [amount] mass. */
    fun onlyOf(species: Species, amount: Long): Mixture = of(species to amount)

    /** Human-readable, dominant species first — for debug output and test failures. */
    override fun toString(): String {
        if (isEmpty) return "Mixture(empty)"
        val parts = Species.ALL.filter { this[it] > 0L }.sortedByDescending { this[it] }
        return parts.joinToString(prefix = "Mixture(", postfix = ")") { "${it.name}=${this[it]}g" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Mixture && mass.contentEquals(other.mass))

    override fun hashCode(): Int = mass.contentHashCode()

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

        /** Builds from raw per-species mass, indexed by [Species] ordinal. The array is copied. */
        fun ofGrams(mass: LongArray): Mixture = Mixture(mass.copyOf())
    }
}

/**
 * Distribute [target] across [weights] proportionally (largest-remainder/Hamilton method).
 * Sum = [target] exactly (floored share + leftover to largest fractional parts). target may exceed weight sum (scaling valid).
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
