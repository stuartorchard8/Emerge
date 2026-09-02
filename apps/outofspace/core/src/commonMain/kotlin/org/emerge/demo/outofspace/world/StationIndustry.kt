package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.num.Budget

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

/** How much of one compound a station takes apart in a batch. The same tonne, for now. */
val CRACKING_BATCH: Long = 1_000L * Budget.KILOGRAM

/** Prices are quoted per hundred kilograms; a share is per mille. */
private const val PARTS_PER_THOUSAND = 1_000L

/**
 * This station having done a tick's work: separate a kilogram of ore, and crack a kilogram of a
 * compound if that is worth doing.
 *
 * Both may happen on the same tick. They are different plants working different stockpiles, and
 * making them take turns would be a rule about the code rather than about the station.
 */
fun Station.worked(): Station = purified().brokenDown()

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
 * A kilogram of one compound cracked into its elements, if the elements are worth more apart.
 *
 * ⛔ **At LIST prices this can never fire, and that is not a bug — it is the mechanism.**
 * [Prices.listPrice] *defines* a compound's price as the sum of its elements', so the gain is
 * identically zero (measured to four decimal places across every mineral, `PLAN_economy.md` §3.4).
 * What makes cracking profitable is the **station-local stock discount**: a station glutted with
 * forsterite quotes forsterite cheap while its magnesium, silicon and oxygen shelves are near list.
 * So it cracks *because it is over-supplied*, and it stops when the element shelves fill up.
 *
 * ⛔ **The discount must therefore be per SPECIES stock, never per element.** Apply it one level up
 * and the two sides of this comparison move together and nothing ever happens.
 *
 * ⚠️ Compared **per price unit**, not per kilogram. Value is linear in mass, so the comparison is the
 * same at any size — and at a kilogram both sides truncate to zero at exactly the glutted station
 * where this is supposed to fire.
 */
private fun Station.brokenDown(): Station {
    var best: Species? = null
    var bestHeld = 0L
    var bestParts: List<org.emerge.demo.outofspace.chem.ElementShare>? = null

    // Most abundant compound first; the next one only if the one above it is not worth cracking.
    // Walked rather than sorted: one pass over the shelves picks the richest *profitable* compound,
    // which is the same answer sorting would give and does not allocate a list every tick.
    for (species in Species.ALL) {
        val held = market.stockOf(species)
        if (held < CRACKING_BATCH || held <= bestHeld) continue
        val parts = compositionOf(species)
        if (parts.isEmpty()) continue
        val whole = market.price(species)
        var apart = 0L
        for (p in parts) apart += p.partsPerThousand * market.price(p.element)
        if (apart / PARTS_PER_THOUSAND <= whole) continue
        best = species
        bestHeld = held
        bestParts = parts
    }

    val species = best ?: return this
    val parts = bestParts ?: return this

    var moved = market.releasing(species, CRACKING_BATCH)
    // ⚠️ The shares are integers per mille and need not sum to exactly a thousand, so the remainder
    // goes to the richest element rather than evaporating. A station is outside every ledger in the
    // game, so nothing would have caught the drift — which is the reason to be exact here rather than
    // an excuse not to be.
    var handedOut = 0L
    for (i in parts.indices.reversed()) {
        val share =
            if (i == 0) CRACKING_BATCH - handedOut
            else CRACKING_BATCH * parts[i].partsPerThousand / PARTS_PER_THOUSAND
        if (share <= 0L) continue
        moved = moved.absorbing(parts[i].element, share)
        handedOut += share
    }
    return Station(ore = ore, market = moved, id = id, docks = docks)
}
