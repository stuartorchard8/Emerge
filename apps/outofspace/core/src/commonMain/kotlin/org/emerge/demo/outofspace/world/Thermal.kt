package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Temperature

/**
 * Gas thermal energy: belongs to atmosphere, travels with it, sets pressure.
 * Not in tile-field (copy(air=...) would leave joules stale — temperature derived from capacity).
 * Coupled to fabric via stepSolidHeat conduction.
 * Joules move (not temperature): carried as a fraction of the donor cell's gas, which is what stops
 * energy being created or destroyed at a transfer — see [diffuseFluid].
 */

/**
 * Gas heat capacity per tile: millijoules/kelvin (zero if no gas).
 * Millijoule scale matches Species.specificHeat (per kg). Avoids joule-scale quantization cliff (<1g→0, 2g→1).
 * joules/capacity exact at ambient (a room-temperature vessel reads as isothermal).
 */
fun gasCapacity(tileCount: Int, grams: LongArray): LongArray =
    LongArray(tileCount) { gasCapacityAt(grams, it) }

/** Millijoules per kelvin held by the gas in one tile — see [gasCapacity] for the units. */
fun gasCapacityAt(grams: LongArray, tile: Int): Long {
    val base = tile * Species.COUNT
    var sum = 0L
    for (s in Species.ALL) sum += grams[base + s.ordinal] * s.specificHeat
    // The whole product first, then the divisor: dividing per species would round a trace gas out of
    // its own capacity. [Budget.CAPACITY_DIVISOR] is 1 at today's units, so this is the expression it
    // has always been — see that constant for the relation it carries.
    return sum / Budget.CAPACITY_DIVISOR
}

/** Capacity/energy scale: 1000 (matches Species.specificHeat per-kg → gram units). */
const val CAPACITY_SCALE = 1000L

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
