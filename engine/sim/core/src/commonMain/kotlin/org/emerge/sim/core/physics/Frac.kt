package org.emerge.sim.core.physics

class Frac(n: Int, d: UInt = UInt.MAX_VALUE) {
    val raw: Int = (n.toULong() * UInt.MAX_VALUE / d).toInt()
    operator fun plus(o: Frac): Frac = Frac(raw+o.raw)
    operator fun div(o: Int): Frac = Frac(raw/o)

    fun toFloat(): Float = raw.toFloat() / Int.MAX_VALUE.toFloat() // -1f..1f
}
