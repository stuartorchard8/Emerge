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

/**
 * Logistics primitives: packets, capacity, and the rate carry. Conservation still rules.
 *
 * ⚠️ Every quantity that depends on how big a packet is is written as a **fraction of [cap]** rather
 * than as a literal. Belt-load size is a tuning dial — it went from a tonne to 100 kg on 2026-08-12 —
 * and it is also about to move again when `PLAN_unit_rescale.md` changes what an integer means. A
 * literal here pins neither the behaviour being tested nor anything a reader can check; it just goes
 * red and gets re-baselined by hand, which is how a suite stops being evidence. `cap * 41 / 100` says
 * what the assertion is actually about: a packet is 41% iron because the pile is.
 */
class PacketTest {

    /** What a packet holds. Every fraction below is of this, so the dial can move freely. */
    private val cap = Capacity.PACKET_MASS

    // A hundred packets' worth, split by percent, so "take one packet and leave the rest" has a rest
    // to leave. Written as multiples of [cap] rather than as a mass: these were once round numbers of
    // mass, which quietly became a tenth of a packet when the mass unit moved and turned every
    // "takes a whole packet" assertion into "takes the whole pile".
    private val orePile = Resource(
        Form.Ore,
        Mixture.of(
            Species.Iron to 41L * cap,
            Species.Silica to 30L * cap,
            Species.Copper to 18L * cap,
            Species.Titanium to 11L * cap,
        ),
    )

    private val atmosphere = Mixture.of(
        Species.Nitrogen to 78L * cap,
        Species.Oxygen to 21L * cap,
        Species.CarbonDioxide to 1L * cap,
    )

    /**
     * Equal to within [ppb] parts per billion — the resolution a proportional split is actually
     * promised at, and no better.
     *
     * ### Why a sample is not exact
     *
     * [org.emerge.demo.outofspace.chem.apportion] splits through `scaledRatio(cumulative, sum,
     * target)`, and `scaledRatio` reduces a fraction it cannot multiply out by **shifting both terms
     * right** until `d <= Long.MAX_VALUE / scale`. A ten-tonne ore pile packed into hundred-kilogram
     * packets is `sum = 1e13` against `scale = 1e11`: the ceiling is about 9.2e7, so seventeen bits
     * leave the numerator and the iron share lands 590 ppb light.
     *
     * That is the contract working, not failing. `scaledRatio` promises **monotonic** and **exact at
     * the ends**, and both still hold — the packet totals exactly one capacity and
     * [assertConserved] still balances to the last unit. What it does not promise is exactness in the
     * middle, and at a microgram per integer the middle is where a real pile now sits. Shrinking the
     * fixture would not help: any pile at least one packet big has `d >= scale`, which is already
     * three orders past the ceiling.
     *
     * A part per million is 0.1 g on a 100 kg packet. Nothing in the game can see it; leaving the
     * assertion exact would only mean nobody could see this note either.
     */
    private fun assertNear(expected: Long, actual: Long, ppb: Long, what: String) {
        val slack = expected / 1_000_000_000L * ppb + 1L
        val off = if (actual > expected) actual - expected else expected - actual
        assertTrue(off <= slack, "$what: expected $expected, was $actual — off by $off, allowed $slack")
    }

    private fun assertConserved(inputs: List<Mixture>, outputs: List<Mixture>, what: String) {
        val delta = conservationOf(inputs, outputs)
        for (s in Species.ALL) {
            assertEquals(0L, delta[s.ordinal], "$what did not conserve ${s.name}")
        }
    }

    // ── Packing ────────────────────────────────────────────────────────────────

    @Test
    fun `packing takes at most one packet's worth and leaves the rest`() {
        val (packet, left) = packSolid(orePile)
        assertEquals(cap, assertNotNull(packet).mass)
        assertEquals(orePile.mass - cap, left.mass)
        assertConserved(listOf(orePile.mixture), listOf(packet.contents, left.mixture), "packSolid")
    }

    @Test
    fun `a packet is a proportional sample, not the good bits skimmed off`() {
        val (packet, _) = packSolid(orePile)
        val p = assertNotNull(packet).contents
        // The pile is 41% iron; so is the packet, to a part in a million — see [assertNear].
        assertNear(cap * 41 / 100, p[Species.Iron], ppb = 1_000, what = "iron")
        assertNear(cap * 30 / 100, p[Species.Silica], ppb = 1_000, what = "silica")
        assertNear(cap * 18 / 100, p[Species.Copper], ppb = 1_000, what = "copper")
        assertNear(cap * 11 / 100, p[Species.Titanium], ppb = 1_000, what = "titanium")
        // Whatever the shares round to, they still add up to exactly one packet.
        assertEquals(cap, p.total)
    }

    @Test
    fun `packing an empty source yields no packet rather than an empty one`() {
        val (packet, left) = packSolid(Resource(Form.Ore, Mixture.EMPTY))
        assertNull(packet)
        assertTrue(left.isEmpty)
    }

    @Test
    fun `packing a source smaller than a packet takes all of it`() {
        val crumbs = Resource(Form.IronIngot, Mixture.of(Species.Iron to cap / 4))
        val (packet, left) = packSolid(crumbs)
        assertEquals(cap / 4, assertNotNull(packet).mass)
        assertTrue(left.isEmpty)
    }

