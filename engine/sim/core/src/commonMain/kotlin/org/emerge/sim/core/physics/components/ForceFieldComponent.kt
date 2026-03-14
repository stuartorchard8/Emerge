package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Frac

data class ForceFieldComponent(
    val depth: Frac,
    val strength: Frac,
    val alpha: Frac,
)