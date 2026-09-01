package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * One counterparty's prices, and what it will pay for a lump of matter — `PLAN_economy.md` §3.3–§3.6.
 *
 * A market is a **pure function of what it is holding**. [Prices] says what a species is worth in
 * the abstract; this says what *this* trader will pay today, which is less the more of it they
 * already have. Nothing here is authored per station: give two stations different stock and they
 * quote different numbers, which is the whole of "shop around".
 *
 * Immutable. [absorbing] and [releasing] return a new market, so a station's stock moves the way
 * every other piece of world state moves.
 */
class Market private constructor(private val stock: LongArray) {

    /** How much pure [species] this trader is holding. */
    fun stockOf(species: Species): Long = stock[species.ordinal]

    /**
     * What a species costs here when the trader holds [held] of it, in credits per
     * [Prices.PRICE_UNIT_MASS] — list price decayed toward [FLOOR_PRICE] as the shelves fill.
     *
     * `list × K / (K + held)`: at an empty shelf it is list, at [REFERENCE_STOCK] it is half list,
     * and it asymptotes to nothing — floored, because a price of zero is a thing you cannot sell and
     * the curve should say "nearly worthless", not "not a commodity".
     */
    fun priceAt(species: Species, held: Long): Long {
        val list = Prices.listPrice(species)
        if (list <= 0L) return FLOOR_PRICE
        val shelf = held.coerceAtLeast(0L)
        return scaledRatio(REFERENCE_STOCK, REFERENCE_STOCK + shelf, list).coerceAtLeast(FLOOR_PRICE)
    }

    /** What a species costs here right now, before any particular trade moves the shelf. */
    fun price(species: Species): Long = priceAt(species, stock[species.ordinal])

    /**
     * What the trader **pays** per [Prices.PRICE_UNIT_MASS] to take [mass] of [species] off you.
     *
     * ⛔ **Priced at the stock the trade LEAVES BEHIND, not the stock it started from**, and that one
     * decision is what makes the market un-exploitable — see the class note on [SPREAD_PERMILLE].
     */
    fun bidFor(species: Species, mass: Long): Long =
        withSpread(priceAt(species, stock[species.ordinal] + mass.coerceAtLeast(0L)), -SPREAD_PERMILLE)

    /** What the trader **charges** per [Prices.PRICE_UNIT_MASS] to hand you [mass] of [species]. */
    fun askFor(species: Species, mass: Long): Long =
        withSpread(priceAt(species, stock[species.ordinal] - mass.coerceAtLeast(0L)), SPREAD_PERMILLE)

    /**
     * What this trader pays for [lump] — §3.6, **the top two species by mass, each at its share
     * squared**, and everything else forfeit.
     *
     * ```
     * Σ over the top TWO species s of   bid(s) × mass(s) × (mass(s) / total)²
     * ```
     *
     * The square is the whole incentive structure of the game's middle third. Measured against the
     * concentrator's real purity ladder (41 → 65 → 86 → 94 → 97 → 100), it is worth **10.6×** to
     * arrive with pure metal rather than with what an extractor hands you, and the gain front-loads:
     * +196% on the first rung and +10% on the last. That shape is deliberate — the last rungs are
     * already paid for by `BUILD_PURITY_PERCENT` being 100, and should not be bought twice.
     *
     * ⚠️ **A pure lump is the top ONE species at a share of exactly one, so it fetches full rate.**
     * The rule Stu first stated — top two, each at half — would have paid a 100% lump half price,
     * because a pure lump is still "the top species present". This is the same rule made continuous.
     *
     * ⛔ **The forfeit tail is the rare, valuable part**, by definition: a trace species is a small
     * share and small shares are what this drops. That is the sneaky cost of outsourcing your
     * purification, and it is the reason a concentrator pays for itself.
     */
    fun sellValue(lump: Mixture): Long {
        val total = lump.total
        if (total <= 0L) return 0L
        var value = 0L
        for (species in topTwo(lump)) {
            val mass = lump[species]
            if (mass <= 0L) continue
            // Share as a per-mille, squared into a per-million. The resolution is 1e-6 of the lump,
            // which is far finer than any purity the concentrator can actually reach, and it keeps
            // the square inside an `Int`'s worth of range.
            val shareMilli = scaledRatio(mass, total, PER_MILLE)
            val shareSquared = shareMilli * shareMilli
            val gross = scaledRatio(mass, Prices.PRICE_UNIT_MASS, bidFor(species, mass))
            value += scaledRatio(shareSquared, PER_MILLION, gross)
        }
        return value
    }

    /** What it costs to buy [mass] of pure [species] here. */
    fun buyCost(species: Species, mass: Long): Long {
        if (mass <= 0L) return 0L
        return scaledRatio(mass, Prices.PRICE_UNIT_MASS, askFor(species, mass))
    }

