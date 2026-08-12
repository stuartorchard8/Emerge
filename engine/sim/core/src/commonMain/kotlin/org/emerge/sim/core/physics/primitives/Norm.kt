package org.emerge.sim.core.physics.primitives

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


    /**
     * This direction as an angle. Exact to within one [Coord] raw — see [Trig].
     *
     * Cheap enough now to stop being something to route around, but it is still the *derived* form:
     * a direction that is stored as a direction and rotated as one never needs this at all, and code
     * that reaches for it every tick is usually one step away from being simpler without it.
     */
    val asAngle by lazy { Coord(Trig.atan2(y.raw, x.raw)) }

    companion object {
        /**
         * The unit vector at [angle] — `(cos, sin)` — computed in integer arithmetic by [Trig].
         *
         * Correctly rounded, and identical on every platform the engine runs on. The `Float`
         * implementation this replaces was neither; [Trig]'s KDoc has the measurements and the
         * lockstep argument.
         */
        fun fromAngle(angle: Coord): Norm {
            val cs = Trig.cosSin(angle)
            return Norm(Frac(cs[0]), Frac(cs[1]))
        }
    }
}
