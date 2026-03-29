package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class ImpulseComponent(
    val vel: Frac2 = Frac2.zero,
    val pos: Frac2 = Frac2.zero,
    val ang: Frac = Frac(0),
    val angVel: Frac = Frac(0),
) {
    operator fun plus(impulse: ImpulseComponent): ImpulseComponent {
        return ImpulseComponent(
            vel + impulse.vel,
            pos + impulse.pos,
            ang + impulse.ang,
            angVel + impulse.angVel,
        )
    }
}
