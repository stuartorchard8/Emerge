package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.primitives.Norm
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import kotlin.collections.set


object CollisionSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val LANDING_ALIGNMENT_THRESHOLD = Frac(7, 8)

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val landings = LinkedHashMap(state.raw.landings.asMap())
        val damages = LinkedHashMap<EntityId, Frac>()
        val ids = state.raw.materials.keys().toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                val aTransform = state.raw.transforms[aId] ?: continue
                val bTransform = state.raw.transforms[bId] ?: continue
                val aMotion = state.raw.motions[aId] ?: continue
                val bMotion = state.raw.motions[bId] ?: continue
                val aCollider = state.raw.colliders[aId] ?: continue
                val bCollider = state.raw.colliders[bId] ?: continue
                val aMaterial = state.raw.materials[aId] ?: continue
                val bMaterial = state.raw.materials[bId] ?: continue
                val aShape = state.raw.renderShapes[aId] ?: continue
                val bShape = state.raw.renderShapes[bId] ?: continue

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
                val aLanding = landings[aId]
                val bLanding = landings[bId]
                if (aLanding != null || bLanding != null) {
                    crushLandedShipIfPinnedByPlanet(
                        state = state,
                        cfg = cfg,
                        damages = damages,
                        entityId = aId,
                        entityShape = aShape,
                        landing = aLanding,
                        otherEntityId = bId,
                    )
                    crushLandedShipIfPinnedByPlanet(
                        state = state,
                        cfg = cfg,
                        damages = damages,
                        entityId = bId,
                        entityShape = bShape,
                        landing = bLanding,
                        otherEntityId = aId,
                    )
                    continue
                }

                // Each collision pair can land in either direction: a-on-b or b-on-a.
                val aLandingAttempt = tryLand(
                    supportId = bId,
                    rocketShape = aShape,
                    supportShape = bShape,
                    supportTransform = bTransform,
                    rocketTransform = aTransform,
                    landingNormal = normal,
                    minDist = minDist,
                )
                if (aLandingAttempt != null) {
                    landings[aId] = aLandingAttempt
                    continue
                }
                val bLandingAttempt = tryLand(
                    supportId = aId,
                    rocketShape = bShape,
                    supportShape = aShape,
                    supportTransform = aTransform,
                    rocketTransform = bTransform,
                    landingNormal = -normal,
                    minDist = minDist,
                )
                if (bLandingAttempt != null) {
                    landings[bId] = bLandingAttempt
                    continue
                }

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

                accumulateShipCollisionDamage(
                    damages = damages,
                    entityId = aId,
                    shape = aShape,
                    impactImpulse = pushVelA,
                    cfg = cfg,
                )

                accumulateShipCollisionDamage(
                    damages = damages,
                    entityId = bId,
                    shape = bShape,
                    impactImpulse = pushVelB,
                    cfg = cfg,
                )
            }
        }

        state.addImpulses(impulses)
        state.setLandings(ComponentTable.fromMap(landings))
        state.addDamages(damages)
    }

    private fun accumulateShipCollisionDamage(
        damages: MutableMap<EntityId, Frac>,
        entityId: EntityId,
        shape: RenderShapeComponent,
        impactImpulse: Frac,
        cfg: PhysicsConfig,
    ) {
        if (shape.shape != BodyShape.TRIANGLE) return
        val speedOverThreshold = impactImpulse - cfg.shipCollisionDamageThreshold
        if (speedOverThreshold.raw <= 0L) return
        val priorDamage = damages[entityId] ?: Frac(0)
        damages[entityId] = priorDamage + speedOverThreshold
    }

    private fun canLand(
        rocketShape: RenderShapeComponent,
        supportShape: RenderShapeComponent,
        rocketTransform: TransformComponent,
        landingNormal: Norm,
    ): Boolean {
        if (rocketShape.shape != BodyShape.TRIANGLE) return false
        // Allow landing rockets on one another for now lol
        // if (supportShape.shape == BodyShape.TRIANGLE) return false
        val forward = Norm.fromAngle(rocketTransform.ang)
        val alignment = forward.dot(landingNormal)
        return alignment.raw > LANDING_ALIGNMENT_THRESHOLD.raw
    }

    private fun tryLand(
        supportId: EntityId,
        rocketShape: RenderShapeComponent,
        supportShape: RenderShapeComponent,
        supportTransform: TransformComponent,
        rocketTransform: TransformComponent,
        landingNormal: Norm,
        minDist: Frac,
    ): LandingAttachmentComponent? {
        if (!canLand(
                rocketShape = rocketShape,
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
            relativePos = (landingNormal * minDist).rotateByAngle(Coord(-supportTransform.ang.raw)),
            relativeAng = snappedRocketAng - supportTransform.ang,
        )
    }

    private fun crushLandedShipIfPinnedByPlanet(
        state: PhysicsState,
        cfg: PhysicsConfig,
        damages: MutableMap<EntityId, Frac>,
        entityId: EntityId,
        entityShape: RenderShapeComponent,
        landing: LandingAttachmentComponent?,
        otherEntityId: EntityId,
    ) {
        if (landing == null) return
        if (landing.parentEntityId == otherEntityId) return
        if (entityShape.shape != BodyShape.TRIANGLE) return
        if (state.raw.planets[otherEntityId] == null) return
        damages[entityId] = cfg.shipMaxDamage
    }
}
