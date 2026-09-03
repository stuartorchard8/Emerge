package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.REACTIONS
import org.emerge.demo.outofspace.chem.Reaction
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio

/**
 * A station's own business, one batch of it — `PLAN_economy.md` §6.1.
 *
 * Two processes, independent, each **go/no-go at the full batch**: no partial action taken to
 * squeeze out a better margin. A station is a slow industrial concern and it is meant to read as
 * one. The schedule that makes it slow is [org.emerge.demo.outofspace.OutofspaceReducer.STATION_PERIOD]
 * — once a minute — and it is stated there rather than here because it is a fact about the clock.
 */

/**
 * How much ore a station lifts out of its reserve in one batch: **a tonne**.
 *
 * ⛔ **This is a go/no-go threshold and not only a rate**, and that is the whole of what stops a
 * station being an oracle. A reserve whose dominant species is under a tonne is not worked *at all*
 * — so a station cannot pull trace metals out of ore for you until somebody has delivered it several
 * hundred tonnes of the rock they are in, and even then it does it a tonne a minute. It still does
 * it automatically, which is a genuine service; the mark-up on buying the result back is what makes
 * doing it yourself the better deal.
 *
 * ⚠️ Was a kilogram a *tick*, which is 3.8 tonnes a minute and let a station out-refine the player
 * without being asked.
 */
val CONCENTRATION_BATCH: Long = 1_000L * Budget.KILOGRAM

/**
 * How much of a reaction's **largest product** one batch of station chemistry makes: a hundred
 * kilograms.
 *
 * ⚠️ **Sized off one product and not off the charge**, so that a batch means the same thing to a row
 * that yields four things as to one that yields one. See [batchMass].
 */
val REACTION_BATCH: Long = 100L * Budget.KILOGRAM

/**
 * What a credit buys in energy: **a megajoule**.
 *
 * ⛔ **Fixed, and deliberately not a market.** It is the one number in [heatFee] that is chosen
 * rather than derived, and the alternative — a station burning its own carbon to fire its furnace —
 * was considered and rejected: it makes fuel a second failure mode a player cannot see, on a body
 * with no thermal model to burn anything in.
 *
 * ⚠️ **Chosen against the game's own tables rather than out of the air.** A kilogram of carbon
 * fetches 33.7 credits at list and releases 32.8 MJ burning, so carbon values its own combustion
 * energy at 1.03 MJ to the credit. A megajoule is that number rounded to something readable, and it
 * lands the fee where the gradient is legible: about 1% of the charge to roast pyrite, 6% to alloy
 * steel, 16% to crack serpentine, and 34–60% for the carbothermic reductions of the silicates,
 * which are the expensive end in reality too.
 */
val ENERGY_PER_CREDIT: Long = 1_000_000L * Budget.JOULE

/** Prices are quoted per hundred kilograms; a share is per mille. */
private const val PARTS_PER_THOUSAND = 1_000L

/**
 * This station having done a batch of work: separate a batch of ore, and run a batch through
 * whichever reaction pays best, if any does.
 *
 * Both may happen in the same batch. They are different plants working different stockpiles, and
 * making them take turns would be a rule about the code rather than about the station.
 */
fun Station.worked(): Station = purified().reacted()

/**
 * A kilogram of the dominant species lifted out of the mixed reserve and onto the shelves.
 *
 * ⛔ **The dominant species of the WHOLE reserve**, not of any particular delivery. A station tips
 * everything it buys into one heap and works the heap; which ship a gram arrived on is not a fact it
 * keeps. That is also what makes selling it dirty ore quietly expensive for the seller — the tail
 * they were not paid for is now indistinguishable from everybody else's.
 */
