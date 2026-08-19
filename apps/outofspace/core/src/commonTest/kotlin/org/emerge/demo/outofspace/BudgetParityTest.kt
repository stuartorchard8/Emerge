package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.chem.CRITICAL
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.TILE_LITRES
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.MACHINE_BUFFER_CAP
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Vaporizer
import org.emerge.demo.outofspace.world.millimolesOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every rescaled constant still means the same **physical** quantity.
 *
 * Step 2 of `PLAN_unit_rescale.md` rewrote a dozen bare literals as derivations from [Budget]. This
 * is the check that the rewrite was value-preserving, and it is deliberately written so that it
 * **survives step 8**: each assertion divides the constant by its unit, so it states a fact in grams
 * and joules rather than in whatever integer those currently come to.
 *
 * That is the difference between a pin and a guard. Asserting `PACKET_MASS == 1_000_000` would go
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

    /** Joules, likewise. */
    private val Long.joules: Long get() = this / Budget.JOULE

    @Test
    fun `every derived constant still means the quantity it meant before the audit`() {
        // ── Logistics: the packet is the quantum, everything else is a count of them ──
        assertEquals(100_000L, Capacity.PACKET_MASS.grams, "a packet is 100 kg")
        // Buffers are sized in TICKS OF THROUGHPUT, not in belt-loads — see MACHINE_BUFFER_CAP for
        // the bug that taught the difference. So they are asserted as masses, independent of packets.
        assertEquals(4_000_000L, MACHINE_BUFFER_CAP.grams, "input buffer is four tonnes")
        assertEquals(4_000_000L, MACHINE_OUTPUT_CAP.grams, "output buffer is four tonnes")
        assertEquals(20_000_000L, Storage.CAP.grams, "storage is twenty tonnes")
        assertEquals(5_000_000L, Extractor.BUFFER_CAP.grams, "extractor buffer is five tonnes")

        // The relationships that actually matter, stated as the ratios they are.
        assertEquals(40L, MACHINE_BUFFER_CAP / Vaporizer(TileIndex(0), Direction.Right).massPerTick, "40 ticks of buffer")
        // ⚠️ The extractor has no rate of its own any more — its two stores became one and the rail
        // sets its throughput — so its buffer is sized in belt-loads rather than in ticks.
        assertEquals(50L, Extractor.BUFFER_CAP / Capacity.PACKET_MASS, "fifty belt-loads of buffer")

        // ── Machine throughput ──
        //
        // ⚠️ THE structural invariant of the logistics layer: a belt tile holds one packet and a
        // machine hands over at most one per tick, so no producer may exceed one belt-load per tick
        // or it starves its own output. Asserted for every producer rather than for one, because
        // this is the property that broke when the belt-load shrank and it broke silently.
        // ⚠️ The extractor is absent because it no longer *has* a rate to exceed: it bites while it
        // has room and stops when it does not, so the belt is the only thing metering it. That is
        // this invariant satisfied structurally rather than by a number that has to agree.
        for ((what, rate) in listOf(
            "vaporizer" to Vaporizer(TileIndex(0), Direction.Right).massPerTick,
        )) {
            assertEquals(Capacity.PACKET_MASS, rate, "$what must produce exactly one belt-load a tick")
        }

        // ── Debug tools ──
        assertEquals(1_000L, Edit.INJECT_MASS.grams, "the injector delivers a kilogram a tick")

        // ── Energy-dimensioned, so measured against the energy unit and not the mass one ──
        assertEquals(20L, Material.AIR_FILM.joules, "the air film is 20 J/K/tick")

        // ── chem: the critical densities, audited in step 8 ──
        //
        // A tile at critical density holds `kg/m³ × TILE_LITRES` grams, because a cubic metre is a
        // thousand litres. Stated for every species on file rather than for one, since the miss
        // being guarded against was in the shared constructor and would take the whole table with it.
        for ((species, c) in CRITICAL) {
            val kgPerCubicMetre = when (species) {
                Species.Water -> 322
                Species.Nitrogen -> 313
                Species.Oxygen -> 436
                Species.CarbonDioxide -> 468
                Species.Argon -> 536
                else -> error("no critical density stated for $species")
            }
            assertEquals(kgPerCubicMetre * TILE_LITRES, c.massPerTile.grams, "critical $species")
        }
    }

    /**
     * A mole is a particle count, and the mass unit does not reach it.
     *
     * The one place in the game where [Budget.GRAM] is divided *out* instead of multiplied in — see
     * `MOLAR_DIVISOR`. Asserted against the species table rather than against a remembered figure,
     * so it states the conversion rather than its current value: a kilogram of nitrogen is
     * `1000/28` moles no matter what an integer of mass is worth. Without the divisor this comes out
     * a millionfold high at step 8's unit, and the pressure scale, `Negligible.MILLIMOLES` and
     * `MAX_REDUCED_PRESSURE` all move with it.
     */
    @Test
    fun `a mole is a particle count and does not move with the mass unit`() {
        for (species in Species.ALL) {
            val tile = MassArray(1)
            tile.data[species.ordinal] = Budget.KILOGRAM
            assertEquals(
                1_000L * 1_000L / species.molarMass,
                millimolesOf(tile, TileIndex(0)),
                "millimoles in a kilogram of $species",
            )
        }
    }

    /**
     * The mass/energy relation the capacity expressions depend on, now that it is *stated* rather
     * than enforced by holding the two units equal.
     *
     * `gasCapacityAt` reads `grams * specificHeat / CAPACITY_DIVISOR`, and that is only correct if
     * the divisor really is the ratio the units imply. Asserted from the two knobs rather than as a
     * remembered number, so it stays true when either moves — if it is ever wrong, every heat
     * capacity in the game is wrong by the same factor, quietly, and no other test in the suite
     * would attribute it to this cause.
     */
    @Test
    fun `the capacity divisor is exactly the ratio between the two units`() {
        // capacity = mass x c x u_mass / (1000 x u_energy), with u_mass in grams and u_energy in
        // joules. In micrograms and nanojoules the thousands and the 1e-6s cancel to this ratio.
        assertEquals(
            Budget.NANOJOULES_PER_UNIT / Budget.MICROGRAMS_PER_UNIT,
            Budget.CAPACITY_DIVISOR,
        )
        assertTrue(
            Budget.NANOJOULES_PER_UNIT % Budget.MICROGRAMS_PER_UNIT == 0L,
            "the energy unit must be a whole multiple of the mass unit, or the divisor truncates",
        )
        // A physical statement the two knobs have to keep agreeing on: warming a kilogram of water
        // by one kelvin costs 4182 joules, whatever either unit currently is.
        assertEquals(
            4_182L,
            Budget.KILOGRAM * Species.Water.specificHeat / Budget.CAPACITY_DIVISOR / Budget.JOULE,
            "a kilogram of water still costs 4182 J/K",
        )
    }
}
