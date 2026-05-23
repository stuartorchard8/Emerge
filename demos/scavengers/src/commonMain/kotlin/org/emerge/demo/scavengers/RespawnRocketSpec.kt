package org.emerge.demo.scavengers

import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac

data class RespawnRocketSpec(
    val mass: UInt,
    val radius: Frac,
    val bounce: Frac,
    val rough: Frac,
    val shape: BodyShape,
)
