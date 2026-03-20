package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class TransformComponent(
    val pos: Coord2,
    val ang: Coord,
)
