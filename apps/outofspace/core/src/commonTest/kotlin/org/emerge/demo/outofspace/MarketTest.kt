package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Market
import org.emerge.demo.outofspace.world.Prices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a counterparty will actually pay — `PLAN_economy.md` §3.3, §3.5 and §3.6.
 *
 * Three claims, and the milestone rests on all three: **purity pays**, **the market moves against
 * whoever trades with it**, and **a round trip cannot make money**.
 */
class MarketTest {

    private val tonne = Budget.TONNE
    private fun kg(n: Long) = n * Budget.KILOGRAM

    /** A lump of [total] mass that is [permille] one species and the rest another. */
    private fun ore(dominant: Species, permille: Long, other: Species, total: Long): Mixture {
        val head = total * permille / 1_000L
        return Mixture.of(dominant to head, other to (total - head), energy = 0L)
    }

    /**
     * What a rock actually crushes into: a dominant species at [permille], and a gangue spread
     * **50/30/20 across three others**.
     *
     * ⚠️ **The number of species in the gangue is what decides how strong the incentive to
     * concentrate is**, and it caught a test asserting the wrong scenario. With only one impurity
     * the "second species" is huge and its share² still pays well, so concentrating is worth ~3.7×.
     * Spread the same impurity across three and the second species is small, everything past it is
     * forfeit, and it is worth ~10×. Real extractor output is many species, so this is the shape the
     * plan's §3.6 table was measured against and the shape the economy is tuned for.
     */
    private fun rockOre(dominant: Species, permille: Long, total: Long): Mixture {
        val head = total * permille / 1_000L
        val rest = total - head
        return Mixture.of(
            dominant to head,
            Species.Forsterite to rest * 50 / 100,
            Species.Enstatite to rest * 30 / 100,
            Species.Troilite to rest - rest * 50 / 100 - rest * 30 / 100,
            energy = 0L,
        )
    }

    // ── §3.3 the stock curve ──

    @Test
    fun `an empty shelf quotes list and a full one quotes less`() {
        val empty = Market.empty()
        assertEquals(Prices.listPrice(Species.Iron), empty.price(Species.Iron))

        val stocked = Market.of(Species.Iron to Market.REFERENCE_STOCK)
        // K/(K+K) is exactly half.
        assertEquals(Prices.listPrice(Species.Iron) / 2, stocked.price(Species.Iron))

        val glutted = Market.of(Species.Iron to 1_000L * tonne)
        assertTrue(glutted.price(Species.Iron) < stocked.price(Species.Iron) / 10)
        assertTrue(glutted.price(Species.Iron) >= Market.FLOOR_PRICE, "a price fell to zero")
    }

    @Test
    fun `selling depresses a price and buying raises it`() {
        val before = Market.of(Species.Iron to 20L * tonne)
        val after = before.absorbing(Species.Iron, 20L * tonne)
        assertTrue(after.price(Species.Iron) < before.price(Species.Iron))

        val drained = before.releasing(Species.Iron, 10L * tonne)
        assertTrue(drained.price(Species.Iron) > before.price(Species.Iron))
    }

    @Test
    fun `two stations holding different stock quote different numbers`() {
        val ironRich = Market.of(Species.Iron to 50L * tonne)
        val ironPoor = Market.of(Species.Titanium to 50L * tonne)
        assertTrue(ironPoor.price(Species.Iron) > ironRich.price(Species.Iron) * 4)
    }

    // ── §3.5 the arbitrage tripwire ──

    @Test
    fun `a round trip at one station loses money, buy first`() {
        // ⛔ The single most important test in the economy. A price curve driven by stock is an
        // arbitrage machine unless a trade is quoted against the stock it LEAVES BEHIND. Sizes span
        // from a crumb to most of the shelf, because a spread alone holds only for small trades.
        val market = Market.of(Species.Iron to 100L * tonne)
        for (mass in listOf(kg(1), kg(100), 10L * tonne, 50L * tonne, 99L * tonne)) {
            val cost = market.buyCost(Species.Iron, mass)
            val holding = market.releasing(Species.Iron, mass)
            val revenue = holding.sellValue(Mixture.of(Species.Iron to mass, energy = 0L))
            // ⚠️ `>=`, not `>`. A kilogram of iron at a station already holding a hundred tonnes is
            // worth 0.8 credits, and both sides truncate to nothing — a wash, which is exactly what
            // the invariant is for. The strict inequality is asserted wherever there is a credit in
            // it at all. Asserting `>` unconditionally was the test being wrong about the units.
            assertTrue(revenue <= cost, "buying $mass cost $cost and sold back for $revenue")
            if (revenue > 0L) assertTrue(revenue < cost, "buying $mass cost $cost and sold back for $revenue")
        }
    }

    @Test
    fun `a round trip at one station loses money, sell first`() {
        val market = Market.of(Species.Iron to 100L * tonne)
        for (mass in listOf(kg(1), kg(100), 10L * tonne, 50L * tonne, 99L * tonne)) {
            val lump = Mixture.of(Species.Iron to mass, energy = 0L)
            val revenue = market.sellValue(lump)
            val holding = market.absorbing(lump)
            val cost = holding.buyCost(Species.Iron, mass)
            assertTrue(cost >= revenue, "selling $mass paid $revenue and buying back cost $cost")
            if (revenue > 0L) assertTrue(cost > revenue, "selling $mass paid $revenue and buying back cost $cost")
        }
    }

