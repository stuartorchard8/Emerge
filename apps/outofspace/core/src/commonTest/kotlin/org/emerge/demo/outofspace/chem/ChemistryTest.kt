package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
            Mineral.Iron to 4100L,
            Mineral.Silica to 3000L,
            Mineral.Copper to 1800L,
            Mineral.Titanium to 1100L,
        ),
    )

    private fun assertConserved(inputs: List<Mixture>, outputs: List<Mixture>, what: String) {
        val delta = conservationOf(inputs, outputs)
        for (m in Mineral.ALL) {
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
    fun `apportion rejects a target larger than the total`() {
        assertFailsWith<IllegalArgumentException> { apportion(longArrayOf(1, 1, 0, 0, 0, 0, 0, 0), 3) }
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
        assertEquals(2050L, half[Mineral.Iron])
        assertEquals(1500L, half[Mineral.Silica])
        assertEquals(900L, half[Mineral.Copper])
        assertEquals(550L, half[Mineral.Titanium])
    }

    @Test
    fun `subtracting more than is present fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Mixture.of(Mineral.Iron to 10L) - Mixture.of(Mineral.Iron to 11L)
        }
    }

    @Test
    fun `dominant breaks ties by declaration order`() {
        val tied = Mixture.of(Mineral.Copper to 100L, Mineral.Iron to 100L)
        assertEquals(Mineral.Iron, tied.dominant, "Iron is declared before Copper")
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
            Mixture.of(Mineral.Iron to 4100L, Mineral.Silica to 900L, Mineral.Copper to 500L),
        )
        val r = smelt(concentrated)
        assertEquals(Form.IronIngot, r.refined.form)
        assertEquals(r.refined.mass, r.refined.mixture[Mineral.Iron], "the ingot should be nothing but iron")
        assertEquals(2700L, r.refined.mass, "4100 iron less 1400 impurity")
    }

    @Test
    fun `impurities eat the product rather than dilute it`() {
        val clean = Resource(Form.Ore, Mixture.of(Mineral.Iron to 1000L, Mineral.Silica to 100L))
        val dirty = Resource(Form.Ore, Mixture.of(Mineral.Iron to 1000L, Mineral.Silica to 400L))
        assertEquals(900L, smelt(clean).refined.mass, "1000 iron less 100 impurity")
        assertEquals(600L, smelt(dirty).refined.mass, "1000 iron less 400 impurity")
    }

    @Test
    fun `ore with more impurity than metal smelts entirely to slag`() {
        // Iron is still the largest single mineral, but everything else together outweighs it.
        val junk = Resource(
            Form.Ore,
            Mixture.of(Mineral.Iron to 1000L, Mineral.Silica to 600L, Mineral.Copper to 600L),
        )
        val r = smelt(junk)
        assertTrue(r.refined.isEmpty, "expected no product, got ${r.refined}")
        assertEquals(Form.Slag, r.slag.form)
        assertEquals(junk.mass, r.slag.mass)
        assertConserved(listOf(junk.mixture), listOf(r.refined.mixture, r.slag.mixture), "smelt(junk)")
    }

    @Test
    fun `every mineral has somewhere to go when smelted`() {
        for (m in Mineral.ALL) {
            val pure = Resource(Form.Ore, Mixture.of(m to 1000L))
            val r = smelt(pure)
            assertEquals(SMELT_PRODUCTS.getValue(m), r.refined.form)
            assertEquals(1000L, r.refined.mass)
            assertTrue(r.slag.isEmpty)
        }
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
        val inputPurity = dirtyOre.mixture[Mineral.Iron].toDouble() / dirtyOre.mass
        val outputPurity = r.product.mixture[Mineral.Iron].toDouble() / r.product.mass
        assertTrue(outputPurity > inputPurity, "purity should rise: $inputPurity -> $outputPurity")
    }

    @Test
    fun `a perfect machine cannot beat the ore it is fed`() {
        // Efficiency is capped by purity, so a perfect machine and a machine matching the ore's own
        // purity produce identical output.
        val purityPermille = (dirtyOre.mixture[Mineral.Iron] * 1000L / dirtyOre.mass).toInt()
        assertEquals(process(dirtyOre, 1000).product, process(dirtyOre, purityPermille).product)
    }

    @Test
    fun `a worse machine yields a less pure product`() {
        val good = process(dirtyOre, 1000).product
        val bad = process(dirtyOre, 100).product
        val goodPurity = good.mixture[Mineral.Iron].toDouble() / good.mass
        val badPurity = bad.mixture[Mineral.Iron].toDouble() / bad.mass
        assertTrue(goodPurity > badPurity, "expected $goodPurity > $badPurity")
    }

    @Test
    fun `processing already-pure material just halves it`() {
        val pure = Resource(Form.Ore, Mixture.of(Mineral.Iron to 1000L))
        val r = process(pure, 1000)
        assertEquals(500L, r.product.mass)
        assertEquals(500L, r.tailings.mass)
    }

    @Test
    fun `processing empty input is a no-op rather than a crash`() {
        val r = process(Resource(Form.Ore, Mixture.EMPTY))
        assertTrue(r.product.isEmpty && r.tailings.isEmpty)
    }

    // ── Crafting ───────────────────────────────────────────────────────────────

    @Test
    fun `crafting conserves mass and works in either input order`() {
        val iron = Resource(Form.IronIngot, Mixture.of(Mineral.Iron to 500L))
        val fiber = Resource(Form.CarbonFiber, Mixture.of(Mineral.Carbon to 300L))
        val steel = assertNotNull(craft(iron, fiber))
        assertEquals(Form.SteelAlloy, steel.form)
        assertConserved(listOf(iron.mixture, fiber.mixture), listOf(steel.mixture), "craft")
        assertEquals(steel, craft(fiber, iron), "input order must not matter")
    }

    @Test
    fun `crafting a non-recipe returns null`() {
        val iron = Resource(Form.IronIngot, Mixture.of(Mineral.Iron to 500L))
        assertNull(craft(iron, iron))
    }

    @Test
    fun `merging requires matching forms`() {
        val a = Resource(Form.IronIngot, Mixture.of(Mineral.Iron to 100L))
        val b = Resource(Form.IronIngot, Mixture.of(Mineral.Iron to 250L))
        assertEquals(350L, assertNotNull(merge(a, b)).mass)
        assertNull(merge(a, Resource(Form.CopperIngot, Mixture.of(Mineral.Copper to 1L))))
    }

    // ── The tree as a whole ────────────────────────────────────────────────────

    @Test
    fun `every form is reachable from ore`() {
        val reachable = mutableSetOf(Form.Ore, Form.Slag)
        reachable += SMELT_PRODUCTS.values
        var grew = true
        while (grew) {
            grew = false
            for ((output, inputs) in RECIPES) {
                if (output !in reachable && inputs.first in reachable && inputs.second in reachable) {
                    reachable += output
                    grew = true
                }
            }
        }
        val orphans = Form.ALL.filterNot { it in reachable }
        assertTrue(orphans.isEmpty(), "unreachable forms: $orphans")
    }

    @Test
    fun `no two recipes share the same pair of inputs`() {
        val seen = mutableMapOf<Set<Form>, Form>()
        for ((output, inputs) in RECIPES) {
            val key = setOf(inputs.first, inputs.second)
            val clash = seen.put(key, output)
            assertNull(clash, "$output and $clash are both made from $key")
        }
    }

    @Test
    fun `mine, process, smelt and craft a component without losing a gram`() {
        // The whole Phase 1 loop end to end, tracking every stream so nothing can hide.
        val mined = Resource(
            Form.Ore,
            Mixture.of(
                Mineral.Iron to 41_237L,   // deliberately not round, to exercise the remainders
                Mineral.Silica to 30_011L,
                Mineral.Copper to 18_503L,
                Mineral.Carbon to 11_249L,
            ),
        )
        val leftovers = mutableListOf<Mixture>()

        // Concentrate twice: tailings are set aside, not thrown away.
        val first = process(mined, 900)
        leftovers += first.tailings.mixture
        val second = process(first.product, 900)
        leftovers += second.tailings.mixture

        val smelted = smelt(second.product)
        leftovers += smelted.slag.mixture
        assertEquals(Form.IronIngot, smelted.refined.form)
        assertTrue(smelted.refined.mass > 0L, "twice-concentrated ore should smelt to something")

        // Feed it carbon fibre and make steel.
        val fiber = Resource(Form.CarbonFiber, Mixture.of(Mineral.Carbon to 5_000L))
        val steel = assertNotNull(craft(smelted.refined, fiber))

        assertConserved(
            inputs = listOf(mined.mixture, fiber.mixture),
            outputs = leftovers + steel.mixture,
            what = "the full ore-to-steel chain",
        )
    }
}
