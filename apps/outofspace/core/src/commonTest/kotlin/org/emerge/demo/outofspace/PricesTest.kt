package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Prices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The price table is **derived, not authored** — `PLAN_economy.md` §3.1 — so these pin the
 * derivation rather than the numbers it happens to produce today.
 *
 * ⚠️ Every expected value here was computed independently, in Python, against the same two tables
 * before a line of `Prices.kt` existed. A disagreement means the transcription is wrong, not that
 * the expectation wants moving — see `feedback_ask_before_chasing_test_numbers`.
 */
class PricesTest {

    @Test
    fun `every species has a price`() {
        // The guard in `elementPrice` treats an unsourced element as the rarest thing there is. It
        // must stay unreachable: an orphaned element would be silently repriced, not reported.
        val unpriced = Species.ALL.filter { Prices.listPrice(it) <= 0L }
        assertTrue(unpriced.isEmpty(), "these species have no price: $unpriced")
    }

    @Test
    fun `the lanthanide suite is not a source, and its members price at the ceiling`() {
        // ⚠️ **A real finding about the chemistry table, not about the economy.** `MINERALS` writes
        // monazite, bastnasite and xenotime with a **single representative lanthanide** (cerium or
        // yttrium) so their molar masses stay exact; the true site occupancy lives in a separate
        // `LANTHANIDE_SUITE`, which is a distribution a *refining step* is meant to read and which
        // nothing reads today. So thirteen lanthanides occur in no rock this derivation can see.
        //
        // They are consequently unobtainable, and `elementPrice`'s floor prices them as the rarest
        // thing there can be. Harmless — no mineral contains them, so no *compound* price moves —
        // but it means the dearest entries in the table are things nobody can ever sell. Pinned here
        // so that a future chemistry change which sources them shows up as a failure here rather
        // than as a silent repricing.
        val orphans = Species.ALL.filter { compositionOf(it).isEmpty() && Prices.abundance(it) <= 0L }
        assertEquals(13, orphans.size, "the set of unsourced elements moved: $orphans")
        assertTrue(orphans.contains(Species.Lanthanum) && orphans.contains(Species.Neodymium))
        // Cerium and yttrium ARE the representatives, so they must be sourced.
        assertTrue(Prices.abundance(Species.Cerium) > 0L)
        assertTrue(Prices.abundance(Species.Yttrium) > 0L)
    }

    @Test
    fun `iron is the peg and costs exactly its stated price`() {
        assertEquals(Prices.IRON_PRICE, Prices.listPrice(Species.Iron))
        // 100 kg of iron is one price unit, so it fetches the peg exactly.
        assertEquals(Prices.IRON_PRICE, Prices.listValue(Species.Iron, 100L * Budget.KILOGRAM))
        assertEquals(Prices.IRON_PRICE / 2, Prices.listValue(Species.Iron, 50L * Budget.KILOGRAM))
    }

    @Test
    fun `most of the world's iron rides in rock rather than in native metal`() {
        // Native iron is 7,000,000; summed across every mineral that carries it, iron comes out at
        // ~21.7M. If this ever collapses back toward the native figure, `compositionOf` has stopped
        // contributing and every mineral in the game is being priced as if it were an element.
        val native = Species.Iron.relativeAbundance.toLong() * 1_000L
        assertTrue(
            Prices.abundance(Species.Iron) > native * 2,
            "iron's abundance ${Prices.abundance(Species.Iron)} is barely above its native $native",
        )
    }

    @Test
    fun `scarcity orders the table`() {
        // The chain the whole economy leans on: common rock is cheap, structural metal is dearer,
        // and the trace metals the mid-game is about are dearer again by orders of magnitude.
        val ladder = listOf(Species.Oxygen, Species.Iron, Species.Titanium, Species.Gold, Species.Uranium)
        val prices = ladder.map { Prices.listPrice(it) }
        assertEquals(prices.sorted(), prices, "scarcity no longer orders $ladder: $prices")
    }

    @Test
    fun `gamma one half holds the obtainable table to four and a half orders`() {
        // γ = 1 spreads this over 7.4 orders and puts uranium at 24.6 billion. γ = 1/2 gives 666 to
        // 4,961,834 — the cheapest and dearest things a player can actually hold, four and a half
        // orders apart and all inside seven digits. See `Prices.GAMMA_HALVED`.
        //
        // ⚠️ Measured over **sourced** species only. The thirteen orphaned lanthanides sit at the
        // rarity ceiling and would dominate a bare maximum without ever being tradeable — see the
        // test above.
        val obtainable = Species.ALL.filter { compositionOf(it).isNotEmpty() || Prices.abundance(it) > 0L }
        val dearest = obtainable.maxOf { Prices.listPrice(it) }
        val cheapest = obtainable.minOf { Prices.listPrice(it) }
        assertTrue(dearest < 10_000_000L, "the obtainable table has spread to $dearest per 100 kg")
        assertTrue(cheapest > 100L, "the cheapest obtainable species fell to $cheapest")
        assertTrue(Prices.listPrice(Species.Gold) > 100L * Prices.listPrice(Species.Iron))
    }

    @Test
    fun `a compound is worth exactly what it is made of`() {
        // §3.4's finding, as a tripwire: this identity is what makes a station's breakdown of a
        // mineral into its elements profitable ONLY through the local stock discount. If a rounding
        // change ever makes the parts systematically worth more, stations start grinding rock for
        // free money and the discount stops being the mechanism.
        for (species in listOf(Species.Forsterite, Species.Hematite, Species.Ilmenite, Species.Uraninite)) {
            val whole = Prices.listPrice(species)
            val parts = compositionOf(species)
                .sumOf { it.partsPerThousand * Prices.listPrice(it.element) } / 1_000L
            assertEquals(whole, parts, "$species is worth $whole whole but $parts in pieces")
        }
    }

    @Test
    fun `a manufactured species is priced off its formula`() {
        // Steel and firebrick carry a formula but no abundance — they are made, not mined. They must
        // still price, and steel must land near iron because it is 99 parts iron by formula.
        val steel = Prices.listPrice(Species.Steel)
        val iron = Prices.listPrice(Species.Iron)
        assertTrue(steel > 0L, "steel has no price")
        assertTrue(steel in (iron / 2)..(iron * 2), "steel at $steel is nowhere near iron at $iron")
    }
}
