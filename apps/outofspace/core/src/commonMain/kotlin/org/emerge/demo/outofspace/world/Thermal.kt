package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.chem.Species

/**
 * Gas thermal energy: belongs to atmosphere, travels with it, sets pressure.
 * Not in tile-field (copy(air=...) would leave energy stale — temperature derived from capacity).
 * Coupled to fabric via stepSolidHeat conduction.
 * Joules move (not temperature): carried as a fraction of the donor cell's gas, which is what stops
 * energy being created or destroyed at a transfer — see [diffuseFluid].
 */

/**
 * Gas heat capacity per tile: millijoules/kelvin (zero if no gas).
 * Millijoule scale matches Species.specificHeat (per kg). Avoids joule-scale quantization cliff (<1g→0, 2g→1).
 * energy/capacity exact at ambient (a room-temperature vessel reads as isothermal).
 */
fun heatCapacity(tileCount: Int, masses: MassArray): LongArray =
    LongArray(tileCount) { heatCapacityAt(masses, TileIndex(it)) }

/** Millijoules per kelvin held by the gas in one tile — see [heatCapacity] for the units. */
fun heatCapacityAt(masses: MassArray, tile: TileIndex): Long {
    var sum = 0L
    for (s in Species.ALL) sum += masses[MassIndex(tile,s)] * s.specificHeat
    // The whole product first, then the divisor: dividing per species would round a trace gas out of
    // its own capacity. [Budget.CAPACITY_DIVISOR] is 1 at today's units, so this is the expression it
    // has always been — see that constant for the relation it carries.
    return sum / Budget.CAPACITY_DIVISOR
}

/** Capacity/energy scale: 1000 (matches Species.specificHeat per-kg → gram units). */
const val CAPACITY_SCALE = 1000L

/** Gas temperature per tile (kelvin). Empty tiles read AMBIENT_KELVIN (placeholder for absent gas; tilePressure multiplies this). */
fun gasKelvin(gasEnergy: EnergyArray, capacity: LongArray): IntArray =
    IntArray(gasEnergy.size) {
        if (capacity[it] <= 0L) Temperature.AMBIENT_KELVIN else (gasEnergy[TileIndex(it)] / capacity[it]).toInt()
    }

/**
 * The energy a tile's gas holds at room temperature, in millijoules — what a filled vessel starts
 * with, and exactly divisible by its capacity so the temperature reads as ambient on the nose.
 */
fun ambientGasEnergy(tileCount: Int, masses: MassArray): EnergyArray {
    val capacity = heatCapacity(tileCount, masses)
    return EnergyArray(tileCount) { capacity[it] * Temperature.AMBIENT_KELVIN }
}
