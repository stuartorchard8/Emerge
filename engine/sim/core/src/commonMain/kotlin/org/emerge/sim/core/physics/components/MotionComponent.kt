package org.emerge.sim.core.physics.components

import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Norm

data class MotionComponent(
    val vel: Coord2,
    val angVel: Coord,
) {
    fun surfaceVelocityAtOffset(
        normal: Norm,
        distance: Frac,
    ): Coord2 {
        val tangent = normal.cw90
        val spinSpeed = distance.toCircumference() * Frac(angVel.raw.toLong())
        return vel - tangent * spinSpeed
    }
}
