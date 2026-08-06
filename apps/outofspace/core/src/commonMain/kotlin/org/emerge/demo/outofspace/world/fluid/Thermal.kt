package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.cohesionJoules
import org.emerge.demo.outofspace.chem.reducedDensity
import org.emerge.demo.outofspace.world.Temperature

/**
 * Gas thermal energy: belongs to atmosphere, travels with it, sets pressure.
 * Not in tile-field (copy(air=...) would leave joules stale — temperature derived from capacity).
 * Coupled to fabric via stepSolidHeat conduction.
 * Joules move (not temperature): advected as fraction of donor's gas (prevents energy creation/destruction).
 */

/**
 * Gas heat capacity per tile: millijoules/kelvin (zero if no gas).
 * Millijoule scale matches Species.specificHeat (per kg). Avoids joule-scale quantization cliff (<1g→0, 2g→1).
 * joules/capacity exact at ambient (stepFluid: room-temp vessel = isothermal).
 */
fun gasCapacity(tileCount: Int, grams: LongArray, species: List<Species> = Species.FLUIDS): LongArray =
    LongArray(tileCount) { gasCapacityAt(grams, it, species) }

/** Millijoules per kelvin held by the gas in one tile — see [gasCapacity] for the units. */
fun gasCapacityAt(grams: LongArray, tile: Int, species: List<Species> = Species.FLUIDS): Long {
    val base = tile * Species.COUNT
    var sum = 0L
    for (s in species) sum += grams[base + s.ordinal] * s.specificHeat
    return sum
}

/** Capacity/energy scale: 1000 (matches Species.specificHeat per-kg → gram units). */
const val CAPACITY_SCALE = 1000L

/**
 * The cohesion energy held in every tile — the energy the fluid owes to its own molecules
 * attracting each other, which is negative because that is a bound state.
 *
 * See [cohesionJoules]. This is the latent heat as a field, and it is deliberately *not* stored
 * anywhere: it is a function of the density and so is recomputed whenever it is needed, in the same
 * spirit as [gasKelvin]. What is stored, in `gasJoules`, remains the thermal energy alone, which is
 * what keeps [advectHeat] correct without modification — thermal energy is linear in mass and so
 * rides a mass flux honestly, whereas cohesion goes as the square of density and would not.
 */
fun cohesionField(tileCount: Int, grams: LongArray, volumes: VolumeField?): LongArray =
    LongArray(tileCount) { tile ->
        val room = volumes?.at(tile) ?: VolumeField.FULL
        var sum = 0L
        for (s in Species.FLUIDS) {
            val g = grams[tile * Species.COUNT + s.ordinal]
            if (g <= 0L) continue
            val dr = reducedDensity(g, s, room, VolumeField.FULL) ?: continue
            sum += cohesionJoules(dr, s, room, VolumeField.FULL)
        }
        sum
    }

/**
 * Settle the tick's change in cohesion energy against the thermal pot, tile by tile.
 *
 * **This is where boiling gets paid for.** Over a tick a cell's fluid has spread out or drawn
 * together, and its cohesion energy has risen toward zero or fallen away from it. Total internal
 * energy is thermal plus cohesion and cannot change on its own, so whatever cohesion gained, thermal
 * loses. A cell whose water has just expanded therefore cools, which is what stops boiling running
 * away; a cell where vapour has just gathered warms, which is the latent heat coming back out on
 * condensation.
 *
 * No latent heat of vaporisation is written down anywhere in the codebase, and none is needed: the
 * number falls out of the same attraction term that produced the phase transition.
 *
 * @return the energy that could not be taken because the thermal pot was already empty — see below.
 */
fun settleCohesion(gasJoules: LongArray, before: LongArray, after: LongArray): Long {
    var unpaid = 0L
    for (tile in gasJoules.indices) {
        val owed = after[tile] - before[tile]
        val paid = if (owed > gasJoules[tile]) gasJoules[tile] else owed
        gasJoules[tile] -= paid
        unpaid += owed - paid
    }
    return unpaid
}

/** Gas temperature per tile (kelvin). Empty tiles read AMBIENT_KELVIN (placeholder for absent gas; tilePressure multiplies this). */
fun gasKelvin(gasJoules: LongArray, capacity: LongArray): IntArray =
    IntArray(gasJoules.size) {
        if (capacity[it] <= 0L) Temperature.AMBIENT_KELVIN else (gasJoules[it] / capacity[it]).toInt()
    }

/**
 * The energy a tile's gas holds at room temperature, in millijoules — what a filled vessel starts
 * with, and exactly divisible by its capacity so the temperature reads as ambient on the nose.
 */
