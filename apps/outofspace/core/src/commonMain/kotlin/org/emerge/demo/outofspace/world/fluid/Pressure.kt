package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Temperature

/**
 * Pressure (moles × T / T_ambient) vs. density (mass): separate so heavy gases sink naturally.
 * Millimoles: 3-figure precision on integer division by molar mass (~34,000 moles/tile air).
 * T scaled by T_ambient (room-temp vessels unchanged; convection emerges from buoyancy comparison).
 */

/** Millimoles per gram, per species. Fixed at startup; read in the hot path. */
private val MILLIMOLES_PER_KILOGRAM: LongArray = LongArray(Species.COUNT) { i ->
    MILLI * MILLI / Species.ALL[i].molarMass
}

private const val MILLI = 1000L

/**
 * The pressure field: millimoles of gas in each tile, scaled by how hot that gas is.
 *
 * [kelvin] is optional and defaults to ambient everywhere, which reproduces the pure-moles field
 * exactly. That default is what lets every test and caller that has no opinion about temperature go
 * on not having one.
 *
 * The multiplication is done before the division so a tile near ambient does not round its way to a
 * different pressure than it had. A tile of air is about 34,000 millimoles and kelvin fits in a few
 * hundred, so the intermediate is comfortably inside a `Long` even for a furnace.
 *
 * [volumes] is the third term of `PV = nRT` and arrived last, for the plainest of reasons: until
 * pipes there was nothing in the world that was not one tile big, and dividing by one leaves no
 * trace. It is optional and null means every cell is a whole tile, which reproduces the pure `n × T`
 * field exactly — the same courtesy [kelvin] extends, and for the same reason. The scaling is applied
 * after the temperature so that a cell at [VolumeField.FULL] multiplies and divides by the same
 * number and lands on precisely the value it had before this parameter existed.
 */
fun tilePressure(
    tileCount: Int,
    grams: LongArray,
    kelvin: IntArray? = null,
    volumes: VolumeField? = null,
    species: List<Species> = Species.GASES,
): LongArray =
    LongArray(tileCount) { tile ->
        val moles = millimolesOf(grams, tile, species)
        val hot = if (kelvin == null) moles else moles * kelvin[tile] / AMBIENT_KELVIN
        if (volumes == null) hot else hot * VolumeField.FULL / volumes.at(tile)
    }

/** The temperature [tilePressure] measures against — one atmosphere at room temperature. */
private const val AMBIENT_KELVIN = Temperature.AMBIENT_KELVIN.toLong()

/** The pressure of a single tile, for callers that want one rather than the whole field. */
fun millimolesOf(grams: LongArray, tile: Int, species: List<Species> = Species.GASES): Long {
    val base = tile * Species.COUNT
    var sum = 0L
    for (s in species) sum += grams[base + s.ordinal] * MILLIMOLES_PER_KILOGRAM[s.ordinal] / MILLI
    return sum
}

/** A tile of ordinary air at one atmosphere, in the units [tilePressure] returns. */
val AMBIENT_PRESSURE: Long = run {
    var sum = 0L
    for (s in Species.GASES) {
        sum += AirField.AMBIENT_AIR[s] * MILLIMOLES_PER_KILOGRAM[s.ordinal] / MILLI
    }
    sum
}
