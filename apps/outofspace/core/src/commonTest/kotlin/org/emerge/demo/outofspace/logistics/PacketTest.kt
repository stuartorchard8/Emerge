package org.emerge.demo.outofspace.logistics

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.conservationOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logistics primitives: packets, capacity, and the rate carry. Conservation still rules. */
class PacketTest {

    private val orePile = Resource(
        Form.Ore,
        Mixture.of(
            Species.Iron to 4_100L,
            Species.Silica to 3_000L,
            Species.Copper to 1_800L,
            Species.Titanium to 1_100L,
        ),
    )

    private val atmosphere = Mixture.of(
        Species.Nitrogen to 7_800L,
        Species.Oxygen to 2_100L,
        Species.CarbonDioxide to 100L,
    )

    private fun assertConserved(inputs: List<Mixture>, outputs: List<Mixture>, what: String) {
        val delta = conservationOf(inputs, outputs)
        for (s in Species.ALL) {
            assertEquals(0L, delta[s.ordinal], "$what did not conserve ${s.name}")
        }
    }

    // ── Phase discipline ───────────────────────────────────────────────────────

    @Test
    fun `a solid packet refuses fluids and a fluid packet refuses solids`() {
        assertFailsWith<IllegalArgumentException> {
            SolidPacket(Resource(Form.Ore, Mixture.of(Species.Water to 10L)))
        }
        assertFailsWith<IllegalArgumentException> {
            FluidPacket(Mixture.of(Species.Iron to 10L))
        }
    }

    @Test
    fun `an empty mixture is acceptable to either network`() {
        assertTrue(Mixture.EMPTY.isAllSolid && Mixture.EMPTY.isAllFluid)
    }

    @Test
    fun `liquids and gases are both fluids and share the pipe network`() {
        assertTrue(Species.Water.isFluid && Species.Oxygen.isFluid)
        // A pipe carries either, and a packet may hold both at once.
        val mixed = FluidPacket(Mixture.of(Species.Water to 500L, Species.Oxygen to 500L))
        assertEquals(1_000L, mixed.mass)
    }

    // ── Packing ────────────────────────────────────────────────────────────────

    @Test
    fun `packing takes at most one packet's worth and leaves the rest`() {
        val (packet, left) = packSolid(orePile)
        assertEquals(1_000L, assertNotNull(packet).mass)
        assertEquals(orePile.mass - 1_000L, left.mass)
        assertConserved(listOf(orePile.mixture), listOf(packet.contents, left.mixture), "packSolid")
    }

    @Test
    fun `a packet is a proportional sample, not the good bits skimmed off`() {
        val (packet, _) = packSolid(orePile)
        val p = assertNotNull(packet).contents
        // The pile is 41% iron; so is the packet, to the nearest gram.
        assertEquals(410L, p[Species.Iron])
        assertEquals(300L, p[Species.Silica])
        assertEquals(180L, p[Species.Copper])
        assertEquals(110L, p[Species.Titanium])
    }

    @Test
    fun `packing an empty source yields no packet rather than an empty one`() {
        val (packet, left) = packSolid(Resource(Form.Ore, Mixture.EMPTY))
        assertNull(packet)
        assertTrue(left.isEmpty)
    }

    @Test
    fun `packing a source smaller than a packet takes all of it`() {
        val crumbs = Resource(Form.IronIngot, Mixture.of(Species.Iron to 250L))
        val (packet, left) = packSolid(crumbs)
        assertEquals(250L, assertNotNull(packet).mass)
        assertTrue(left.isEmpty)
    }

    @Test
    fun `fluid packing works the same way and conserves`() {
        val (packet, left) = packFluid(atmosphere)
        assertEquals(1_000L, assertNotNull(packet).mass)
        assertConserved(listOf(atmosphere), listOf(packet.contents, left), "packFluid")
        // Composition of the slug matches the source: 78% nitrogen.
        assertEquals(780L, packet.contents[Species.Nitrogen])
    }

    // ── Merging ────────────────────────────────────────────────────────────────

