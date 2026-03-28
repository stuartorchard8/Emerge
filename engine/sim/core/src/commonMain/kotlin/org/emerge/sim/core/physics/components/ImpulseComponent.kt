package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class ImpulseComponent(
    val vel: Frac2,
    val pos: Frac2,
    val ang: Frac,
) {
    operator fun plus(impulse: ImpulseComponent): ImpulseComponent {
        return ImpulseComponent(
            vel + impulse.vel,
            pos + impulse.pos,
            ang + impulse.ang,
        )
    }

    companion object {
        val ZERO = ImpulseComponent(
            vel = Frac2.zero,
            pos = Frac2.zero,
            ang = Frac(0),
        )
    }
}