fun ambientGasJoules(tileCount: Int, grams: LongArray): LongArray {
    val capacity = gasCapacity(tileCount, grams)
    return LongArray(tileCount) { capacity[it] * Temperature.AMBIENT_KELVIN }
}

/**
 * Moves the gas's heat along with the gas, conserving joules exactly.
 *
 * ### Boundaries
 *
 * Energy leaving on the rim leaves the world, and is returned so the vessel's energy ledger still
 * closes. Venting hot gas is a genuine way for a ship to lose energy — it is what a rocket does —
 * and it must be counted on its way out rather than quietly vanishing, for exactly the reasons
 * [advectMass] counts grams.
 *
 * Nothing arrives from outside: space is empty, and empty is not cold, it is *absent*.
 *
 * [gasJoules] is edited in place. [flux] and [tileGrams] must both be from the same snapshot as each
 * other and as the [advectMass] pass that produced them — the mass field as it was *before* it moved
 * anything, which is why [tileGrams] is passed rather than recomputed.
 *
 * @return the joules that left the grid entirely.
 */
fun advectHeat(
    edges: EdgeGrid,
    gasJoules: LongArray,
    flux: MassFlux,
    tileGrams: LongArray,
): Long {
    // Snapshotted before anything moves, so every transfer is measured against the same field and
    // the result cannot depend on the order faces are visited.
    val before = gasJoules.copyOf()

    val moves = HeatTransfers(edges.xEdgeCount + edges.yEdgeCount, gasJoules.size)
    for (e in 0 until edges.xEdgeCount) {
        val crossing = flux.x[e]
        if (crossing == 0L) continue
        moves.request(crossing, edges.xEdgeBefore(e), edges.xEdgeAfter(e), before, tileGrams)
    }
    for (e in 0 until edges.yEdgeCount) {
        val crossing = flux.y[e]
        if (crossing == 0L) continue
        moves.request(crossing, edges.yEdgeBefore(e), edges.yEdgeAfter(e), before, tileGrams)
    }
    return moves.settle(gasJoules)
}

/**
 * Ask-first-pay-afterwards for the heat the gas is carrying.
 *
 * The same discipline as the mass and momentum passes and for the same reason: a tile can be drained
 * by all four of its faces at once, every flux is computed against one snapshot, so the requests have
 * to be totalled and clamped before any of them is honoured.
 */
private class HeatTransfers(capacity: Int, tileCount: Int) {
    private val amount = LongArray(capacity)
    private val from = IntArray(capacity)
    private val to = IntArray(capacity)
    private var count = 0
    private val available = LongArray(tileCount)
    private val requested = LongArray(tileCount)

    fun request(crossing: Long, before: Int, after: Int, gasJoules: LongArray, tileGrams: LongArray) {
        val donor = if (crossing > 0L) before else after
        val acceptor = if (crossing > 0L) after else before
        if (donor < 0) return // Space has no heat to give.
        val donorGrams = tileGrams[donor]
        if (donorGrams <= 0L) return
        val moving = if (crossing > 0L) crossing else -crossing
        val carried = gasJoules[donor] * moving / donorGrams
        if (carried <= 0L) return

        amount[count] = carried
        from[count] = donor
        to[count] = acceptor
        count++
        available[donor] = gasJoules[donor]
        requested[donor] += carried
    }

    /**
     * Applies every transfer, scaled down where a tile was asked for more than it had.
     *
     * Every clamp is against the **snapshot**, never against the live array. A further guard of
     * `minOf(moving, gasJoules[donor])` looks like free safety and is in fact a Gauss-Seidel sweep:
     * the first face to drain a tile would see its full energy and the second a reduced one, so the
     * answer would depend on the order faces are visited — which on a row-major grid is a leftward
     * bias. That is the exact fault [BreachSymmetryTest] was written for after `applySpeciesDrift`
     * had it, and it caught this one within a tick of it being written.
     *
     * It is also unnecessary. The scaled shares sum to at most `available`, which is what the tile
     * had, so a tile cannot be overdrawn.
     */
    fun settle(gasJoules: LongArray): Long {
        var escaped = 0L
        for (i in 0 until count) {
            val donor = from[i]
            var moving = amount[i]
            val asked = requested[donor]
            if (asked > available[donor]) moving = moving * available[donor] / asked
            if (moving <= 0L) continue
            gasJoules[donor] -= moving
            if (to[i] < 0) escaped += moving else gasJoules[to[i]] += moving
        }
        return escaped
    }
}
