package org.emerge.sim.core.physics.primitives

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

data class Norm(val x: Frac, val y: Frac) {
    operator fun unaryMinus(): Norm = Norm(-x, -y)
    operator fun times(s: Frac): Frac2 = Frac2(
        x*s,
        y*s,
    )
    fun dot(other: Norm): Frac = (
        x*other.x +
        y*other.y
    )
    val cw90 by lazy { Norm(y, -x) }


    val asAngle by lazy {
        // TODO: integer atan2?
        val angleTurns = atan2(y.toFloat(), x.toFloat()) / PI.toFloat()
        Coord((angleTurns * Int.MAX_VALUE.toFloat()).roundToInt())
    }

    companion object {
        fun fromAngle(angle: Coord): Norm {
            // TODO: integer cos & sin?
            val rad: Float = (angle.raw.toFloat() / Int.MAX_VALUE.toFloat()) * PI.toFloat()
            return Norm(
                Frac((cos(rad)*Int.MAX_VALUE.toFloat()).roundToLong()),
                Frac((sin(rad)*Int.MAX_VALUE.toFloat()).roundToLong()),
            )
        }
    }
}
