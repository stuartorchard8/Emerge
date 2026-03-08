package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId

data class Body(
    val playerId: PlayerId,
    val pos: Frac2,
    val vel: Frac2,
    val ang: Frac,
    val angVel: Frac,
    val mass: UInt,
    val radius: Frac,
    val bounce: Frac,
    val rough: Frac,
    val shape: BodyShape,
) {
    companion object {
        fun rocket(
            playerId: PlayerId,
            pos: Frac2,
        ) = Body(
            playerId,
            pos,
            vel = Frac2.zero,
            ang = Frac(0),
            angVel = Frac(0),
            mass = 1000u,
            radius = Frac(1,40),
            bounce = Frac(3, 4),
            rough = Frac(1, 16),
            shape = BodyShape.TRIANGLE,
        )
    }
}

enum class BodyShape(val wireValue: Int) {
    CIRCLE(0),
    TRIANGLE(1);

    companion object {
        fun fromWireValue(value: Int): BodyShape =
            entries.firstOrNull { it.wireValue == value } ?: CIRCLE
    }
}

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 1024),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
)

data class PhysicsState(
    val bodies: Map<PlayerId, Body>,
)

data class PhysicsInput(val thrust: Int, val turn: Int) {
    companion object {
        val ZERO = PhysicsInput(0, 0)
    }
}
