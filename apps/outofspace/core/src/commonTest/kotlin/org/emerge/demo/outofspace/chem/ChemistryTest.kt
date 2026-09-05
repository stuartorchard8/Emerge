package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.Mixture

/**
 * The chemistry layer's whole test suite. It runs headlessly in milliseconds because none of the
 * code under test touches the engine, a window or a clock.
 *
 * The assertion that matters most is **conservation**: after any operation, the per-mineral sum of
 * the outputs equals the per-mineral sum of the inputs. Totals alone are not enough — a total can
 * balance while iron quietly turns into copper — so [conservationOf] compares mineral by mineral.
 */
class ChemistryTest {

    /** Realistic dirty ore: mostly iron, with everything else along for the ride. */
    private val dirtyOre = Mixture.of(
            Species.Iron to 4100L,
            Species.Quartz to 3000L,
            Species.Copper to 1800L,
            Species.Titanium to 1100L,
            energy = 0,
        )

    private fun assertConserved(inputs: List<Mixture>, outputs: List<Mixture>, what: String) {
        val delta = conservationOf(inputs, outputs)
        for (m in Species.ALL) {
            assertEquals(0L, delta[m.ordinal], "$what did not conserve ${m.name} (delta ${delta[m.ordinal]}g)")
        }
    }

    // ── apportion: the primitive every split rests on ──────────────────────────

    @Test
    fun `apportion distributes exactly the requested total`() {
        val weights = longArrayOf(4100, 3000, 1800, 1100, 0, 0, 0, 7)
        for (target in longArrayOf(0, 1, 2, 7, 999, 5000, 10007)) {
            val out = apportion(weights, target)
            assertEquals(target, out.sum(), "apportioning $target")
        }
    }

    @Test
    fun `apportion never exceeds the available weight of an entry in aggregate`() {
        val weights = longArrayOf(10, 0, 0, 0, 0, 0, 0, 0)
        val out = apportion(weights, 10)
        assertEquals(10L, out[0])
        assertEquals(0L, out[1])
    }

    @Test
    fun `apportion is proportional`() {
        // 3:1 split of 400 should be 300:100 exactly.
        val out = apportion(longArrayOf(300, 100, 0, 0, 0, 0, 0, 0), 400)
        assertEquals(300L, out[0])
        assertEquals(100L, out[1])
    }

    @Test
    fun `apportion splits a tie the same way every time`() {
        // Three equal weights sharing 4 units: each takes 1, and the spare unit goes somewhere
        // fixed. WHERE is a property of the rounding rule and not something callers may rely on —
        // step 4b of PLAN_unit_rescale.md moved it from index 0 to index 2 when apportion stopped
        // using largest-remainder. What callers rely on, and what this pins, is that the answer is
        // the same on every machine and every run: never a function of iteration luck.
        val weights = longArrayOf(10, 10, 10, 0, 0, 0, 0, 0)
        val out = apportion(weights, 4)
        assertEquals(4L, out.sum())
        assertEquals(longArrayOf(1, 1, 2, 0, 0, 0, 0, 0).toList(), out.toList())
        repeat(5) { assertEquals(out.toList(), apportion(weights, 4).toList()) }
    }

    @Test
    fun `apportion conserves and never over-draws, across a wide spread of splits`() {
        // The two properties the cumulative rule guarantees by construction, and the reason it was
        // safe to give up largest-remainder: the total is exact because the running total
        // telescopes, and no entry can be handed more than it had because the running total only
        // ever grows. Swept rather than spot-checked, because both are structural claims.
        val spreads = listOf(
            longArrayOf(4100, 3000, 1800, 1100, 0, 0, 0, 7),
            longArrayOf(1, 1, 1, 1, 1, 1, 1, 1),
            longArrayOf(999_999_999, 1, 0, 0, 0, 0, 0, 1),
            longArrayOf(0, 0, 0, 5, 0, 0, 0, 0),
        )
        for (weights in spreads) {
            val sum = weights.sum()
            for (target in longArrayOf(1, 2, 3, 7, 100, 4999, sum - 1, sum, sum + 1, sum * 3)) {
                if (target <= 0L) continue
                val out = apportion(weights, target)
                assertEquals(target, out.sum(), "total for $target over ${weights.toList()}")
                for (i in out.indices) {
                    assertTrue(out[i] >= 0L, "negative share at $i for $target")
                    if (target <= sum) {
                        assertTrue(out[i] <= weights[i], "over-drew $i: ${out[i]} of ${weights[i]}")
                    }
                }
            }
        }
    }

