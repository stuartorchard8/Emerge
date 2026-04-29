package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.contacts
import org.emerge.sim.core.physics.primitives.*


object LandingSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val LANDING_ALIGNMENT_THRESHOLD = Frac(7, 8)

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        for (contact in builder.contacts) {
            val aId = contact.aId
            val bId = contact.bId
            val aShape = builder.getComponent<RenderShapeComponent>(aId) ?: continue
            val bShape = builder.getComponent<RenderShapeComponent>(bId) ?: continue
            val aTransform = builder.getComponent<TransformComponent>(aId) ?: continue
            val bTransform = builder.getComponent<TransformComponent>(bId) ?: continue
            val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
            val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
            val aMotion = builder.getComponent<MotionComponent>(aId) ?: continue
            val bMotion = builder.getComponent<MotionComponent>(bId) ?: continue
            val aLanding = builder.getComponent<LandingAttachmentComponent>(aId)
            val bLanding = builder.getComponent<LandingAttachmentComponent>(bId)
            val normal = contact.normal
            val minDist = contact.minDist
            if (aLanding != null || bLanding != null) {
                crushLandedShipIfPinnedByPlanet(
                    builder = builder,
                    cfg = cfg,
                    entityId = aId,
                    entityShape = aShape,
                    landing = aLanding,
                    otherEntityId = bId,
                )
                crushLandedShipIfPinnedByPlanet(
                    builder = builder,
                    cfg = cfg,
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
                builder.update<LandingAttachmentComponent>(aId) { aLandingAttempt }
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
                builder.update<LandingAttachmentComponent>(bId) { bLandingAttempt }
                continue
            }

            val velDelta = bMotion.vel - aMotion.vel
            val velAlongNorm = velDelta.dot(normal)
            val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
            val normalResponse = solveNormalCollisionResponse(
                massA = aMaterial.mass,
                massB = bMaterial.mass,
                closingSpeedAlongNormal = velAlongNorm,
                restitution = bounciness,
            )

            accumulateShipCollisionDamage(
                builder = builder,
                entityId = aId,
                shape = aShape,
                impactImpulse = normalResponse.deltaVelA,
                cfg = cfg,
            )

            accumulateShipCollisionDamage(
                builder = builder,
                entityId = bId,
                shape = bShape,
                impactImpulse = normalResponse.deltaVelB,
                cfg = cfg,
            )
        }
    }

    private fun accumulateShipCollisionDamage(
        builder: PhysicsBuilder,
        entityId: EntityId,
        shape: RenderShapeComponent,
        impactImpulse: Frac,
        cfg: PhysicsConfig,
    ) {
        if (shape.shape != BodyShape.TRIANGLE) return
        val speedOverThreshold = impactImpulse - cfg.collisionSpeedDamageThreshold
        if (speedOverThreshold.raw <= 0L) return
        builder.update<DamageComponent>(entityId) { DamageComponent(next = speedOverThreshold) + it }
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
        builder: PhysicsBuilder,
        cfg: PhysicsConfig,
        entityId: EntityId,
        entityShape: RenderShapeComponent,
        landing: LandingAttachmentComponent?,
        otherEntityId: EntityId,
    ) {
        if (landing == null) return
        if (landing.parentEntityId == otherEntityId) return
        if (entityShape.shape != BodyShape.TRIANGLE) return
        if (builder.getComponent<PlanetComponent>(otherEntityId) == null) return
        builder.update<DamageComponent>(entityId) { DamageComponent(next = cfg.maxHealth) }
    }
}
