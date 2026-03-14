package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.components.ControlIntentComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.collections.set


object CollisionSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val LANDING_ALIGNMENT_THRESHOLD = Frac(1, 2)

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val transforms = LinkedHashMap(state.transforms.asMap())
        val motions = LinkedHashMap(state.motions.asMap())
        val landings = LinkedHashMap(state.landings.asMap())
        val ids = state.world.entities
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (landings.containsKey(aId) || landings.containsKey(bId)) continue
                val aTransform = transforms[aId] ?: continue
                val bTransform = transforms[bId] ?: continue
                val aMotion = motions[aId] ?: continue
                val bMotion = motions[bId] ?: continue
                val aCollider = state.colliders[aId] ?: continue
                val bCollider = state.colliders[bId] ?: continue
                val aMaterial = state.materials[aId] ?: continue
                val bMaterial = state.materials[bId] ?: continue
                val aShape = state.renderShapes[aId] ?: continue
                val bShape = state.renderShapes[bId] ?: continue
                val aControl = state.controls[aId] ?: ControlIntentComponent.ZERO
                val bControl = state.controls[bId] ?: ControlIntentComponent.ZERO

                val bodyContact = Contact.compute(
                    aTransform = aTransform,
                    bTransform = bTransform,
                    aRadius = aCollider.radius,
                    bRadius = bCollider.radius,
                )
                val contact = bodyContact ?: continue
                val normal = contact.normal
                val tangent = contact.tangent
                val minDist = contact.minDist
                val pen = contact.penetration

                // Each collision pair can land in either direction: a-on-b or b-on-a.
                val aLanding = tryLand(
                    supportId = bId,
                    rocketShape = aShape,
                    rocketControl = aControl,
                    supportShape = bShape,
                    supportTransform = bTransform,
                    rocketTransform = aTransform,
                    landingNormal = normal,
                    minDist = minDist,
                )
                if (aLanding != null) {
                    landings[aId] = aLanding
                    motions[aId] = bMotion
                    continue
                }
                val bLanding = tryLand(
                    supportId = aId,
                    rocketShape = bShape,
                    rocketControl = bControl,
                    supportShape = aShape,
                    supportTransform = aTransform,
                    rocketTransform = bTransform,
                    landingNormal = -normal,
                    minDist = minDist,
                )
                if (bLanding != null) {
                    landings[bId] = bLanding
                    motions[bId] = aMotion
                    continue
                }

                val massA = aMaterial.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val massB = bMaterial.mass.coerceIn(1u, Int.MAX_VALUE.toUInt()).toInt()
                val totalMass = (massA + massB).coerceAtMost(Int.MAX_VALUE)
                val invMassWeightA = Frac(massB, totalMass)
                val invMassWeightB = Frac(massA, totalMass)

                val pushA = normal * (pen * invMassWeightA)
                val pushB = normal * (pen * invMassWeightB)

                val velDelta = bMotion.vel - aMotion.vel
                val velAlongNorm = velDelta.dot(normal)
                val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
                val normResponse = if (velAlongNorm.sign > 0) velAlongNorm * bounciness else Frac(0)

                val roughness = aMaterial.rough.coerceAtMost(bMaterial.rough)
                val circumferenceA = aCollider.radius.toCircumference()
                val circumferenceB = bCollider.radius.toCircumference()
                val spinAlongTangent = aMotion.angVel * circumferenceA + bMotion.angVel * circumferenceB
                val velAlongTangent = velDelta.dot(tangent) - spinAlongTangent
                val tangentResponse = velAlongTangent * roughness

                // Multiply by 2 so that bounciness of 1 results in full momentum transfer.
                val pushNormVelA = normal * Frac((normResponse * invMassWeightA).raw*2)
                val pushNormVelB = normal * Frac((normResponse * invMassWeightB).raw*2)

                val tangentResponseA = (tangentResponse * invMassWeightA) / 2
                val tangentResponseB = (tangentResponse * invMassWeightB) / 2
                val pushTangentialVelA = tangent * tangentResponseA
                val pushTangentialVelB = tangent * tangentResponseB
                val pushAngVelA = tangentResponseA / circumferenceA
                val pushAngVelB = tangentResponseB / circumferenceB

                transforms[aId] = aTransform.copy(pos = aTransform.pos + pushA)
                transforms[bId] = bTransform.copy(pos = bTransform.pos - pushB)
                motions[aId] = aMotion.copy(
                    vel = aMotion.vel + pushNormVelA + pushTangentialVelA,
                    angVel = aMotion.angVel + pushAngVelA,
                )
                motions[bId] = bMotion.copy(
                    vel = bMotion.vel - pushNormVelB - pushTangentialVelB,
                    angVel = bMotion.angVel + pushAngVelB,
                )
            }
        }
        return state.copy(
            transforms = ComponentTable.fromMap(transforms),
            motions = ComponentTable.fromMap(motions),
            landings = ComponentTable.fromMap(landings),
        )
    }

    private fun canLand(
        rocketShape: RenderShapeComponent,
        rocketControl: ControlIntentComponent,
        supportShape: RenderShapeComponent,
        rocketTransform: TransformComponent,
        landingNormal: Norm,
    ): Boolean {
        if (rocketShape.shape != BodyShape.TRIANGLE) return false
        if (supportShape.shape == BodyShape.TRIANGLE) return false
        if (rocketControl.thrust > 0) return false
        val forward = Norm.fromAngle(rocketTransform.ang)
        val alignment = forward.dot(landingNormal)
        return alignment.raw > LANDING_ALIGNMENT_THRESHOLD.raw
    }

    private fun tryLand(
        supportId: org.emerge.sim.core.EntityId,
        rocketShape: RenderShapeComponent,
        rocketControl: ControlIntentComponent,
        supportShape: RenderShapeComponent,
        supportTransform: TransformComponent,
        rocketTransform: TransformComponent,
        landingNormal: Norm,
        minDist: Frac,
    ): LandingAttachmentComponent? {
        if (!canLand(
                rocketShape = rocketShape,
                rocketControl = rocketControl,
                supportShape = supportShape,
                rocketTransform = rocketTransform,
                landingNormal = landingNormal,
            )
        ) {
            return null
        }
        val snappedRocketAng = landingNormal.asAngle
        return LandingAttachmentComponent(
            parentEntityId = supportId,
            relativePos = (landingNormal * minDist).rotateByAngle(Frac(-supportTransform.ang.raw)),
            relativeAng = snappedRocketAng - supportTransform.ang,
        )
    }
}