    @Test
    fun `apportion never over-draws a trace species out of a heavy mixture`() {
        // The shape that crashed a running game: a few hundred units of one species riding along in
        // a mixture weighing tonnes. At a microgram the sum is large enough that scaledRatio used to
        // reduce the fraction by shifting, which is an error of ±2^k in the *numerator's* units —
        // hundreds of thousands of units, against a species holding hundreds. Differencing the
        // running total then handed the trace far more than the mixture contained, and Mixture.minus
        // failed with "subtracting more Osmium than present: 376 - 1772" several frames later.
        val heavy = 5_669_573_360_735L
        val trace = 1_230L
        val rest = 7_232_430_064_034L
        val weights = longArrayOf(heavy, trace, rest, 0, 0, 0, 0, 0)
        val sum = weights.sum()
        val targets = longArrayOf(1, 366_743_985_224L, sum / 3, sum / 2, sum - 1, sum)
        for (target in targets) {
            val out = apportion(weights, target)
            assertEquals(target, out.sum(), "total for $target")
            for (i in out.indices) {
                assertTrue(out[i] >= 0L, "negative share at $i for $target")
                assertTrue(out[i] <= weights[i], "over-drew $i for $target: ${out[i]} of ${weights[i]}")
            }
        }
    }

    @Test
    fun `apportion gives the same proportions whatever the mass unit is`() {
        // The point of step 4b. Multiplying every weight AND the target by a million is a change of
        // unit and nothing else, so the shares must come back multiplied by a million too — to
        // within the rounding a unit that fine permits, which is a few units out of 10^13.
        val weights = longArrayOf(4100, 3000, 1800, 1100, 0, 0, 0, 7)
        val target = 9_999L
        val coarse = apportion(weights, target)

        val k = 1_000_000L
        val fine = apportion(LongArray(weights.size) { weights[it] * k }, target * k)
        assertEquals(target * k, fine.sum())
        for (i in weights.indices) {
            val drift = fine[i] - coarse[i] * k
            assertTrue(
                drift >= -k && drift <= k,
                "species $i drifted by $drift when the unit changed (coarse ${coarse[i]}, fine ${fine[i]})",
            )
        }
    }

    @Test
    fun `apportion scales up as happily as it splits down`() {
        // It is proportional distribution, not subdivision: a per-kilogram ore recipe rendered at
        // ten kilograms is the same operation as taking a shovelful.
        val out = apportion(longArrayOf(410, 300, 180, 110, 0, 0, 0, 0), 10_000)
        assertEquals(10_000L, out.sum())
        assertEquals(4_100L, out[0])
    }

    @Test
    fun `take never yields more than is present, however much is asked for`() {
        val pile = Mixture.of(Species.Iron to 100L, energy = 0)
        assertEquals(100L, pile.take(1_000_000L).total)
    }

    // ── Mixture ────────────────────────────────────────────────────────────────

    @Test
    fun `take and its remainder sum back to the original`() {
        for (amount in longArrayOf(0, 1, 137, 5000, 10000, 99999)) {
            val (taken, left) = takeFrom(dirtyOre, amount)
            assertConserved(listOf(dirtyOre), listOf(taken, left), "take($amount)")
        }
    }

    @Test
    fun `take of everything or more returns everything`() {
        assertEquals(dirtyOre, dirtyOre.take(dirtyOre.total))
        assertEquals(dirtyOre, dirtyOre.take(dirtyOre.total + 1000))
    }

    @Test
    fun `take preserves proportions`() {
        // Half of the ore should be half of each mineral, since all four masses are even.
        val half = dirtyOre.take(dirtyOre.total / 2)
        assertEquals(2050L, half[Species.Iron])
        assertEquals(1500L, half[Species.Quartz])
        assertEquals(900L, half[Species.Copper])
        assertEquals(550L, half[Species.Titanium])
    }

