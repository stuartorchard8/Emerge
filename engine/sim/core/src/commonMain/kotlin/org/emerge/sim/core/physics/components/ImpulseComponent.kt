package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class ImpulseComponent(
    val pos: Frac2 = Frac2.zero,
    val vel: Frac2 = Frac2.zero,
    val angVel: Frac = Frac(0),
) {
    operator fun plus(impulse: ImpulseComponent?): ImpulseComponent {
        return ImpulseComponent(
            pos + impulse?.pos,
            vel + impulse?.vel,
            angVel + impulse?.angVel,
        )
    }
}
