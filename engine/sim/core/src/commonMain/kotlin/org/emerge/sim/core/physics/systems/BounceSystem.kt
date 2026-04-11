package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.set


object BounceSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        for (contact in state.raw.contacts) {
            val aId = contact.aId
            val bId = contact.bId
            val aMaterial = state.raw.materials[aId] ?: continue
            val bMaterial = state.raw.materials[bId] ?: continue
            val aMotion = state.raw.motions[aId] ?: continue
            val bMotion = state.raw.motions[bId] ?: continue
            val aCollider = state.raw.colliders[aId] ?: continue
            val bCollider = state.raw.colliders[bId] ?: continue
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
            impulses[aId] = impulses[aId]?.plus(impulseA) ?: impulseA
            impulses[bId] = impulses[bId]?.plus(impulseB) ?: impulseB
        }

        state.addImpulses(impulses)
        state.setContacts(listOf())
    }
}
