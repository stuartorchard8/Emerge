package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.compositionOf
import org.emerge.demo.outofspace.num.Budget

/**
 * A station's own business, one tick of it — `PLAN_economy.md` §6.1.
 *
 * Two processes, independent, each capped at [RATE] and each **go/no-go at the full kilogram**: no
 * partial action taken to squeeze out a better margin. A station is a slow industrial concern and it
 * is meant to read as one.
 */

/** What a station moves per tick, in either process. A kilogram — Stu's number. */
val RATE: Long = Budget.KILOGRAM

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
    // Go/no-go: a reserve holding less than a kilogram of its own dominant species does nothing at
    // all rather than dribbling out what is left.
    if (ore[dominant] < RATE) return this
    return Station(
        ore = ore - Mixture.of(dominant to RATE, energy = 0L),
        market = market.absorbing(dominant, RATE),
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
        if (held < RATE || held <= bestHeld) continue
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

    var moved = market.releasing(species, RATE)
    // ⚠️ The shares are integers per mille and need not sum to exactly a thousand, so the remainder
    // goes to the richest element rather than evaporating. A station is outside every ledger in the
    // game, so nothing would have caught the drift — which is the reason to be exact here rather than
    // an excuse not to be.
    var handedOut = 0L
    for (i in parts.indices.reversed()) {
        val share = if (i == 0) RATE - handedOut else RATE * parts[i].partsPerThousand / PARTS_PER_THOUSAND
        if (share <= 0L) continue
        moved = moved.absorbing(parts[i].element, share)
        handedOut += share
    }
    return Station(ore = ore, market = moved)
}
