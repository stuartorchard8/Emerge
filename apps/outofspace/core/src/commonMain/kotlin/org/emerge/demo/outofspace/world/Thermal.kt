package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.num.scaledRatio


/**
 * Gas thermal energy: belongs to atmosphere, travels with it, sets pressure.
 * Not in tile-field (copy(air=...) would leave energy stale — temperature derived from capacity).
 * Coupled to fabric via stepSolidHeat conduction.
 * Joules move (not temperature): carried as a fraction of the donor cell's gas, which is what stops
 * energy being created or destroyed at a transfer — see [diffuseFluid].
 */

/**
 * Millijoules per kelvin held by the gas in one tile — [thermalMassAt] over `CAPACITY_DIVISOR`.
 *
 * ⛔ **Never form a temperature out of this.** It is [thermalMassAt] with the divisor already
 * applied, and that division is lossy: `CAPACITY_DIVISOR` is **10⁷**, so a cell holding less than
 * ~9.6 mg of nitrogen (0.7 mg of hydrogen, 22 mg of iron) has a capacity of *zero* however much
 * energy it is carrying, and every `energy / capacity` in the game then took the
 * `capacity <= 0` branch and answered a fabricated ambient. Measured: **112,020 cell-ticks** in one
 * suite run reading 293 K for gas that genuinely had a temperature — and on a 16 K vessel that is a
 * 277 K lie feeding `vapourMass`'s dome lookups and the pressure sweep.
 *
 * ⚠️ Two places in the tree asserted this could not happen. This function's own comment used to say
 * "`CAPACITY_DIVISOR` is 1 at today's units", and `PLAN_unit_rescale.md`'s Correction 2 says the
 * temperature item is "already satisfied … no truncation and no sub-unit capacity" on the strength
 * of `Kₑ = 1000·Kₘ`. That relation does **not** hold in this build: `Kₘ` is 10⁶ and `Kₑ` is 100, off
 * by exactly this divisor.
 *
 * What it is still good for is what it always was: a **weight** in the conduction solve, where it
 * appears only inside ratios and a node too thin to register is a node with nothing to conduct.
 */
fun heatCapacityAt(masses: MassArray, tile: TileIndex): Long =
    thermalMassAt(masses, tile) / Budget.CAPACITY_DIVISOR

/**
 * `Σ mass × specificHeat`, **undivided** — the quantity a temperature is actually made of.
 *
 * The whole product first and the divisor last, which is the rule this file always meant to state:
 * dividing early rounds a trace gas out of its own heat, and rounds a thin cell out of having a
 * temperature at all.
 */
fun thermalMass(tileCount: Int, masses: MassArray): LongArray =
    LongArray(tileCount) { thermalMassAt(masses, TileIndex(it)) }

/** @see thermalMass */
fun thermalMassAt(masses: MassArray, tile: TileIndex): Long {
    var sum = 0L
    masses.forEachSpecies(tile) { s, mass -> sum += mass * s.specificHeat }
    return sum
}

/** Capacity/energy scale: 1000 (matches Species.specificHeat per-kg → gram units). */
const val CAPACITY_SCALE = 1000L

/**
 * **How hot [energy] is, held by [thermalMass].** The one place a temperature is formed.
 *
 * ⛔ **Divides once, at the end.** `energy × CAPACITY_DIVISOR / thermalMass` through [scaledRatio],
 * which is exact and cannot overflow on the way — as against `energy / (thermalMass /
 * CAPACITY_DIVISOR)`, which throws the denominator away before it is used and is what made thin
 * matter read as room temperature. See [heatCapacityAt].
 *
 * Nothing to hold it reads ambient, as it always has. That branch is now reachable **only** for a
 * genuinely empty tile — `specificHeat` is positive for every species, so `thermalMass` is zero
 * exactly when the mass is — and `StuffLayer.checkInvariants` forbids such a tile from holding
 * energy at all, so there is no longer a hoard hiding behind it.
 */
fun kelvinOf(energy: Long, thermalMass: Long): Int =
    if (thermalMass <= 0L) Temperature.AMBIENT_KELVIN
    else scaledRatio(Budget.CAPACITY_DIVISOR, thermalMass, energy).toInt()

/**
 * **The energy [thermalMass] must hold to read [kelvin]** — the inverse of [kelvinOf], and the only
 * way to seed matter at a stated temperature.
 *
 * ⚠️ The multiply comes before the divide here for the same reason it does there. `capacity ×
 * kelvin` seeds *nothing at all* into a cell too thin to have a capacity, which then reads ambient
 * off an empty store rather than off the gas actually put in it.
 */
fun energyAtKelvin(thermalMass: Long, kelvin: Int): Long =
    scaledRatio(kelvin.toLong(), Budget.CAPACITY_DIVISOR, thermalMass)

/**
 * Gas temperature per tile (kelvin). Empty tiles read AMBIENT_KELVIN — see [kelvinOf].
 *
 * ⚠️ **Takes the masses, not a capacity array.** Every one of the fourteen call sites used to spell
 * out `gasKelvin(energy, heatCapacity(n, masses))`, which is two chances to hand it the wrong array
 * and — more to the point — the shape that pre-divided the denominator. There is nothing to pair up
 * now.
 */
fun gasKelvin(gasEnergy: EnergyArray, masses: MassArray): IntArray =
    IntArray(gasEnergy.size) {
        val tile = TileIndex(it)
        kelvinOf(gasEnergy[tile], thermalMassAt(masses, tile))
    }

/**
 * The energy a tile's gas holds at room temperature — what a filled vessel starts with, and chosen
 * so [gasKelvin] reads it back as ambient.
 */
fun ambientGasEnergy(tileCount: Int, masses: MassArray): EnergyArray =
    EnergyArray(tileCount) { energyAtKelvin(thermalMassAt(masses, TileIndex(it)), Temperature.AMBIENT_KELVIN) }
