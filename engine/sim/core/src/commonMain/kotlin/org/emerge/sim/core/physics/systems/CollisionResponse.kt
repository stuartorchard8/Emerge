package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.physics.primitives.Frac

internal data class NormalCollisionResponse(
    val deltaVelA: Frac,
    val deltaVelB: Frac,
)

internal fun solveNormalCollisionResponse(
    massA: UInt,
    massB: UInt,
    closingSpeedAlongNormal: Frac,
    restitution: Frac,
): NormalCollisionResponse {
    if (closingSpeedAlongNormal.raw <= 0L) {
        return NormalCollisionResponse(
            deltaVelA = Frac(0),
            deltaVelB = Frac(0),
        )
    }

    val massALong = massA.toLong()
    val massBLong = massB.toLong()
    val totalMass = (massALong + massBLong).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    val invMassWeightA = Frac(massBLong, totalMass)
    val invMassWeightB = Frac(massALong, totalMass)

    // 1D restitution solve along the contact normal:
    // e = 0 -> post-collision normal velocities match (fully inelastic),
    // e = 1 -> elastic collision (relative normal speed is preserved and inverted).
    val response = (Frac(1, 1) + restitution) * closingSpeedAlongNormal
    return NormalCollisionResponse(
        deltaVelA = response * invMassWeightA,
        deltaVelB = response * invMassWeightB,
    )
}