private fun Station.purified(): Station {
    val dominant = ore.dominant ?: return this
    // Go/no-go: a reserve holding less than a full batch of its own dominant species does nothing
    // at all rather than dribbling out what is left. See [CONCENTRATION_BATCH] — the threshold is
    // the balance, not the rate.
    if (ore[dominant] < CONCENTRATION_BATCH) return this
    // ⚠️ **`id` and `docks` carried explicitly.** `Station`'s constructor defaults them, so rebuilding
    // one from two fields quietly hands back a station with **no identity and no berths** — and it
    // does it a tick after the world starts, in a value nothing else reads until the player tries to
    // dock. Caught by a screenshot reading "STATION 0"; the same shape as `RigidBody.copy`'s warning
    // one file over, which is why that one is written down there in capitals.
    return Station(
        ore = ore - Mixture.of(dominant to CONCENTRATION_BATCH, energy = 0L),
        market = market.absorbing(dominant, CONCENTRATION_BATCH),
        id = id,
        docks = docks,
    )
}


/**
 * One batch run through whichever reaction the station stands to make the most on, or nothing.
 *
 * ### This used to be a free element splitter, and that was the wrong game
 *
 * ⛔ **What was here took any compound apart into its elements by mass share, with no chemistry in
 * it at all** — no reagents, no temperature, no cost, and no direction. Three things followed, and
 * each of them undercut a system the game had already built:
 *
 * - **Serpentine gave no water.** Splitting Mg₃Si₂O₅(OH)₄ by element yields magnesium, silicon,
 *   oxygen and hydrogen. The *reaction* — `DECOMPOSITIONS`' serpentine row — yields forsterite,
 *   enstatite and **two waters**, which is where a station's water is supposed to come from. Under
 *   the old rule there was no way for one to replenish its tanks except by being sold water.
 * - **Steel only ever went backwards.** `Fe₉₉C` is a formula in `MINERALS`, so a station could take
 *   an alloy to pieces and had no way whatever to make one. `REACTIONS` has the forward row, so a
 *   station can now *make* steel out of iron and carbon — which is the more useful commodity, and
 *   is the thing a trading post ought to be selling.
 * - **Titanium was free.** Ilmenite is one of the commoner minerals in the game, and splitting
 *   FeTiO₃ by element hands over titanium for nothing — routing straight around the reduction
 *   chain `PLAN_ambient_chemistry.md` exists to make interesting. The real route is two rows deep
 *   and the second one spends **magnesium**, which does not occur naturally and has to be made.
 *
 * ### What decides
 *
 * Every row in [REACTIONS], scaled so its **largest product** comes to [REACTION_BATCH]. A row is
 * available if the shelves hold its whole charge at that size; of the available ones the station
 * runs the single most profitable, and if none of them pays it does nothing.
 *
 * ⛔ **At list prices every reaction in the game is exactly value-neutral**, measured across the
 * whole table: a species' price is *defined* as the sum of its elements' and a reaction conserves
 * atoms, so the two sides agree to within a few credits of rounding on charges worth thousands.
 * That is the same fact §3.4 records about cracking, and it means what makes a reaction pay is —
 * still, and only — the **station-local stock discount** plus [heatFee]. A station reacts because
 * it is over-supplied in the reagents and short of the products, and it stops when that stops being
 * true. ⛔ So the discount must stay per SPECIES stock; apply it per element and both sides of the
 * comparison move together and the whole mechanism dies silently.
 */
private fun Station.reacted(): Station {
    var best: Reaction? = null
    var bestProfit = 0L
    var bestCharge = Mixture.EMPTY
    var bestYield = Mixture.EMPTY

    for (reaction in REACTIONS) {
        val total = batchMass(reaction)
        if (total <= 0L) continue
        val charge = reaction.mixtureOf(reaction.reagents, reaction.draw(total))
        if (!market.canSupply(charge)) continue
        val yielded = reaction.mixtureOf(reaction.products, reaction.split(total))
        // ⚠️ Priced at the shelves as they stand, on both sides, which is what makes this
        // self-limiting: the products are quoted against the emptier shelf they are about to fill,
        // so each batch makes the next one worth slightly less.
        val profit = market.valueOf(yielded) - market.valueOf(charge) - reaction.heatFee(charge)
        if (profit > bestProfit) {
            best = reaction
            bestProfit = profit
            bestCharge = charge
            bestYield = yielded
        }
    }

    if (best == null) return this
    // ⚠️ **Released before absorbed**, because a row may hold the same species on both sides —
    // photosynthesis takes a hundred units of algae and gives back a hundred and one. Absorbing
    // first would let a station run a reaction it could not actually charge.
    return Station(ore = ore, market = market.releasing(bestCharge).absorbing(bestYield), id = id, docks = docks)
}

