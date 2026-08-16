package org.emerge.demo.outofspace.chem

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.speciesColor

/**
 * Grams of each Species. Mass = integer (exact conservation, reproducible across machines).
 * Immutable. Splits use apportion() (cumulative). Operations: +, -, take, scaledTo.
 */
class Mixture private constructor(val masses: LongArray, val energy: Long) {

    init {
        require(masses.size == Species.COUNT) { "expected ${Species.COUNT} species, got ${masses.size}" }
    }

    operator fun get(species: Species): Long = masses[species.ordinal]

    /** Total mass in mass. */
    val total: Long get() {
        var sum = 0L
        for (g in masses) sum += g
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
            for (i in masses.indices) {
                if (masses[i] > bestMass) { bestMass = masses[i]; best = i }
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
            for (i in masses.indices) {
                val v = masses[i]*0xFF/total
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
        val outMasses = LongArray(Species.COUNT)
        for (i in outMasses.indices) outMasses[i] = masses[i] + other.masses[i]
        val outEnergy = energy+other.energy
        return Mixture(outMasses, outEnergy)
    }

    /**
     * Removes [other] from this mixture. Requires that this contains at least as much of every
     * species — a negative mass is never a meaningful state, so it fails loudly rather than
     * silently inventing matter.
     */
    operator fun minus(other: Mixture): Mixture {
        val outMasses = LongArray(Species.COUNT)
        for (i in outMasses.indices) {
            outMasses[i] = masses[i] - other.masses[i]
            require(outMasses[i] >= 0L) { "subtracting more ${Species.ALL[i]} than present: ${masses[i]} - ${other.masses[i]}" }
        }
        val outEnergy = energy-other.energy
        require(outEnergy >= 0L) { "subtracting more energy than present: $energy - ${other.energy}" }
        return Mixture(outMasses, outEnergy)
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
        val outEnergy = scaledRatio(amount, total, energy)
        return Mixture(apportion(masses, amount), outEnergy)
    }

    /**
     * This mixture's *proportions* rendered at a different total — the "recipe" operation. An orebody
     * described as 410g iron / 300g silica per kilogram becomes any number of mass of the same
     * stuff. Unlike [take] this may scale up, because a recipe is a ratio and not a pile.
     */
    fun scaledTo(mass: Long): Mixture {
        if (mass <= 0L || isEmpty) return EMPTY
        // TODO this function doesn't need temperature if callers only care about species ratios.
        //  Extract the function outside of mixture as an independent utility function.
        return Mixture(apportion(this.masses, mass), 0)
    }

    /** Human-readable, dominant species first — for debug output and test failures. */
    override fun toString(): String {
        if (isEmpty) return "Mixture(empty)"
        val parts = Species.ALL.filter { this[it] > 0L }.sortedByDescending { this[it] }
        return parts.joinToString(prefix = "Mixture(", postfix = ")") { "${it.name}=${this[it]}g" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Mixture && masses.contentEquals(other.masses))

    override fun hashCode(): Int = masses.contentHashCode()

    companion object {
        val EMPTY: Mixture = Mixture(LongArray(Species.COUNT), 0)

        fun of(vararg parts: Pair<Species, Long>, energy: Long): Mixture {
            val out = LongArray(Species.COUNT)
            for ((species, mass) in parts) {
                require(mass >= 0L) { "negative mass for $species: $mass" }
                out[species.ordinal] += mass
            }
            return Mixture(out, energy)
        }

        /** Builds from raw per-species mass, indexed by [Species] ordinal. The array is copied. */
        fun of(masses: LongArray, energy: Long): Mixture = Mixture(masses.copyOf(), energy)
    }
}

/**
 * Distribute [target] across [weights] proportionally. Sum = [target] exactly. [target] may exceed
 * the weight sum (scaling up is valid — a recipe is a ratio, see [Mixture.scaledTo]).
 *
 * ### Why this is a running total and not the obvious loop
 *
 * The obvious form — and what this was until step 4b of `PLAN_unit_rescale.md` — gives each entry
 * `weights[i] * target / sum` and then hands out what flooring discarded, largest fractional part
 * first (largest-remainder, or Hamilton). It is the textbook method and it was correct. It also
 * multiplied two masses together, which made it **the tightest expression in the game**: a safe mass
 * scale of 152, against the million the rescale is aiming at.
 *
 * The quadratic cannot be divided away, because `weights[i] / sum` is a ratio of two masses and
 * `target` is a third — three mass-carrying terms, and [scaledRatio] can only take the unit out of
 * the ratio. Reducing that ratio per entry is what breaks Hamilton: the method's whole correctness
 * argument rests on the *remainders* being exact, and a reduced remainder ranks entries by noise.
 * The shares would still sum to [target] — the leftover loop guarantees that unconditionally — but
 * the slop would land on an arbitrary species. Mass conservation would keep closing while the
 * composition quietly went wrong, which is the worst failure shape available.
 *
 * So the method changes. Instead of rounding each share and repairing the total, this rounds the
 * **running total** and takes differences:
 *
 * ```
 * out[i] = f(w₀ + … + wᵢ) - f(w₀ + … + wᵢ₋₁)     where f(x) = x × target / sum
 * ```
 *
 * The sum then telescopes to `f(sum) - f(0)`, which is exactly `target` — by construction, with no
 * repair pass and no leftover to place. Conservation stops depending on the precision of `f` at all,
 * which is what makes it safe to compute `f` with [scaledRatio] and let the mass unit cancel.
 *
 * ⚠️ **This is a different rounding rule, and it gives different answers** — by at most one unit per
 * entry, but different. Where Hamilton gives the spare unit to the largest fractional part, this
 * gives it to whichever entry the running total happens to cross an integer inside, which favours
 * later indices very slightly. Both are legitimate apportionments; neither is "the" right one. What
 * is kept is what callers actually rely on: an exact total, proportionality to within a unit,
 * determinism, and index-order stability.
 *
 * The two properties this rests on are [scaledRatio]'s, and are documented there as a contract:
 * `f` is monotonic non-decreasing (so no entry can come out negative, since the running total only
 * grows), and `f(sum) == target` exactly.
 */
internal fun apportion(weights: LongArray, target: Long): LongArray {
    val out = LongArray(weights.size)
    if (target <= 0L) return out

    var sum = 0L
    for (w in weights) sum += w
    if (sum == 0L) return out

    var cumulative = 0L
    var placed = 0L
    for (i in weights.indices) {
        cumulative += weights[i]
        // The reduction inside scaledRatio depends only on `sum` and `target`, which are the same
        // for every entry — so the whole series is reduced identically and stays ordered.
        val upto = scaledRatio(cumulative, sum, target)
        out[i] = upto - placed
        placed = upto
    }
    return out
}
