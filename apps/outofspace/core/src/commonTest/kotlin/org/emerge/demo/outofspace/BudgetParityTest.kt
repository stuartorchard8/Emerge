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
        assertEquals(1_000_000L, Capacity.PACKET_GRAMS.grams, "a packet is one tonne")
        assertEquals(4L, MACHINE_BUFFER_CAP / Capacity.PACKET_GRAMS, "input buffer is four packets")
        assertEquals(4L, MACHINE_OUTPUT_CAP / Capacity.PACKET_GRAMS, "output buffer is four packets")
        assertEquals(20L, Storage.CAP / Capacity.PACKET_GRAMS, "storage is twenty packets")
        assertEquals(5L, Extractor.BUFFER_CAP / Capacity.PACKET_GRAMS, "extractor buffer is five packets")

        // ── Machine throughput, in grams per tick ──
        assertEquals(250_000L, Extractor(Direction.Right).gramsPerTick.grams, "extractor 250 kg/tick")
        assertEquals(125_000L, Smelter(Direction.Right).gramsPerTick.grams, "smelter 125 kg/tick")
        assertEquals(125_000L, Processor(Direction.Right).gramsPerTick.grams, "processor 125 kg/tick")
        assertEquals(125_000L, Vaporizer(Direction.Right).gramsPerTick.grams, "vaporizer 125 kg/tick")

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