/**
 * The mass of charge that yields exactly [REACTION_BATCH] of this row's **largest** product, or
 * zero if the row makes nothing.
 *
 * ⚠️ **Sized off one product rather than off the total**, which is Stu's rule and the one that makes
 * batches comparable across rows of wildly different shapes. A reaction that yields a tonne of
 * tailings and a kilogram of metal and one that yields the reverse would otherwise be run at sizes
 * whose profits mean different things.
 */
internal fun batchMass(reaction: Reaction): Long {
    var sum = 0L
    var largest = 0L
    for ((species, units) in reaction.products) {
        val weight = units.toLong() * species.molarMass
        sum += weight
        if (weight > largest) largest = weight
    }
    if (largest <= 0L) return 0L
    return scaledRatio(sum, largest, REACTION_BATCH)
}

/**
 * What this station's furnace charges to run [charge] through this row, in credits.
 *
 * Two terms, and the codebase already states the first one: *"the energy a foundry actually spends
 * is spent getting the charge to temperature, which is what `onsetKelvin` already makes the player
 * pay for"* ([Reaction]'s steel row). So the fee is the heat capacity of the actual charge times the
 * climb from ambient to the row's onset — derived from specific heats and the reaction table, with
 * nothing authored per row — plus whatever the reaction itself swallows.
 *
 * ⛔ **An exothermic row is never paid back.** A station has no thermal model, no store to put
 * recovered heat in and nobody to sell it to, so a rebate would be inventing a buyer. Worse, it
 * would be a large one: burning a batch of carbon releases about thirty times what it costs to
 * light, so a station credited for its own fires would burn every gram of carbon it owned for the
 * money. Endothermy is charged; exothermy is free and no more than free.
 *
 * ⚠️ **[Reaction.enthalpy], never `enthalpyPerKg` times a mass.** The per-kilogram figure is around
 * 3×10⁹ energy units and a batch is 10¹¹ of mass — the product is 10²⁰ and a `Long` stops at
 * 9.2×10¹⁸. Multiplying them directly overflows and comes back *negative*, which reads as a large
 * exothermic rebate on an endothermic row. It caught out the measurement that sized this constant.
 */
internal fun Reaction.heatFee(charge: Mixture): Long {
    val climb = (onsetKelvin - Temperature.AMBIENT_KELVIN).coerceAtLeast(0).toLong()
    val warming = energyAtKelvin(thermalMassOf(charge), climb.toInt())
    return (warming + enthalpy(charge[principal]).coerceAtLeast(0L)) / ENERGY_PER_CREDIT
}

/** The masses in [amounts] against the species in [entries], as one mixture. Cold; a station has no heat. */
private fun Reaction.mixtureOf(entries: List<Pair<Species, Int>>, amounts: LongArray): Mixture {
    val masses = LongArray(Species.COUNT)
    for (i in entries.indices) masses[entries[i].first.ordinal] += amounts[i]
    return Mixture.of(masses, 0L)
}

/** What [lump] is worth on these shelves, at the prices they quote right now. */
private fun Market.valueOf(lump: Mixture): Long {
    var sum = 0L
    for (species in Species.ALL) {
        val mass = lump[species]
        if (mass > 0L) sum += scaledRatio(mass, Prices.PRICE_UNIT_MASS, price(species))
    }
    return sum
}