    @Test
    fun `fluid packing works the same way and conserves`() {
        val (packet, left) = packFluid(atmosphere)
        assertEquals(cap, assertNotNull(packet).mass)
        assertConserved(listOf(atmosphere), listOf(packet.contents, left), "packFluid")
        // Composition of the slug matches the source: 78% nitrogen.
        assertNear(cap * 78 / 100, packet.contents[Species.Nitrogen], ppb = 1_000, what = "nitrogen")
    }

    // ── Merging ────────────────────────────────────────────────────────────────

    @Test
    fun `merging same-form solids fills to capacity and rejects the overflow`() {
        val a = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap * 8 / 10)))
        val b = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap * 5 / 10)))
        val r = assertNotNull(mergeInto(a, b))
        assertEquals(cap, r.merged.mass)
        assertEquals(cap * 3 / 10, assertNotNull(r.rejected).mass)
        assertConserved(listOf(a.contents, b.contents), listOf(r.merged.contents, r.rejected.contents), "merge")
    }

    @Test
    fun `merging leaves no rejection when everything fits`() {
        val a = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap * 3 / 10)))
        val b = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap * 4 / 10)))
        val r = assertNotNull(mergeInto(a, b))
        assertEquals(cap * 7 / 10, r.merged.mass)
        assertNull(r.rejected)
    }

    @Test
    fun `different solid forms do not merge`() {
        val ingot = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 100_000L)))
        val frame = SolidPacket(Resource(Form.SiliconCrystal, Mixture.of(Species.Iron to 100_000L)))
        assertNull(mergeInto(ingot, frame), "an ingot cannot be poured into a structural frame")
    }

    @Test
    fun `fluids always merge into an amalgam`() {
        val air = FluidPacket(Mixture.of(Species.Oxygen to cap * 4 / 10))
        val steam = FluidPacket(Mixture.of(Species.Water to cap * 3 / 10))
        val r = assertNotNull(mergeInto(air, steam))
        assertEquals(cap * 7 / 10, r.merged.mass)
        assertEquals(cap * 4 / 10, r.merged.contents[Species.Oxygen])
        assertEquals(cap * 3 / 10, r.merged.contents[Species.Water])
    }

    @Test
    fun `a solid and a fluid never merge`() {
        val solid = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 100_000L)))
        val fluid = FluidPacket(Mixture.of(Species.Oxygen to 100_000L))
        assertNull(mergeInto(solid, fluid))
        assertNull(mergeInto(fluid, solid))
    }

    @Test
    fun `merging into a full packet rejects everything`() {
        val full = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap)))
        val more = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap / 4)))
        val r = assertNotNull(mergeInto(full, more))
        assertEquals(cap, r.merged.mass)
        assertEquals(cap / 4, assertNotNull(r.rejected).mass)
    }

    // ── Rates: the place a float would otherwise sneak in ──────────────────────
    //
    // A machine's rate is whole mass per tick, so the clock cannot make it fractional. A *throttle*
    // can: these cover the one remaining division.

    @Test
    fun `a throttled machine delivers its exact share, not a rounded one`() {
        var carry = 0L
        var delivered = 0L
        // 125 g/tick at 45% is 56.25 g — a quarter gram a tick that must not be dropped.
        repeat(4) {
            val (mass, next) = Rate.tick(numerator = 125_000L * 450, denominator = 1_000, carry = carry)
            delivered += mass
            carry = next
        }
        assertEquals(225_000L, delivered, "56.25 g/tick must come to exactly 225 g over four ticks")
    }

    @Test
    fun `throttle carry stays exact over a long run`() {
        var carry = 0L
        var delivered = 0L
        val ticks = 3_600
        repeat(ticks) {
            val (mass, next) = Rate.tick(125_000L * 450, 1_000, carry)
            delivered += mass
            carry = next
        }
        assertEquals(125_000L * 450 * ticks / 1_000, delivered, "a long run is exact, not about right")
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
        val (mass, carry) = Rate.tick(125_000L * 1_000, 1_000, carry = 0L)
        assertEquals(125_000L, mass)
        assertEquals(0L, carry, "an unthrottled machine has no fraction to remember")
    }

    @Test
    fun `a zero rate delivers nothing and never accumulates`() {
        val (mass, carry) = Rate.tick(0L, 60, 0L)
        assertEquals(0L, mass)
        assertEquals(0L, carry)
    }

    @Test
    fun `rate rejects nonsense`() {
        assertFailsWith<IllegalArgumentException> { Rate.tick(-1_000L, 60, 0L) }
        assertFailsWith<IllegalArgumentException> { Rate.tick(100_000L, 0, 0L) }
    }

    // ── Capacity indirection ───────────────────────────────────────────────────

    @Test
    fun `capacity is measured through quantityOf so the volume switch has one home`() {
        val packet = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap / 4)))
        assertEquals(cap / 4, Capacity.quantityOf(packet))
        assertEquals(cap * 3 / 4, Capacity.headroom(packet))
        assertEquals(0L, Capacity.headroom(SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to cap * 12 / 10)))))
    }
}
