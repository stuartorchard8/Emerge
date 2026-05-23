package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.contacts
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput


object CrashSystem : EcsSystem<PhysicsTuning, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        for (contact in builder.contacts) {
            val aId = contact.aId
            val bId = contact.bId
            val aShape = builder.getComponent<RenderShapeComponent>(aId) ?: continue
            val bShape = builder.getComponent<RenderShapeComponent>(bId) ?: continue
            val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
            val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
            val aMotion = builder.getComponent<MotionComponent>(aId) ?: continue
            val bMotion = builder.getComponent<MotionComponent>(bId) ?: continue
            val normal = contact.normal

            val velDelta = bMotion.vel - aMotion.vel
            val velAlongNorm = velDelta.dot(normal)
            val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
            val normalResponse = solveNormalCollisionResponse(
                massA = aMaterial.mass,
                massB = bMaterial.mass,
                closingSpeedAlongNormal = velAlongNorm,
                restitution = bounciness,
            )

            applyCollisionDamage(
                builder = builder,
                entityId = aId,
                shape = aShape,
                impactImpulse = normalResponse.deltaVelA,
                cfg = cfg,
            )

            applyCollisionDamage(
                builder = builder,
                entityId = bId,
                shape = bShape,
                impactImpulse = normalResponse.deltaVelB,
                cfg = cfg,
            )
        }
    }

    private fun applyCollisionDamage(
        builder: PhysicsBuilder,
        entityId: EntityId,
        shape: RenderShapeComponent,
        impactImpulse: Frac,
        cfg: PhysicsTuning,
    ) {
        if (shape.shape != BodyShape.TRIANGLE) return
        val speedOverThreshold = impactImpulse - cfg.collisionSpeedDamageThreshold
        if (speedOverThreshold.raw <= 0L) return
        builder.update<DamageComponent>(entityId) { DamageComponent(next = speedOverThreshold) + it }
    }
}
