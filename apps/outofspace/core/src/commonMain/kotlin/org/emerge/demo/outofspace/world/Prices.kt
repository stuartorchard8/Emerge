package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.MINERALS
import org.emerge.demo.outofspace.chem.atomicMass
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.chem.derivedMolarMass
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.isqrt
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * What everything is worth before any station has an opinion about it — see `PLAN_economy.md` §3.1.
 *
 * ### The whole table falls out of `relativeAbundance`, and that is the point
 *
 * A price is not authored here. It is **derived from scarcity**, and scarcity is already stated, to
 * reality, in [Species.relativeAbundance] — parts per hundred million of a reference rock, by mass.
 * Two steps: an element is worth the inverse of how much of it the world contains, and a compound is
 * worth the sum of what it is made of.
 *
 * ⛔ **The same table decides what the world is MADE OF.**
 * [org.emerge.demo.outofspace.world.RockSpawner] rolls every rock's composition straight off
 * `relativeAbundance`, so raising a rare mineral's abundance makes it more findable *and*
 * automatically cheaper, by exactly the right factor. **Never add a second, price-only abundance
 * table.** Two tables can drift, and the drift is a world that calls a thing common while the price
 * calls it rare — which is the bug this avoids by construction rather than by discipline.
 *
 * ⚠️ **Prices are derived, never stored.** A station saves its *stock*; nobody saves a price list. So
 * turning [GAMMA_HALVED] or editing an abundance reprices every existing save on load, which is
 * right during development and is worth knowing before it surprises somebody.
 */
object Prices {

    /**
     * The peg the whole table hangs from: what 100 kg of pure iron fetches at list.
     *
     * Iron is the right anchor because it is the commonest metal in the game and the one the player
     * meets first, so every other number reads as a multiple of something they have a feel for.
     */
    const val IRON_PRICE: Long = 1_000L

    /** Prices are quoted per 100 kg, which is the unit [IRON_PRICE] is stated in. */
    const val PRICE_UNIT_MASS: Long = 100L * Budget.KILOGRAM

    /**
     * Whether the inverse-abundance curve is square-rooted — γ = 1/2 rather than γ = 1.
     *
     * ⚠️ **This one flag is the difference between a playable table and an unreadable one.** γ = 1 is
     * the physically honest law (if uranium is a 24-millionth as abundant as iron it *should* be 24
     * million times as dear, and that is roughly the real gold:iron ratio too) and it spreads the
     * table over **7.4 orders of magnitude** — uranium at 24.6 billion credits per 100 kg, in a HUD
     * shared with a starter ship worth a few thousand. γ = 1/2 gives 4.4 orders: gold still 875×
     * iron, uranium 4,961×, and every number fits in five digits.
     *
     * Stu's call, 2026-09-01, and his reasoning is worth keeping: diminishing returns on rarity is a
     * thing games do on purpose. Kept as a flag rather than an exponent because the integer
     * arithmetic for γ = 1/2 is [isqrt] and for γ = 1 is nothing — there is no third case wanted, and
     * a general power would cost a `pow` on the fixed-point path to express two options.
     */
    const val GAMMA_HALVED: Boolean = true

    /**
     * How much of each **element** a reference rock contains, by mass, summed over every rock that
     * carries it — indexed by [Species.ordinal], in `relativeAbundance` × parts-per-thousand.
     *
     * ⛔ **Accumulated at the parts-per-thousand scale, not as a fraction.** A share is an integer per
     * mille here, so multiplying it in *before* any division is what stops a trace element rounding
     * to nothing: uranium's only source is uraninite at an abundance of 1, and 1 × 880 ppt is 880,
     * where `1 × 0.88` truncated is **zero** — an element that cannot be priced at all, by division
     * by zero, and only for the rarest and most interesting entries in the table.
     */
    private val elementAbundance: LongArray = LongArray(Species.COUNT).also { out ->
        for (s in Species.NATURAL) {
            val parts = compositionOf(s)
            if (parts.isEmpty()) {
                // A native element — iron, nickel, carbon, sulfur, the noble metals. It is its own
                // source, at the whole of its abundance.
                out[s.ordinal] += s.relativeAbundance.toLong() * PARTS_PER_THOUSAND
            } else {
                for (p in parts) out[p.element.ordinal] += s.relativeAbundance.toLong() * p.partsPerThousand
            }
        }
    }

