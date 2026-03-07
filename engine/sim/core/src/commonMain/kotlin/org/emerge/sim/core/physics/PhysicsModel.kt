package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId

data class CircleBody(
    val playerId: PlayerId,
    val pos: Frac2,
    val vel: Frac2,
    val ang: Frac,
    val angVel: Frac,
    val radius: Int,
)

data class PhysicsConfig(
    val accelFactorInv: Int = Int.MAX_VALUE/(1024*1024),
)

data class PhysicsState(
    val bodies: Map<PlayerId, CircleBody>,
)

data class PhysicsInput(val ax: Int, val ay: Int);
