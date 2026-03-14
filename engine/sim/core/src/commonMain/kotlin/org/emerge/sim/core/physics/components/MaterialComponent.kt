package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Frac

data class MaterialComponent(
    val mass: UInt,
    val bounce: Frac,
    val rough: Frac,
)