    @Test
    fun `subtracting more than is present fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Mixture.of(Species.Iron to 10L, energy = 0) - Mixture.of(Species.Iron to 11L, energy = 0)
        }
    }

    @Test
    fun `dominant breaks ties by declaration order`() {
        val tied = Mixture.of(Species.Copper to 100L, Species.Iron to 100L, energy = 0)
        assertEquals(Species.Iron, tied.dominant, "Iron is declared before Copper")
        assertNull(Mixture.EMPTY.dominant)
    }


    // ── Mineral processing ─────────────────────────────────────────────────────

    @Test
    fun `processing conserves mass mineral by mineral`() {
        for (eff in intArrayOf(0, 250, 500, 750, 1000)) {
            val r = process(dirtyOre, eff)
            assertConserved(
                listOf(dirtyOre),
                listOf(r.product, r.tailings),
                "process(eff=$eff)",
            )
        }
    }

    @Test
    fun `processing makes the product purer than the input`() {
        val r = process(dirtyOre, 1000)
        val inputPurity = dirtyOre[Species.Iron].toDouble() / dirtyOre.total
        val outputPurity = r.product[Species.Iron].toDouble() / r.product.total
        assertTrue(outputPurity > inputPurity, "purity should rise: $inputPurity -> $outputPurity")
    }

    /**
     * ⛔ **Pure, and for any feed and any machine.** Not "purer": the product is some quantity of
     * one species, so there is no tail to converge along and no threshold to end it with. See
     * [process] for what that replaced.
     */
    @Test
    fun `the product is pure whatever it was fed and whatever the machine`() {
        val feeds = listOf(
            dirtyOre,
            Mixture.of(Species.Iron to 1L, Species.Quartz to 9_999L, energy = 0),
            Mixture.of(Species.Iron to 5_000L, Species.Quartz to 5_000L, energy = 0),
        )
        for (feed in feeds) for (eff in intArrayOf(1, 250, 750, 999, 1000)) {
            val p = process(feed, eff).product
            if (p.isEmpty) continue
            assertEquals(0L, p.impurities, "process(eff=$eff) on $feed gave a blended product: $p")
        }
    }

    /**
     * **Efficiency is a recovery rate**, and this pins it exactly: the machine's share of the
     * dominant species, and nothing else moves.
     *
     * It replaces `a perfect machine cannot beat the ore it is fed`, which pinned the old
     * `min(machine, purity)` cap. That cap existed to stop a fractional split inventing purity; a
     * draw cannot invent it, so bad ore now costs **yield** rather than quality.
     */
    @Test
    fun `the product is the machine's share of the dominant species`() {
        for (eff in intArrayOf(0, 250, 750, 1000)) {
            val r = process(dirtyOre, eff)
            assertEquals(
                dirtyOre[Species.Iron] * eff / 1000L,
                r.product[Species.Iron],
                "process(eff=$eff) drew the wrong amount of iron",
            )
            assertEquals(
                dirtyOre[Species.Iron] - r.product[Species.Iron],
                r.tailings[Species.Iron],
                "what the machine missed must stay in the tailings",
            )
            for (s in Species.ALL) if (s != Species.Iron) {
                assertEquals(dirtyOre[s], r.tailings[s], "$s should be untouched by the draw")
            }
        }
    }

    /** A worse machine leaves more of the metal in the tailings — it does not make dirtier metal. */
    @Test
    fun `a worse machine recovers less rather than producing less pure`() {
        val good = process(dirtyOre, 1000).product
        val bad = process(dirtyOre, 100).product
        assertTrue(good.total > bad.total, "expected ${good.total} > ${bad.total}")
        assertEquals(0L, good.impurities)
        assertEquals(0L, bad.impurities)
    }

    /**
     * ⚠️ **Feeding pure material in costs you**, where a halving used to hand back two identical
     * piles. The demand work is what stops it happening by accident — a concentrator asks for
     * `SpeciesFilter.MIXED` and the network never routes pure metal to one.
     */
    @Test
    fun `processing already-pure material taxes it`() {
        val pure = Mixture.of(Species.Iron to 1000L, energy = 0)
        val r = process(pure, 750)
        assertEquals(750L, r.product.total, "the machine's share comes out")
        assertEquals(250L, r.tailings.total, "and the rest is tailings, not a second pile of product")
        assertEquals(Species.Iron, r.tailings.dominant, "which are pure iron, because that is all there was")
    }

    @Test
    fun `processing empty input is a no-op rather than a crash`() {
        val r = process(Mixture.EMPTY)
        assertTrue(r.product.isEmpty && r.tailings.isEmpty)
    }

    /**
     * Energy follows **heat capacity**, not mass. Both streams leave at the same temperature, so a
     * product made of the species with the *lower* specific heat carries a smaller share of the
     * energy than of the mass.
     *
     * ⚠️ **Stated against the mass share rather than against a half**, which is the same claim it
     * always made: the old split gave both streams exactly half the mass, so "less than half the
     * energy" said "less than its share". A draw does not split the mass evenly, so the share has to
     * be named. This is the invariant that tells a heat-capacity-weighted split apart from a
     * mass-weighted one, and from the bug of giving the product everything.
     */
    @Test
    fun `processing splits thermal energy by heat capacity, not by mass`() {
        // 90% iron. A perfect machine draws all 900 of it, so the product is 90% of the mass — and
        // iron's specific heat is below quartz's, so it must carry less than 90% of the energy.
        val input = Mixture.of(Species.Iron to 900L, Species.Quartz to 100L, energy = 5_250_000L)
        val r = process(input, 1000)

        assertConserved(listOf(input), listOf(r.product, r.tailings), "energy-conserving process")
        assertEquals(900L, r.product.total, "the whole of the iron was drawn")

        assertTrue(r.product.energy > 0L, "product must hold thermal energy: ${r.product.energy}")
        assertTrue(r.tailings.energy > 0L, "tailings must hold thermal energy: ${r.tailings.energy}")

        // Cross-multiplied so no float enters: product/input energy < product/input mass.
        assertTrue(
            r.product.energy * input.total < input.energy * r.product.total,
            "product carries ${r.product.energy} of ${input.energy} on ${r.product.total} of ${input.total} " +
                "— that is its mass share or better, so the split is not by heat capacity",
        )
        assertEquals(input.energy, r.product.energy + r.tailings.energy, "energy must be conserved")
    }
}
