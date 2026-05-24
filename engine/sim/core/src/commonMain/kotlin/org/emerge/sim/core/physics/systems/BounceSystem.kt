package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.contacts
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.SimInput


object BounceSystem : EcsSystem<PhysicsTuning, SimState, SimInput> {

    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        for (contact in builder.contacts) {
            val aId = contact.aId
            val bId = contact.bId
            val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
            val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
            val aMotion = builder.getComponent<MotionComponent>(aId) ?: continue
            val bMotion = builder.getComponent<MotionComponent>(bId) ?: continue
            val aCollider = builder.getComponent<ColliderComponent>(aId) ?: continue
            val bCollider = builder.getComponent<ColliderComponent>(bId) ?: continue
            val normal = contact.normal
            val tangent = contact.tangent
            val pen = contact.penetration

            val massA = aMaterial.mass
            val massB = bMaterial.mass
            val massALong = massA.toLong()
            val massBLong = massB.toLong()
            val totalMass = (massALong + massBLong).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val invMassWeightA = Frac(massBLong, totalMass)
            val invMassWeightB = Frac(massALong, totalMass)

            val pushA = normal * (pen * invMassWeightA)
            val pushB = -normal * (pen * invMassWeightB)

            val velDelta = bMotion.vel - aMotion.vel
            val velAlongNorm = velDelta.dot(normal)
            val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
            val normalResponse = solveNormalCollisionResponse(
                massA = massA,
                massB = massB,
                closingSpeedAlongNormal = velAlongNorm,
                restitution = bounciness,
            )

            val roughness = aMaterial.rough.coerceAtMost(bMaterial.rough)
            val circumferenceA = aCollider.radius.toCircumference()
            val circumferenceB = bCollider.radius.toCircumference()
            val spinAlongTangent = Frac(aMotion.angVel.raw.toLong()) * circumferenceA + Frac(bMotion.angVel.raw.toLong()) * circumferenceB
            val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
            val tangentResponse = velAlongTangent * roughness

            val pushVelA = normalResponse.deltaVelA
            val pushVelB = normalResponse.deltaVelB
            val pushNormVelA = normal * pushVelA
            val pushNormVelB = -normal * pushVelB

            val tangentResponseA = (tangentResponse * invMassWeightA) / 2
            val tangentResponseB = (tangentResponse * invMassWeightB) / 2
            val pushTangentialVelA = tangent * tangentResponseA
            val pushTangentialVelB = -tangent * tangentResponseB
            val pushAngVelA = tangentResponseA / circumferenceA
            val pushAngVelB = tangentResponseB / circumferenceB

            val impulseA = ImpulseComponent(
                pos = pushA,
                vel = pushNormVelA + pushTangentialVelA,
                angVel = pushAngVelA
            )
            val impulseB = ImpulseComponent(
                pos = pushB,
                vel = pushNormVelB + pushTangentialVelB,
                angVel = pushAngVelB
            )
            builder.update<ImpulseComponent>(aId) { impulseA + it }
            builder.update<ImpulseComponent>(bId) { impulseB + it }
        }
    }
}
