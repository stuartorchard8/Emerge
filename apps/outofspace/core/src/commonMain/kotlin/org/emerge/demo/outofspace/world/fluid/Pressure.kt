package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.CLOSE_PACKED
import org.emerge.demo.outofspace.chem.CRITICAL
import org.emerge.demo.outofspace.chem.SCALE
import org.emerge.demo.outofspace.chem.liquidVolumeFraction
import org.emerge.demo.outofspace.chem.partialPressure
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
    species: List<Species> = Species.FLUIDS,
): LongArray =
    LongArray(tileCount) { tile ->
        val hot = kelvin?.get(tile) ?: Temperature.AMBIENT_KELVIN
        val room = volumes?.at(tile) ?: VolumeField.FULL

        // First pass: how much of the cell is taken up by liquid, and so is not room for gas. Zero
        // for everything the vessel carries today, which is what makes this free of consequence
        // until something actually condenses — see [liquidVolumeFraction] for why it has to exist
        // at all.
        var liquidShare = 0L
        for (s in species) {
            val g = grams[tile * Species.COUNT + s.ordinal]
            if (g <= 0L) continue
            liquidShare += liquidVolumeFraction(g, s, room, VolumeField.FULL, hot)
        }
        // Floored rather than allowed to reach zero: a cell packed entirely with liquid has no room
        // for gas at all, and the honest rendering of "a gas squeezed into no volume" is a division
        // by zero. The floor makes it merely a very large pressure, which is both finite and the
        // right direction — that gas is being crushed, and the solver should feel it and push back.
        val gasRoom = (room - room * minOf(liquidShare, SCALE) / SCALE).coerceAtLeast(1L).toInt()

        var sum = 0L
        for (s in species) {
            val g = grams[tile * Species.COUNT + s.ordinal]
            // A condensing species is measured against the whole cell, because the lever rule has
            // already divided that cell between its own liquid and its own vapour — the volume it
            // is competing for is the volume it is itself defining. Everything else gets what is
            // left over.
            val wanted = if (liquidVolumeFraction(g, s, room, VolumeField.FULL, hot) > 0L) room else gasRoom
            // ...but never squeezed past close packing, which is where the equation of state stops
            // having an answer and [vanDerWaalsPressure] throws rather than returning one. The floor
            // above says "a very large pressure"; without this it says "a crash", and a cell can now
            // genuinely reach that state — gas advected into a tile that is nearly solid with liquid
            // has almost no room to be in, and a handful of grams in a thousandth of a tile is past
            // the limit. Clamped to the densest that species can physically be, the pressure comes
            // out enormous and finite, which is what the comment above always intended and what the
            // solver needs in order to push the gas back out.
            val mine = maxOf(wanted, leastRoomFor(g, s))
            sum += partialPressure(g, s, hot, mine, VolumeField.FULL) ?: idealPressure(g, s, hot, mine)
        }
        sum
    }

/**
 * The smallest volume [grams] of [species] can be squeezed into and still have a pressure: the
 * volume at which it reaches [CLOSE_PACKED].
 *
 * Inverts [org.emerge.demo.outofspace.chem.reducedDensity]. Zero for a species with no critical
 * point on file, which has no packing limit to reach.
 */
