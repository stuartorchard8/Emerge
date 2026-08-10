package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private val dirtyOre = Resource(
        Form.Ore,
        Mixture.of(
            Species.Iron to 4100L,
            Species.Silica to 3000L,
            Species.Copper to 1800L,
            Species.Titanium to 1100L,
        ),
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
    fun `apportion breaks ties by index so results never depend on iteration luck`() {
        // Three equal weights sharing 4 units: each takes 1, and the leftover goes to the earliest
        // index rather than to whichever the loop happened to visit last.
        val out = apportion(longArrayOf(10, 10, 10, 0, 0, 0, 0, 0), 4)
        assertEquals(longArrayOf(2, 1, 1, 0, 0, 0, 0, 0).toList(), out.toList())
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
        val pile = Mixture.of(Species.Iron to 100L)
        assertEquals(100L, pile.take(1_000_000L).total)
    }

    // ── Mixture ────────────────────────────────────────────────────────────────

    @Test
    fun `take and its remainder sum back to the original`() {
        for (amount in longArrayOf(0, 1, 137, 5000, 10000, 99999)) {
            val (taken, left) = takeFrom(dirtyOre, amount)
            assertConserved(listOf(dirtyOre.mixture), listOf(taken.mixture, left.mixture), "take($amount)")
        }
    }

    @Test
    fun `take of everything or more returns everything`() {
        assertEquals(dirtyOre.mixture, dirtyOre.mixture.take(dirtyOre.mass))
        assertEquals(dirtyOre.mixture, dirtyOre.mixture.take(dirtyOre.mass + 1000))
    }

    @Test
    fun `take preserves proportions`() {
        // Half of the ore should be half of each mineral, since all four masses are even.
        val half = dirtyOre.mixture.take(dirtyOre.mass / 2)
        assertEquals(2050L, half[Species.Iron])
        assertEquals(1500L, half[Species.Silica])
        assertEquals(900L, half[Species.Copper])
        assertEquals(550L, half[Species.Titanium])
    }

    @Test
    fun `subtracting more than is present fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Mixture.of(Species.Iron to 10L) - Mixture.of(Species.Iron to 11L)
        }
    }

    @Test
    fun `dominant breaks ties by declaration order`() {
        val tied = Mixture.of(Species.Copper to 100L, Species.Iron to 100L)
        assertEquals(Species.Iron, tied.dominant, "Iron is declared before Copper")
        assertNull(Mixture.EMPTY.dominant)
    }

    // ── Smelting ───────────────────────────────────────────────────────────────

    @Test
    fun `smelting conserves mass mineral by mineral`() {
        val r = smelt(dirtyOre)
        assertConserved(listOf(dirtyOre.mixture), listOf(r.refined.mixture, r.slag.mixture), "smelt")
        assertEquals(dirtyOre.mass, r.totalMass)
    }

    @Test
    fun `smelting yields a pure product of the dominant mineral`() {
        // Note dirtyOre itself is too dirty to smelt at all (4100 iron against 5900 of everything
        // else) — that is the point of it, and why the end-to-end test concentrates first.
        val concentrated = Resource(
            Form.Ore,
            Mixture.of(Species.Iron to 4100L, Species.Silica to 900L, Species.Copper to 500L),
        )
        val r = smelt(concentrated)
        assertEquals(Form.IronIngot, r.refined.form)
        assertEquals(r.refined.mass, r.refined.mixture[Species.Iron], "the ingot should be nothing but iron")
        assertEquals(2700L, r.refined.mass, "4100 iron less 1400 impurity")
    }

    @Test
    fun `impurities eat the product rather than dilute it`() {
        val clean = Resource(Form.Ore, Mixture.of(Species.Iron to 1000L, Species.Silica to 100L))
        val dirty = Resource(Form.Ore, Mixture.of(Species.Iron to 1000L, Species.Silica to 400L))
        assertEquals(900L, smelt(clean).refined.mass, "1000 iron less 100 impurity")
        assertEquals(600L, smelt(dirty).refined.mass, "1000 iron less 400 impurity")
    }

    @Test
    fun `ore with more impurity than metal smelts entirely to slag`() {
        // Iron is still the largest single mineral, but everything else together outweighs it.
        val junk = Resource(
            Form.Ore,
            Mixture.of(Species.Iron to 1000L, Species.Silica to 600L, Species.Copper to 600L),
        )
        val r = smelt(junk)
        assertTrue(r.refined.isEmpty, "expected no product, got ${r.refined}")
        assertEquals(Form.Slag, r.slag.form)
        assertEquals(junk.mass, r.slag.mass)
        assertConserved(listOf(junk.mixture), listOf(r.refined.mixture, r.slag.mixture), "smelt(junk)")
    }

    @Test
    fun `every mineral has somewhere to go when smelted`() {
        for (m in Species.ALL) {
            val pure = Resource(Form.Ore, Mixture.of(m to 1000L))
            val r = smelt(pure)
            assertEquals(SMELT_PRODUCTS.getValue(m), r.refined.form)
            assertEquals(1000L, r.refined.mass)
            assertTrue(r.slag.isEmpty)
        }
    }

    @Test
    fun `smelting something mostly fluid yields slag rather than an exception`() {
        val sludge = Resource(Form.Ore, Mixture.of(Species.Water to 900L, Species.Iron to 100L))
        val r = smelt(sludge)
        assertTrue(r.refined.isEmpty)
        assertEquals(sludge.mass, r.slag.mass)
        assertConserved(listOf(sludge.mixture), listOf(r.refined.mixture, r.slag.mixture), "smelt(sludge)")
    }

    // ── Mineral processing ─────────────────────────────────────────────────────

    @Test
    fun `processing conserves mass mineral by mineral`() {
        for (eff in intArrayOf(0, 250, 500, 750, 1000)) {
            val r = process(dirtyOre, eff)
            assertConserved(
                listOf(dirtyOre.mixture),
                listOf(r.product.mixture, r.tailings.mixture),
                "process(eff=$eff)",
            )
        }
    }

    @Test
    fun `processing makes the product purer than the input`() {
        val r = process(dirtyOre, 1000)
        val inputPurity = dirtyOre.mixture[Species.Iron].toDouble() / dirtyOre.mass
        val outputPurity = r.product.mixture[Species.Iron].toDouble() / r.product.mass
        assertTrue(outputPurity > inputPurity, "purity should rise: $inputPurity -> $outputPurity")
    }

    @Test
    fun `a perfect machine cannot beat the ore it is fed`() {
        // Efficiency is capped by purity, so a perfect machine and a machine matching the ore's own
        // purity produce identical output.
        val purityPermille = (dirtyOre.mixture[Species.Iron] * 1000L / dirtyOre.mass).toInt()
        assertEquals(process(dirtyOre, 1000).product, process(dirtyOre, purityPermille).product)
    }

    @Test
    fun `a worse machine yields a less pure product`() {
        val good = process(dirtyOre, 1000).product
        val bad = process(dirtyOre, 100).product
        val goodPurity = good.mixture[Species.Iron].toDouble() / good.mass
        val badPurity = bad.mixture[Species.Iron].toDouble() / bad.mass
        assertTrue(goodPurity > badPurity, "expected $goodPurity > $badPurity")
    }

    @Test
    fun `processing already-pure material just halves it`() {
        val pure = Resource(Form.Ore, Mixture.of(Species.Iron to 1000L))
        val r = process(pure, 1000)
        assertEquals(500L, r.product.mass)
        assertEquals(500L, r.tailings.mass)
    }

    @Test
    fun `processing empty input is a no-op rather than a crash`() {
        val r = process(Resource(Form.Ore, Mixture.EMPTY))
        assertTrue(r.product.isEmpty && r.tailings.isEmpty)
    }

    // ── The tree as a whole ────────────────────────────────────────────────────

    @Test
    fun `every form is reachable from ore`() {
        val reachable = mutableSetOf(Form.Ore, Form.Slag)
        reachable += SMELT_PRODUCTS.values
        val orphans = Form.ALL.filterNot { it in reachable }
        assertTrue(orphans.isEmpty(), "unreachable forms: $orphans")
    }
}
