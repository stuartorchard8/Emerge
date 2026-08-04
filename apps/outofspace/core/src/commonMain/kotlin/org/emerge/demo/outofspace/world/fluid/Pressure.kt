package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField

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
 * Temperature is deliberately still absent. `P ∝ nT` is what turns a hot cell into an expanding one
 * and is the direct route from combustion to thrust, but it belongs with heat coming back on in
 * increment D rather than being smuggled in with the plumbing. Where it will go is marked.
 */

/** Millimoles per gram, per species. Fixed at startup; read in the hot path. */
private val MILLIMOLES_PER_KILOGRAM: LongArray = LongArray(Species.COUNT) { i ->
    MILLI * MILLI / Species.ALL[i].molarMass
}

private const val MILLI = 1000L

/**
 * Millimoles of gas in each tile — the pressure field, up to a temperature that does not exist yet.
 *
 * Where temperature arrives, this becomes `n × T / T_ambient` and nothing else has to change: every
 * reader below already treats it as "pressure", not as "an amount of stuff".
 */
fun tilePressure(tileCount: Int, grams: LongArray, species: List<Species> = Species.GASES): LongArray =
    LongArray(tileCount) { tile ->
        val base = tile * Species.COUNT
        var sum = 0L
        for (s in species) sum += grams[base + s.ordinal] * MILLIMOLES_PER_KILOGRAM[s.ordinal] / MILLI
        sum
    }

/** A tile of ordinary air at one atmosphere, in the units [tilePressure] returns. */
val AMBIENT_PRESSURE: Long = run {
    var sum = 0L
    for (s in Species.GASES) {
        sum += AirField.AMBIENT_AIR[s] * MILLIMOLES_PER_KILOGRAM[s.ordinal] / MILLI
    }
    sum
}
