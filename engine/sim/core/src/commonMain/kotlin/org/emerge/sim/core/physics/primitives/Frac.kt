package org.emerge.sim.core.physics.primitives

import kotlin.math.sign

class Frac(n: Int, d: Int = Int.MAX_VALUE) {
    val raw: Int = (n.toLong() * Int.MAX_VALUE.toLong() / d.toLong()).toInt()
    operator fun plus(o: Frac): Frac = Frac(raw+o.raw)
    operator fun minus(o: Frac): Frac = Frac(raw-o.raw)
    operator fun div(o: Int): Frac = Frac(raw/o)
    operator fun div(o: Frac): Frac = Frac((toLong() * Int.MAX_VALUE.toLong() / o.toLong()).toInt())
    operator fun times(o: Frac): Frac = Frac((toLong() * o.toLong() / Int.MAX_VALUE.toLong()).toInt())
    operator fun unaryMinus(): Frac = Frac(-raw)

    fun toFloat(): Float = raw.toFloat() / Int.MAX_VALUE.toFloat() // -1f..1f
    fun toLong(): Long = raw.toLong()
    fun toCircumference() = Frac((this*PIon4).raw*8)
    val sign: Int get() = raw.sign

    fun coerceAtMost(o: Frac) = Frac(raw.coerceAtMost(o.raw))
    fun coerceAtLeast(o: Frac) = Frac(raw.coerceAtLeast(o.raw))

    companion object {
        val PIon4: Frac = Frac(1686629713)
        fun abs(v : Frac) = Frac(kotlin.math.abs(v.raw))
    }
}
