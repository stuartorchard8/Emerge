package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class TransformComponent(
    val pos: Frac2,
    val ang: Frac,
)