package org.emerge.demo.drockets

import kotlin.math.roundToLong

/**
 * A drocket's chromosome — a fixed shape of typed integer-encoded genes.
 *
 * Each field is a raw uint-mapped Int in the full Int range. Gameplay code reads
 * the decoded form via [phenotype]; mutation operates on raw values (small int
 * deltas) so repeated mutation keeps the distribution approximately uniform.
 *
 * Stored in the ECS as [GenomeComponent].
 */
data class Genome(
    val aiWalkMinTicks: Int = AI_WALK_MIN_TICKS.encode(120),
    val aiWalkMaxTicks: Int = AI_WALK_MAX_TICKS.encode(600),
    val aiChargeTicks: Int = AI_CHARGE_TICKS.encode(18),
    val aiFuelTicks: Int = AI_FUEL_TICKS.encode(200),
    val aiSpin: Int = AI_SPIN.encode(Int.MAX_VALUE / 120),
    val aiThrust: Int = AI_THRUST.encode(Int.MAX_VALUE / (1 shl 18)),
    val bodyColor: HsvColorGene = HsvColorGene(
        rawH = BODY_COLOR_H.encode(0),
        rawS = BODY_COLOR_S.encode(1000),
        rawV = BODY_COLOR_V.encode(1000),
    ),
    val fireColor: HsvColorGene = HsvColorGene(
        rawH = FIRE_COLOR_H.encode(0),
        rawS = FIRE_COLOR_S.encode(1000),
        rawV = FIRE_COLOR_V.encode(1000),
    ),
) {
    fun phenotype(): Phenotype = Phenotype(
        aiWalkMinTicks = AI_WALK_MIN_TICKS.decode(aiWalkMinTicks),
        aiWalkMaxTicks = AI_WALK_MAX_TICKS.decode(aiWalkMaxTicks),
        aiChargeTicks = AI_CHARGE_TICKS.decode(aiChargeTicks),
        aiFuelTicks = AI_FUEL_TICKS.decode(aiFuelTicks),
        aiSpin = AI_SPIN.decode(aiSpin),
        aiThrust = AI_THRUST.decode(aiThrust),
        bodyColor = HsvColor(
            h = BODY_COLOR_H.decode(bodyColor.rawH),
            s = BODY_COLOR_S.decode(bodyColor.rawS),
            v = BODY_COLOR_V.decode(bodyColor.rawV),
        ),
        fireColor = HsvColor(
            h = FIRE_COLOR_H.decode(fireColor.rawH),
            s = FIRE_COLOR_S.decode(fireColor.rawS),
            v = FIRE_COLOR_V.decode(fireColor.rawV),
        ),
    )

    companion object {
        val AI_WALK_MIN_TICKS = GeneRange(min = 1, max = 6_000)
        val AI_WALK_MAX_TICKS = GeneRange(min = 1, max = 6_000)
        val AI_CHARGE_TICKS = GeneRange(min = 1, max = 6_000)
        val AI_FUEL_TICKS = GeneRange(min = 1, max = 400)
        val AI_SPIN = GeneRange(min = -Int.MAX_VALUE / 64, max = Int.MAX_VALUE / 64)
        val AI_THRUST = GeneRange(min = Int.MAX_VALUE / (1 shl 19), max = Int.MAX_VALUE / (1 shl 17))
        val BODY_COLOR_H = GeneRange(min = 0, max = 360)
        val BODY_COLOR_S = GeneRange(min = 0, max = 1000)
        val BODY_COLOR_V = GeneRange(min = 200, max = 1000)
        val FIRE_COLOR_H = GeneRange(min = 0, max = 360)
        val FIRE_COLOR_S = GeneRange(min = 0, max = 1000)
        val FIRE_COLOR_V = GeneRange(min = 0, max = 1000)
    }
}

/** Decoded view of a [Genome] — values usable directly by gameplay code. */
data class Phenotype(
    val aiWalkMinTicks: Int,
    val aiWalkMaxTicks: Int,
    val aiChargeTicks: Int,
    val aiFuelTicks: Int,
    val aiSpin: Int,
    val aiThrust: Int,
    val bodyColor: HsvColor,
    val fireColor: HsvColor,
)

/** Three coupled HSV genes — H, S, V stored as raw uint-mapped Ints. */
data class HsvColorGene(
    val rawH: Int,
    val rawS: Int,
    val rawV: Int,
)

/** Decoded HSV with hue in [0, 360] and S, V in [0, 1000]. */
data class HsvColor(
    val h: Int,
    val s: Int,
    val v: Int,
)

/**
 * Maps a raw uint-style Int uniformly to a decoded value in `[min, max]`.
 *
 * Mutation adds small integer deltas to raw values; because the raw space is
 * the full Int range, small deltas correspond to small phenotype shifts.
 */
data class GeneRange(val min: Int, val max: Int) {
    fun encode(decoded: Int): Int {
        val clamped = decoded.coerceIn(min, max)
        val span = (max - min).coerceAtLeast(1)
        val norm = (clamped - min).toDouble() / span.toDouble()
        return (Int.MIN_VALUE.toLong() + (norm * UINT_RANGE).roundToLong()).toInt()
    }

    fun decode(raw: Int): Int {
        val norm = ((raw.toLong() - Int.MIN_VALUE.toLong()) / UINT_RANGE).coerceIn(0.0, 1.0)
        return (min + ((max - min) * norm)).toInt().coerceIn(min, max)
    }

    companion object {
        private const val UINT_RANGE = 4294967295.0
    }
}

data class GenomeComponent(val genome: Genome = Genome())
