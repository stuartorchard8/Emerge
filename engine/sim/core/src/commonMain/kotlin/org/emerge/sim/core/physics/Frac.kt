package org.emerge.sim.core.physics

import kotlin.math.sign

class Frac(n: Int, d: Int = Int.MAX_VALUE) {
    val raw: Int = (n.toULong() * Int.MAX_VALUE.toULong() / d.toULong()).toInt()
    operator fun plus(o: Frac): Frac = Frac(raw+o.raw)
    operator fun minus(o: Frac): Frac = Frac(raw-o.raw)
    operator fun div(o: Int): Frac = Frac(raw/o)
    operator fun div(o: Frac): Frac = Frac((toLong() * Int.MAX_VALUE.toLong() / o.toLong()).toInt())
    operator fun times(o: Frac): Frac = Frac((toLong() * o.toLong() / Int.MAX_VALUE.toLong()).toInt())
    operator fun unaryMinus(): Frac = Frac(-raw)

    fun toFloat(): Float = raw.toFloat() / Int.MAX_VALUE.toFloat() // -1f..1f
    fun toLong(): Long = raw.toLong()
    val sign: Int get() = raw.sign

    fun coerceAtMost(o: Frac) = Frac(raw.coerceAtMost(o.raw))
    fun coerceAtLeast(o: Frac) = Frac(raw.coerceAtLeast(o.raw))
}

fun abs(v : Frac) = Frac(kotlin.math.abs(v.raw))
