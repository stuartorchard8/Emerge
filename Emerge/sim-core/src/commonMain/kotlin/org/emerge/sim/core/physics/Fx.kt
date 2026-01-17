package org.emerge.sim.core.physics

/**
 * Deterministic fixed-point number: raw Int scaled by [SCALE].
 * This avoids cross-platform floating point drift (JVM vs JS).
 */
data class Fx(val raw: Int) {
    companion object {
        const val SCALE: Int = 1000

        fun fromInt(v: Int): Fx = Fx(v * SCALE)
        fun fromRaw(raw: Int): Fx = Fx(raw)
    }

    fun toIntFloor(): Int = raw / SCALE

    operator fun plus(o: Fx): Fx = Fx(raw + o.raw)
    operator fun minus(o: Fx): Fx = Fx(raw - o.raw)
    operator fun unaryMinus(): Fx = Fx(-raw)

    operator fun times(o: Fx): Fx = Fx(((raw.toLong() * o.raw.toLong()) / SCALE).toInt())
    operator fun div(o: Fx): Fx = Fx(((raw.toLong() * SCALE) / o.raw.toLong()).toInt())

    operator fun compareTo(o: Fx): Int = raw.compareTo(o.raw)
}

fun fxInt(v: Int): Fx = Fx.fromInt(v)