    @Test
    fun `merging same-form solids fills to capacity and rejects the overflow`() {
        val a = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 800L)))
        val b = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 500L)))
        val r = assertNotNull(mergeInto(a, b))
        assertEquals(1_000L, r.merged.mass)
        assertEquals(300L, assertNotNull(r.rejected).mass)
        assertConserved(listOf(a.contents, b.contents), listOf(r.merged.contents, r.rejected.contents), "merge")
    }

    @Test
    fun `merging leaves no rejection when everything fits`() {
        val a = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 300L)))
        val b = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 400L)))
        val r = assertNotNull(mergeInto(a, b))
        assertEquals(700L, r.merged.mass)
        assertNull(r.rejected)
    }

    @Test
    fun `different solid forms do not merge`() {
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 100L)))
        val frame = SolidPacket(Resource(Form.SiliconCrystal, Mixture.of(Species.Iron to 100L)))
        assertNull(mergeInto(ingot, frame), "an ingot cannot be poured into a structural frame")
    }

    @Test
    fun `fluids always merge into an amalgam`() {
        val air = FluidPacket(Mixture.of(Species.Oxygen to 400L))
        val steam = FluidPacket(Mixture.of(Species.Water to 300L))
        val r = assertNotNull(mergeInto(air, steam))
        assertEquals(700L, r.merged.mass)
        assertEquals(400L, r.merged.contents[Species.Oxygen])
        assertEquals(300L, r.merged.contents[Species.Water])
    }

    @Test
    fun `a solid and a fluid never merge`() {
        val solid = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 100L)))
        val fluid = FluidPacket(Mixture.of(Species.Oxygen to 100L))
        assertNull(mergeInto(solid, fluid))
        assertNull(mergeInto(fluid, solid))
    }

    @Test
    fun `merging into a full packet rejects everything`() {
        val full = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_000L)))
        val more = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 250L)))
        val r = assertNotNull(mergeInto(full, more))
        assertEquals(1_000L, r.merged.mass)
        assertEquals(250L, assertNotNull(r.rejected).mass)
    }

    // ── Rates: the place a float would otherwise sneak in ──────────────────────
    //
    // A machine's rate is whole grams per tick, so the clock cannot make it fractional. A *throttle*
    // can: these cover the one remaining division.

    @Test
    fun `a throttled machine delivers its exact share, not a rounded one`() {
        var carry = 0L
        var delivered = 0L
        // 125 g/tick at 45% is 56.25 g — a quarter gram a tick that must not be dropped.
        repeat(4) {
            val (grams, next) = Rate.tick(numerator = 125L * 450, denominator = 1_000, carry = carry)
            delivered += grams
            carry = next
        }
        assertEquals(225L, delivered, "56.25 g/tick must come to exactly 225 g over four ticks")
    }

    @Test
    fun `throttle carry stays exact over a long run`() {
        var carry = 0L
        var delivered = 0L
        val ticks = 3_600
        repeat(ticks) {
            val (grams, next) = Rate.tick(125L * 450, 1_000, carry)
            delivered += grams
            carry = next
        }
        assertEquals(125L * 450 * ticks / 1_000, delivered, "a long run is exact, not about right")
    }

    @Test
    fun `awkward rates and throttles stay exact`() {
        for (rate in longArrayOf(1L, 7L, 333L, 1_000L, 20_001L)) {
            for (activation in intArrayOf(1, 7, 333, 500, 999, 1_000)) {
                var carry = 0L
                var delivered = 0L
                repeat(1_000) {
                    val (g, next) = Rate.tick(rate * activation, 1_000, carry)
                    delivered += g
                    carry = next
                }
                assertEquals(rate * activation, delivered, "rate=$rate activation=$activation")
            }
        }
    }

    @Test
    fun `full activation needs no carry at all`() {
        val (grams, carry) = Rate.tick(125L * 1_000, 1_000, carry = 0L)
        assertEquals(125L, grams)
        assertEquals(0L, carry, "an unthrottled machine has no fraction to remember")
    }

    @Test
    fun `a zero rate delivers nothing and never accumulates`() {
        val (grams, carry) = Rate.tick(0L, 60, 0L)
        assertEquals(0L, grams)
        assertEquals(0L, carry)
    }

    @Test
    fun `rate rejects nonsense`() {
        assertFailsWith<IllegalArgumentException> { Rate.tick(-1L, 60, 0L) }
        assertFailsWith<IllegalArgumentException> { Rate.tick(100L, 0, 0L) }
    }

    // ── Capacity indirection ───────────────────────────────────────────────────

    @Test
    fun `capacity is measured through quantityOf so the volume switch has one home`() {
        val packet = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 250L)))
        assertEquals(250L, Capacity.quantityOf(packet))
        assertEquals(750L, Capacity.headroom(packet))
        assertEquals(0L, Capacity.headroom(SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1_200L)))))
    }
}