    @Test
    fun `the spread is a margin and not the safety property`() {
        // The guarantee above must survive the spread being turned off entirely — it comes from
        // pricing at the post-trade shelf, not from the margin. Proven by exercising the same
        // inequality on the raw curve, which is what a zero spread reduces to.
        val market = Market.of(Species.Iron to 100L * tonne)
        val mass = 60L * tonne
        val askShelf = market.priceAt(Species.Iron, 100L * tonne - mass)
        val bidShelf = market.priceAt(Species.Iron, 100L * tonne)
        assertTrue(askShelf > bidShelf, "the raw curve does not defend the round trip")
    }

    // ── §3.6 purity ──

    @Test
    fun `a pure lump fetches full rate`() {
        val market = Market.empty()
        val mass = kg(100)
        val lump = Mixture.of(Species.Iron to mass, energy = 0L)
        // Full bid on the whole mass: the share is one, so the square is one. The bid sits one
        // spread under list, and the shelf it lands on is small against REFERENCE_STOCK.
        assertEquals(market.bidFor(Species.Iron, mass), market.sellValue(lump))
    }

    @Test
    fun `a fifty-fifty blend pays each species a quarter rate`() {
        // ⚠️ **Squaring the share does NOT reproduce Stu's stated "top two, each at half"** — at
        // 50/50 it pays each a *quarter*, so the blend fetches a quarter of what the two separated
        // piles do rather than a half. That is the deliberate consequence of choosing R2 over R1
        // from `PLAN_economy.md` §3.6's table, and it is the extra bite Stu asked for after seeing
        // R1's 3.92× called too soft. Written down here because "each at half the going rate" is
        // still the sentence the feature started from.
        val market = Market.empty()
        val total = kg(200)
        val lump = ore(Species.Iron, 500, Species.Nickel, total)
        val separated = market.sellValue(Mixture.of(Species.Iron to total / 2, energy = 0L)) +
            market.sellValue(Mixture.of(Species.Nickel to total / 2, energy = 0L))
        val mixed = market.sellValue(lump)
        assertTrue(
            mixed * 4 in (separated - separated / 50)..(separated + separated / 50),
            "blend $mixed is not a quarter of separated $separated",
        )
    }

    @Test
    fun `purity pays, all the way up the concentrator ladder`() {
        val market = Market.empty()
        val total = tonne
        val ladder = listOf(410L, 650L, 860L, 940L, 970L, 1_000L)
        val takes = ladder.map { market.sellValue(rockOre(Species.Iron, it, total)) }
        assertEquals(takes.sorted(), takes, "value does not rise with purity: $takes")
        // §3.6's measured incentive: extractor output to fully concentrated is worth about 10x.
        val gain = takes.last().toDouble() / takes.first()
        assertTrue(gain > 8.0, "concentrating is only worth ${gain}x; the incentive has gone soft")
    }

    @Test
    fun `the gain front-loads`() {
        // The first concentrator rung must be worth far more than the last, because the last is
        // already paid for by BUILD_PURITY_PERCENT being 100 and should not be bought twice.
        val market = Market.empty()
        val total = tonne
        fun take(permille: Long) = market.sellValue(rockOre(Species.Iron, permille, total))
        // ⚠️ Compare the **gains**, not the multipliers. §3.6 measured +196% on the first rung and
        // +10% on the last — a factor of twenty between the gains, but only 2.7 between the
        // multipliers they are quoted as. The first draft of this assertion confused the two.
        val firstGain = take(650).toDouble() / take(410) - 1.0
        val lastGain = take(1_000).toDouble() / take(970) - 1.0
        assertTrue(firstGain > lastGain * 5, "first rung +${firstGain * 100}%, last rung +${lastGain * 100}%")
    }

    @Test
    fun `a single impurity is far cheaper to carry than three`() {
        // The other half of `rockOre`'s note, pinned from the front: identical purity, different
        // gangue. One impurity keeps most of its value because it is itself a top-two species; three
        // push everything past the second into the forfeit tail. Ore that is dirty in MANY ways is
        // what the concentrator is really for.
        val market = Market.empty()
        val onePartner = market.sellValue(ore(Species.Iron, 410, Species.Forsterite, tonne))
        val threePartners = market.sellValue(rockOre(Species.Iron, 410, tonne))
        assertTrue(
            onePartner > threePartners * 2,
            "one impurity paid $onePartner, three paid $threePartners",
        )
    }

    @Test
    fun `the tail beyond the top two species is forfeit`() {
        val market = Market.empty()
        // Same dominant pair, but one lump carries a third species instead of more of the second.
        val twoWay = Mixture.of(Species.Iron to kg(60), Species.Nickel to kg(40), energy = 0L)
        val threeWay = Mixture.of(
            Species.Iron to kg(60), Species.Nickel to kg(30), Species.Gold to kg(10), energy = 0L,
        )
        // The gold is worth a fortune at list and the station pays nothing at all for it.
        assertTrue(Prices.listValue(Species.Gold, kg(10)) > market.sellValue(twoWay) * 100)
        assertTrue(
            market.sellValue(threeWay) < market.sellValue(twoWay),
            "a lump carrying forfeit gold paid more than one without it",
        )
    }

    @Test
    fun `an empty lump is worth nothing and costs nothing`() {
        val market = Market.empty()
        assertEquals(0L, market.sellValue(Mixture.EMPTY))
        assertEquals(0L, market.buyCost(Species.Iron, 0L))
    }

    @Test
    fun `a market will not go short`() {
        val market = Market.of(Species.Iron to kg(10))
        assertTrue(market.canSupply(Species.Iron, kg(10)))
        assertTrue(!market.canSupply(Species.Iron, kg(11)))
    }
}
