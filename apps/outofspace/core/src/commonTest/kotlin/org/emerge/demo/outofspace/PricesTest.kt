package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.MINERALS
import org.emerge.demo.outofspace.chem.atomicMass
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.chem.derivedMolarMass
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
    fun `every element occurs in some rock, so nothing prices at the ceiling`() {
        // ⛔ **This test used to assert there were THIRTEEN elements with no source**, and it existed
        // to make sure that if the chemistry ever changed to source them it would show up here as a
        // failure rather than as a silent repricing. It did, on 2026-09-05, and this is the other
        // side of that.
        //
        // `MINERALS` used to write monazite, bastnasite and xenotime with a *single representative
        // lanthanide* — cerium or yttrium — with the true site occupancy kept in a separate
        // `LANTHANIDE_SUITE` that nothing read. So thirteen lanthanides occurred in no rock this
        // derivation could see, were unobtainable, and `elementPrice`'s floor priced them as the
        // rarest things in the game: the dearest entries in the whole table were things nobody could
        // ever sell.
        //
        // The three minerals are now written as the solid solutions they are, over two hundred
        // lanthanide sites each, so every one of the fifteen occurs in rock and is priced off how
        // much of it there actually is.
        //
        // ⚠️ **The floor in `elementPrice` is not deleted and must stay**, for the reason it was
        // written: an element some future mineral edit orphans has to be priced as unobtainable
        // rather than divide by zero. This asserts nothing reaches it.
        val orphans = Species.ALL.filter { compositionOf(it).isEmpty() && Prices.abundance(it) <= 0L }
        assertTrue(orphans.isEmpty(), "these elements occur in no rock and price at the ceiling: $orphans")

        // The scarce end of the heavy suite, which only xenotime carries.
        assertTrue(Prices.abundance(Species.Dysprosium) > 0L)
        assertTrue(Prices.abundance(Species.Terbium) > 0L)
        // And the light suite's rarest, two sites in two hundred of monazite.
        assertTrue(Prices.abundance(Species.Europium) > 0L)
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
        //
        // ⛔ **Weighed off the formula, and it used to be weighed off [massPartsPerThousand].** That
        // reconstruction floors each element's share to an integer per mille, so it was a *lossy
        // copy* of the identity it was meant to check rather than an independent statement of it —
        // forsterite came out 930 against 931 the moment `Prices` stopped rounding the same way.
        // Nothing in the game physically splits a mineral by per mille (it is display and an
        // abundance ratio), so there is no trade route the old form was guarding.
        //
        // ⚠️ **Every mineral, not the four this used to name.** The identity is now exact by
        // construction, so there is no reason to sample it — and sampling is what let argentite,
        // which is 999 parts per thousand of itself, go unpriced for as long as it did.
        for (species in MINERALS.keys) {
            val whole = Prices.listPrice(species)
            val formula = MINERALS.getValue(species)
            val parts = formula.entries
                .sumOf { (element, atoms) ->
                    atoms.toLong() * element.atomicMass * Prices.listPrice(element)
                } / derivedMolarMass(species)
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
