package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.contacts
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput


object BounceSystem : EcsSystem<PhysicsConfig, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
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

            val massA = aMaterial.mass.toLong()
            val massB = bMaterial.mass.toLong()
            val totalMass = (massA + massB).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val invMassWeightA = Frac(massB, totalMass)
            val invMassWeightB = Frac(massA, totalMass)

            val pushA = normal * (pen * invMassWeightA)
            val pushB = -normal * (pen * invMassWeightB)

            val velDelta = bMotion.vel - aMotion.vel
            val velAlongNorm = velDelta.dot(normal)
            val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
            val normResponse = if (velAlongNorm.sign > 0) velAlongNorm * bounciness else Frac(0)

            val roughness = aMaterial.rough.coerceAtMost(bMaterial.rough)
            val circumferenceA = aCollider.radius.toCircumference()
            val circumferenceB = bCollider.radius.toCircumference()
            val spinAlongTangent = Frac(aMotion.angVel.raw.toLong()) * circumferenceA + Frac(bMotion.angVel.raw.toLong()) * circumferenceB
            val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
            val tangentResponse = velAlongTangent * roughness

            // Multiply by 2 so that bounciness of 1 results in full momentum transfer.
            val pushVelA = Frac((normResponse * invMassWeightA).raw*2)
            val pushVelB = Frac((normResponse * invMassWeightB).raw*2)
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
