package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput


object CrashSystem : EcsSystem<PhysicsConfig, PhysicsInput> {
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
            val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
            val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
            val aMotion = builder.getComponent<MotionComponent>(aId) ?: continue
            val bMotion = builder.getComponent<MotionComponent>(bId) ?: continue
            val normal = contact.normal

            val massA = aMaterial.mass.toLong()
            val massB = bMaterial.mass.toLong()
            val totalMass = (massA + massB).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val invMassWeightA = Frac(massB, totalMass)
            val invMassWeightB = Frac(massA, totalMass)

            val velDelta = bMotion.vel - aMotion.vel
            val velAlongNorm = velDelta.dot(normal)
            val bounciness = aMaterial.bounce.coerceAtMost(bMaterial.bounce)
            val normResponse = if (velAlongNorm.sign > 0) velAlongNorm * bounciness else Frac(0)

            // Multiply by 2 so that bounciness of 1 results in full momentum transfer.
            val pushVelA = Frac((normResponse * invMassWeightA).raw*2)
            val pushVelB = Frac((normResponse * invMassWeightB).raw*2)

            accumulateShipCollisionDamage(
                builder = builder,
                entityId = aId,
                shape = aShape,
                impactImpulse = pushVelA,
                cfg = cfg,
            )

            accumulateShipCollisionDamage(
                builder = builder,
                entityId = bId,
                shape = bShape,
                impactImpulse = pushVelB,
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
        val speedOverThreshold = impactImpulse - cfg.shipCollisionDamageThreshold
        if (speedOverThreshold.raw <= 0L) return
        builder.update<DamageComponent>(entityId) { DamageComponent(next = speedOverThreshold) + it }
    }
}