    /**
     * Everything on the shelves, as one mixture.
     *
     * ⚠️ **Mass only — the energy is zero and means it.** A station has no thermal model; see
     * [org.emerge.demo.outofspace.world.Station]. This is for the save and for readouts, and it must
     * not be handed to anything that would read the energy as a temperature.
     */
    fun holdings(): Mixture = Mixture.of(stock, 0L)

    /** True when the trader is actually holding enough of [species] to sell you [mass] of it. */
    fun canSupply(species: Species, mass: Long): Boolean = mass in 0L..stock[species.ordinal]

    /** This market having taken [lump] onto its shelves — **all of it**, including the forfeit tail. */
    fun absorbing(lump: Mixture): Market {
        val next = stock.copyOf()
        for (species in Species.ALL) {
            val mass = lump[species]
            if (mass > 0L) next[species.ordinal] += mass
        }
        return Market(next)
    }

    /** This market having taken [mass] of [species] onto its shelves. */
    fun absorbing(species: Species, mass: Long): Market {
        if (mass <= 0L) return this
        val next = stock.copyOf()
        next[species.ordinal] += mass
        return Market(next)
    }

    /** This market having handed [mass] of [species] over. Refuses to go short. */
    fun releasing(species: Species, mass: Long): Market {
        require(canSupply(species, mass)) {
            "a market holding ${stock[species.ordinal]} of $species cannot release $mass"
        }
        if (mass == 0L) return this
        val next = stock.copyOf()
        next[species.ordinal] -= mass
        return Market(next)
    }

    /** The two heaviest species in [lump]. One element when the lump is pure, none when it is empty. */
    private fun topTwo(lump: Mixture): List<Species> {
        var first: Species? = null
        var second: Species? = null
        for (species in Species.ALL) {
            val mass = lump[species]
            if (mass <= 0L) continue
            when {
                first == null || mass > lump[first] -> { second = first; first = species }
                second == null || mass > lump[second] -> second = species
            }
        }
        return listOfNotNull(first, second)
    }

    private fun withSpread(price: Long, permille: Long): Long =
        (price * (PER_MILLE + permille) / PER_MILLE).coerceAtLeast(FLOOR_PRICE)

    companion object {
        /**
         * The stock at which a trader quotes **half** list — the width of the whole price curve.
         *
         * Ten tonnes is half a [org.emerge.demo.outofspace.world.machine.Storage], so one full tank
         * of a thing noticeably moves a station's price for it. That is the intent: a market a player
         * cannot budge is scenery.
         */
        const val REFERENCE_STOCK: Long = 10L * Budget.TONNE

        /**
         * The half-spread between what a trader pays and what it charges, in per mille.
         *
         * ⛔ **This is NOT what makes the market un-exploitable**, and the distinction cost a
         * finding. A single price curve driven by stock is an arbitrage machine: buying drains the
         * shelf, which *raises* the price, so selling straight back pays more than it cost. A spread
         * only defers that — it holds while a trade is small against [REFERENCE_STOCK] and fails for
         * `m > 2σ(K + stock)/(1 + σ)`, i.e. for exactly the large trades a rich player makes.
         *
         * ✅ **What makes it safe is that [bidFor] and [askFor] price at the stock the trade LEAVES.**
         * Buying is quoted against the emptier shelf it creates and selling against the fuller one,
         * so the player always moves the market against themselves. `ask(S−m) > bid(S)` for every
         * positive `m`, at any spread including zero, and the same holds sell-then-buy. The spread
         * is then free to be what it should be — a trader's margin, and a knob for making one station
         * meaner than another.
         */
        const val SPREAD_PERMILLE: Long = 100L

        /** A price never quite reaches zero: "nearly worthless" is a trade, "free" is not. */
        const val FLOOR_PRICE: Long = 1L

        private const val PER_MILLE: Long = 1_000L
        private const val PER_MILLION: Long = 1_000_000L

        /** A trader with empty shelves, quoting list for everything. */
        fun empty(): Market = Market(LongArray(Species.COUNT))

        /** A trader holding exactly the mass in [mixture]. The inverse of [holdings]. */
        fun holding(mixture: Mixture): Market {
            val stock = LongArray(Species.COUNT)
            for (species in Species.ALL) stock[species.ordinal] = mixture[species]
            return Market(stock)
        }

        /** A trader holding exactly what it is given. */
        fun of(vararg holdings: Pair<Species, Long>): Market {
            val stock = LongArray(Species.COUNT)
            for ((species, mass) in holdings) stock[species.ordinal] += mass
            return Market(stock)
        }
    }
}
