package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Budget
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Extractor
import org.emerge.demo.outofspace.world.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.Processor
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Vaporizer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every rescaled constant still means the same **physical** quantity.
 *
 * Step 2 of `PLAN_unit_rescale.md` rewrote a dozen bare literals as derivations from [Budget]. This
 * is the check that the rewrite was value-preserving, and it is deliberately written so that it
 * **survives step 8**: each assertion divides the constant by its unit, so it states a fact in grams
 * and joules rather than in whatever integer those currently come to.
 *
 * That is the difference between a pin and a guard. Asserting `PACKET_GRAMS == 1_000_000` would go
 * red the moment the knob moves and would have to be re-baselined by hand — which is precisely the
 * kind of "moved expected value" that teaches people to edit tests instead of reading them. Asserting
 * that a packet is **one tonne of material** stays true at every mass scale, and goes red only if a
 * derivation is actually wrong.
 *
 * So at step 8 this file is the thing that says the rescale did not change the game.
 */
class BudgetParityTest {

    /** Grams, whatever a gram currently costs in integers. */
    private val Long.grams: Long get() = this / Budget.GRAM

    /** Millijoules, likewise. */
    private val Long.millijoules: Long get() = this / Budget.MILLIJOULE

    @Test
    fun `every derived constant still means the quantity it meant before the audit`() {
        // ── Logistics: the packet is the quantum, everything else is a count of them ──
        assertEquals(100_000L, Capacity.PACKET_GRAMS.grams, "a packet is 100 kg")
        // Buffers are sized in TICKS OF THROUGHPUT, not in belt-loads — see MACHINE_BUFFER_CAP for
        // the bug that taught the difference. So they are asserted as masses, independent of packets.
        assertEquals(4_000_000L, MACHINE_BUFFER_CAP.grams, "input buffer is four tonnes")
        assertEquals(4_000_000L, MACHINE_OUTPUT_CAP.grams, "output buffer is four tonnes")
        assertEquals(20_000_000L, Storage.CAP.grams, "storage is twenty tonnes")
        assertEquals(5_000_000L, Extractor.BUFFER_CAP.grams, "extractor buffer is five tonnes")

        // The relationships that actually matter, stated as the ratios they are.
        assertEquals(40L, MACHINE_BUFFER_CAP / Smelter(Direction.Right).gramsPerTick, "40 ticks of buffer")
        assertEquals(50L, Extractor.BUFFER_CAP / Extractor(Direction.Right).gramsPerTick, "50 ticks of buffer")

        // ── Machine throughput ──
        //
        // ⚠️ THE structural invariant of the logistics layer: a belt tile holds one packet and a
        // machine hands over at most one per tick, so no producer may exceed one belt-load per tick
        // or it starves its own output. Asserted for every producer rather than for one, because
        // this is the property that broke when the belt-load shrank and it broke silently.
        for ((what, rate) in listOf(
            "extractor" to Extractor(Direction.Right).gramsPerTick,
            "smelter" to Smelter(Direction.Right).gramsPerTick,
            "processor" to Processor(Direction.Right).gramsPerTick,
            "vaporizer" to Vaporizer(Direction.Right).gramsPerTick,
        )) {
            assertEquals(Capacity.PACKET_GRAMS, rate, "$what must produce exactly one belt-load a tick")
        }

        // ── Debug tools ──
        assertEquals(1_000L, Edit.INJECT_GRAMS.grams, "the injector delivers a kilogram a tick")

        // ── Energy-dimensioned, so measured against the energy unit and not the mass one ──
        assertEquals(20_000L, Material.AIR_FILM.millijoules, "the air film is 20 J/K/tick")
    }

    /**
     * The mass/energy relation the capacity expressions silently depend on.
     *
     * `gasCapacityAt` reads `grams * specificHeat` with no conversion constant, which is only correct
     * because specific heat is per-kilogram and [Budget.ENERGY_PER_MASS] is 1000. If that relation is
     * ever broken, every heat capacity in the game becomes wrong by the ratio — quietly, and in a way
     * no other test in the suite would attribute to this cause.
     */
    @Test
    fun `the energy unit stays a thousandth of the mass unit`() {
        assertEquals(1_000L, Budget.ENERGY_PER_MASS)
        assertEquals(Budget.GRAM * 1_000L, Budget.JOULE)
    }
}
