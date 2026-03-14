package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class MotionComponent(
    val vel: Frac2,
    val angVel: Frac,
)