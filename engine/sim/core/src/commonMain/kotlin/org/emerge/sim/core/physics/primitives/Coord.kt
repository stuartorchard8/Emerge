package org.emerge.sim.core.physics.primitives

class Coord(n: Int, d: Int = Int.MAX_VALUE) {
    val raw: Int = (n.toLong() * Int.MAX_VALUE.toLong() / d.toLong()).toInt()
    operator fun plus(other: Frac): Coord = Coord((raw.toLong() + other.raw).toInt())   // TODO confirm negative wrapping
    operator fun minus(other: Frac): Coord = Coord((raw.toLong() - other.raw).toInt())  // TODO confirm negative wrapping
    operator fun minus(other: Coord): Frac = Frac((raw - other.raw).toLong())

    fun toFloat(): Float = raw.toFloat() / Int.MAX_VALUE.toFloat() // -1f..1f

    companion object {
        fun lerp(a: Coord, b: Coord, v: Coord): Coord = Coord(0) + (
            Frac(a.raw.toLong()) * Frac(v.raw.toLong()) +
            Frac(b.raw.toLong()) * Frac(Int.MAX_VALUE.toLong() - v.raw)
        )
    }
}

data class Coord2(val x: Coord, val y: Coord) {
    operator fun plus(other: Frac2): Coord2 = Coord2(x + other.x, y + other.y)
    operator fun minus(other: Frac2): Coord2 = Coord2(x - other.x, y - other.y)
    operator fun minus(other: Coord2): Frac2 = Frac2(x - other.x, y - other.y)
    fun asFrac2() = Frac2(Frac(x.raw.toLong()), Frac(y.raw.toLong()))

    companion object {
        val zero get() = Coord2(
            Coord(0),
            Coord(0),
        )
        fun raw(x: Int, y: Int) = Coord2(Coord(x), Coord(y))
        fun lerp(a: Coord2, b: Coord2, v: Coord) = Coord2(Coord.lerp(a.x,b.x,v), Coord.lerp(a.y,b.y,v))
    }
}