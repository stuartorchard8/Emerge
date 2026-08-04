package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Temperature

/**
 * What sets pressure, and what sets weight — which are not the same quantity.
 *
 * The existing atmosphere treats total mass as pressure, on the grounds that every tile is the same
 * volume so mass is density. That is right about density and wrong about pressure, and the
 * difference is exactly what makes carbon dioxide interesting. Pressure goes as the number of
 * *particles* — `PV = nRT` — while density goes as their mass. A tile of CO₂ and a tile of nitrogen
 * at the same mass hold very different numbers of moles, so they sit at different pressures; and a
 * tile of each at the same *pressure* has very different weight.
 *
 * That single distinction is what makes the heavy gas sink without anybody writing a rule saying it
 * should. `stratifyColumns` exists today because mass-as-pressure cannot express it: if pressure is
 * mass then a dense gas is also a high-pressure gas and it pushes back exactly as hard as it is
 * pulled down, so it never settles and the sorting has to be done by hand. Separate the two and the
 * heavy gas is dense but not over-pressured, gravity wins, and it pools at the floor by itself.
 *
 * Moles are counted in **millimoles** so that integer division by a molar mass keeps three figures
 * of precision. A tile of ordinary air is about 34,000 of them, which is plenty of resolution and
 * nowhere near troubling a `Long`.
 *
 * Temperature arrived with increment D, and it entered exactly where this file said it would: the
 * field below is `n × T / T_ambient`, so its units are unchanged and every reader downstream still
 * sees "pressure" and needs no adjustment. That scaling is deliberate — dividing by ambient rather
 * than working in absolute kelvin keeps a room at room temperature reading exactly its old value, so
 * turning temperature on changed nothing about a vessel sitting still, and everything about one on
 * fire.
 *
 * What it buys is the whole of convection, for one multiplication. A warmed tile is more moles'
 * worth of pressure at the same mass; [applyBuoyancy] compares mass against pressure and finds the
 * parcel light for what it is pushing, so it rises. Nothing anywhere says "hot air rises".
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