private fun leastRoomFor(grams: Long, species: Species): Int {
    if (grams <= 0L) return 0
    val critical = CRITICAL[species] ?: return 0
    // volume such that grams x SCALE / gramsPerTile x FULL / volume < CLOSE_PACKED, rounded up so
    // the strict inequality holds rather than merely being approached.
    val room = grams * SCALE / critical.gramsPerTile * VolumeField.FULL / (CLOSE_PACKED - 1) + 1
    return room.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * The pressure a mass of ordinary air would exert on its own, at [kelvin] in a cell holding
 * [volume] — the reference curve [ambientMassAtPressure] inverts.
 *
 * The mass is split across the species in [AirField.AMBIENT_AIR]'s proportions, because the question
 * being asked of it is always "what would *air* do here", never "what would this particular gas do".
 */
internal fun ambientPressureOf(grams: Long, kelvin: Int, volume: Int): Long {
    if (grams <= 0L) return 0L
    var sum = 0L
    for (s in Species.GASES) {
        val share = grams * AirField.AMBIENT_AIR[s] / AMBIENT_TILE_GRAMS
        sum += partialPressure(share, s, kelvin, volume, VolumeField.FULL)
            ?: idealPressure(share, s, kelvin, volume)
    }
    return sum
}

/**
 * How much ordinary air it would take to reach [target] pressure at [kelvin] in a cell of [volume] —
 * the equation of state run backwards.
 *
 * [applyBuoyancy] needs this to ask whether a tile is heavier than the air around it *at the same
 * pressure*, and for as long as the solver used the ideal gas law it could be had for a single
 * multiply, because `P = nRT/V` is a straight line through the origin and a straight line is its own
 * inverse up to a constant. Van der Waals is a cubic, so the multiply became an approximation, and
 * in a cell squeezed to an eighth of a tile it was wrong by enough to leave a standing impulse under
 * every face — ordinary air in a pipe reading as permanently heavy and permanently trying to fall.
 *
 * Newton's method from the old linear answer, which is an excellent starting guess precisely because
 * it is exact in the sparse limit where most of the vessel lives. Two steps carry the dense cases;
 * the loop exits early once it lands, which for a room at ordinary density is immediately.
 */
internal fun ambientMassAtPressure(target: Long, kelvin: Int, volume: Int): Long {
    if (target <= 0L) return 0L
    val ceiling = closePackedAirGrams(volume)
    var grams = (target * AMBIENT_TILE_GRAMS / AMBIENT_PRESSURE * volume / VolumeField.FULL)
        .coerceAtMost(ceiling)
    repeat(NEWTON_STEPS) {
        val here = ambientPressureOf(grams, kelvin, volume)
        if (here == target) return grams
        // A thousandth of the current guess is small enough to be a local slope and large enough
        // that the pressure difference across it does not vanish into integer rounding.
        val nudge = (grams / 1000L).coerceAtLeast(1L)
        val slope = ambientPressureOf((grams + nudge).coerceAtMost(ceiling), kelvin, volume) - here
        if (slope <= 0L) return grams
        grams = (grams - (here - target) * nudge / slope).coerceAtMost(ceiling)
        if (grams <= 0L) return 0L
    }
    return grams
}

/**
 * The most ordinary air a cell of [volume] could possibly hold — the mass at which its densest
 * component reaches [CLOSE_PACKED] and the equation of state stops having an answer.
 *
 * [ambientMassAtPressure] needs this because it runs the equation *backwards*, and a backwards
 * question can be asked that forwards has no answer: "how much air would it take to reach this
 * pressure" has no solution once the pressure exceeds what close-packed air exerts. That used to be
 * unreachable, since nothing generated pressures that large. A cell that is nearly all liquid does
 * — the gas sharing it is squeezed into almost no volume — and without this the Newton step walks
 * straight past the limit and [vanDerWaalsPressure] throws mid-tick.
 *
 * Clamping rather than throwing is right because the answer is being used to ask whether a tile is
 * heavier than the air around it. At the clamp the answer is "very much heavier", which is both
 * true and the direction the solver needs.
 */
private fun closePackedAirGrams(volume: Int): Long {
    var limit = Long.MAX_VALUE
    for (s in Species.GASES) {
        val share = AirField.AMBIENT_AIR[s]
        if (share <= 0L) continue
        val critical = CRITICAL[s] ?: continue
        // Invert reducedDensity: the total air mass whose share of species s just reaches close
        // packing in this volume.
        val atLimit = (CLOSE_PACKED - 1) / SCALE * critical.gramsPerTile *
            volume / VolumeField.FULL * AMBIENT_TILE_GRAMS / share
        limit = minOf(limit, atLimit)
    }
    return if (limit == Long.MAX_VALUE) Long.MAX_VALUE else limit
}

/**
 * Two is enough because the starting guess is the exact answer wherever the gas is thin, and the
 * correction it needs elsewhere is a percent or so — Newton doubles its correct digits each step, so
 * a third would only be spending time to confirm the second.
 */
private const val NEWTON_STEPS = 2

/**
 * The old law, kept for species with no critical point on file.
 *
 * Anything the vessel never gets near condensing has no need of an equation of state that can
 * describe condensing, and this is both cheaper and exactly what the solver used to do. It is also
 * what [partialPressure] converges to as a cell empties out, which is why swapping one for the other
 * moved no existing pressure by more than a tenth of a percent.
 */
private fun idealPressure(grams: Long, species: Species, kelvin: Int, volume: Int): Long {
    val moles = grams * MILLIMOLES_PER_KILOGRAM[species.ordinal] / MILLI
    return moles * kelvin / AMBIENT_KELVIN * VolumeField.FULL / volume
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

/**
 * A tile of ordinary air at one atmosphere, in the units [tilePressure] returns.
 *
 * Computed through the same law as the field it is compared against, which matters more than it
 * looks: [applyBuoyancy] divides one by the other, so a reference derived from a different equation
 * of state than the pressures it scales would put a small standing bias under every cell in the
 * vessel.
 */
val AMBIENT_PRESSURE: Long = run {
    var sum = 0L
    for (s in Species.GASES) {
        val grams = AirField.AMBIENT_AIR[s]
        sum += partialPressure(grams, s, Temperature.AMBIENT_KELVIN, VolumeField.FULL, VolumeField.FULL)
            ?: (grams * MILLIMOLES_PER_KILOGRAM[s.ordinal] / MILLI)
    }
    sum
}
