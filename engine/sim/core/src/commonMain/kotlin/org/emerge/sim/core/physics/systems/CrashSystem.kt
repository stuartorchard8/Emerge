package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.components.RenderShapeComponent
import kotlin.collections.set


object CrashSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val landings = LinkedHashMap(state.raw.landings.asMap())
        val damages = LinkedHashMap<EntityId, Frac>()
        for (contact in state.raw.contacts) {
            val aId = contact.aId
            val bId = contact.bId
            val aShape = state.raw.renderShapes[aId] ?: continue
            val bShape = state.raw.renderShapes[bId] ?: continue
            val aMaterial = state.raw.materials[aId] ?: continue
            val bMaterial = state.raw.materials[bId] ?: continue
            val aMotion = state.raw.motions[aId] ?: continue
            val bMotion = state.raw.motions[bId] ?: continue
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
}