    /** Iron's abundance, which is the numerator of every ratio below. Held once; it is read per element. */
    private val ironAbundance: Long = elementAbundance[Species.Iron.ordinal]

    /**
     * Credits per [PRICE_UNIT_MASS] of each species at list, indexed by [Species.ordinal].
     *
     * Computed once at load. 170 species against two table walks is nothing, and doing it eagerly
     * means no lazy holder to be thread-safe about — the draw thread reads this as freely as the sim
     * thread does.
     */
    private val listPrices: LongArray = LongArray(Species.COUNT).also { out ->
        for (s in Species.ALL) {
            val formula = MINERALS[s]
            out[s.ordinal] = if (formula == null) {
                elementPrice(s)
            } else {
                // A compound is worth its parts, weighed **off the formula** — atoms times atomic
                // mass over the formula mass — with one division at the very end.
                //
                // ⛔ **Not [massPartsPerThousand], and this is the whole of the fix.** That function
                // floors each element's share to an integer per mille and says in its own
                // documentation that the parts therefore "sum to at most 1000, not exactly" and that
                // it is "for display and for deciding whether a rock is worth mining". Pricing is
                // neither. Argentite is 216/248 silver, which is 870.967 per mille and floors to
                // 870; its sulfur floors to 129; the sum is 999 and **one part per thousand of the
                // mineral simply vanished**. So a compound was priced at 99.9% of what it is made
                // of, and a station could buy the ore, split it, and sell the pieces for the
                // difference.
                //
                // ⚠️ **Almost every mineral lost a part** — quartz, chromite, cassiterite, galena,
                // sphalerite and cinnabar are all 999, ilmenite is 998. Calcite, hematite and rutile
                // divide evenly and came to exactly 1000, which is the only reason
                // `StationTest.at list prices no reaction pays` held: every row that could fire
                // happened to use one of those three, or to trade elements too cheap for a per-mille
                // slice to beat the furnace fee. It was a coincidence, not an invariant, and
                // roasting a silver ore is what ran it out.
                //
                // ⚠️ Still divided once at the end, which is the reason the old code gave and it is
                // still right: an element's price can be six figures and a share is a fraction, so
                // dividing inside the loop throws away the cheap elements of an expensive mineral.
                var sum = 0L
                for ((element, atoms) in formula) {
                    sum += atoms.toLong() * element.atomicMass * elementPrice(element)
                }
                sum / derivedMolarMass(s)
            }
        }
    }

    /**
     * What [species] fetches at list, in credits per [PRICE_UNIT_MASS], before any station's stock
     * has an opinion — see [Market.localPrice], which is what anybody actually trades at.
     */
    fun listPrice(species: Species): Long = listPrices[species.ordinal]

    /** What [mass] of pure [species] is worth at list. Exact, and monotonic in [mass]. */
    fun listValue(species: Species, mass: Long): Long =
        scaledRatio(mass, PRICE_UNIT_MASS, listPrices[species.ordinal])

    /**
     * How much of [species] a reference rock holds, by mass, across every mineral that carries it.
     *
     * Exposed for the readouts and for `PricesTest`, which pins the two facts this rests on: that
     * every species is priceable, and that iron's total is dominated by the rock it rides in rather
     * than by the native metal.
     */
    fun abundance(species: Species): Long = elementAbundance[species.ordinal]

    /**
     * An element's price: inverse abundance against iron, raised to γ.
     *
     * ⚠️ **`isqrt` of the whole expression, not of the ratio.** `IRON_PRICE × sqrt(A_Fe / A_e)` and
     * `sqrt(IRON_PRICE² × A_Fe / A_e)` are the same number, and only the second survives integer
     * arithmetic: the ratio is under 1 for everything commoner than iron, so rooting it first floors
     * oxygen, silicon and magnesium — the three commonest elements in the game — to zero.
     */
    private fun elementPrice(element: Species): Long {
        // ⛔ Not reachable today and a test says so. Kept because the alternative is a division by
        // zero for an element some future mineral edit orphans, and "unobtainable" is honestly
        // priced as "as rare as anything can be" rather than as a crash.
        val have = elementAbundance[element.ordinal].coerceAtLeast(1L)
        if (!GAMMA_HALVED) return scaledRatio(ironAbundance, have, IRON_PRICE)
        return isqrt(scaledRatio(ironAbundance, have, IRON_PRICE * IRON_PRICE))
    }

    private const val PARTS_PER_THOUSAND = 1_000L
}
