package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput


object GravitySystem : EcsSystem<PhysicsConfig, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        if (cfg.gravityNumerator.sign <= 0) {
            return
        }

        val ids = builder.entries<MaterialComponent>().keys.toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (builder.getComponent<LandingAttachmentComponent>(aId) != null
                    || builder.getComponent<LandingAttachmentComponent>(bId) != null ) continue

                val aTransform = builder.getComponent<TransformComponent>(aId) ?: continue
                val bTransform = builder.getComponent<TransformComponent>(bId) ?: continue
                val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
                val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
                val aCollider = builder.getComponent<ColliderComponent>(aId) ?: continue
                val bCollider = builder.getComponent<ColliderComponent>(bId) ?: continue
                val aShape = builder.getComponent<RenderShapeComponent>(aId)?.shape ?: continue
                val bShape = builder.getComponent<RenderShapeComponent>(bId)?.shape ?: continue
                val aIsAsteroid = aShape == BodyShape.CIRCLE
                val bIsAsteroid = bShape == BodyShape.CIRCLE
                if (aIsAsteroid == bIsAsteroid) continue

                val delta = aTransform.pos - bTransform.pos
                if (delta.lenSq.raw == 0L) continue
                val minDist = aCollider.radius + bCollider.radius
                val dist = if (delta > minDist) delta.lenSq else minDist

                val accelTowardB = gravityAcceleration(
                    sourceMass = bMaterial.mass,
                    dist = dist,
                    gravityNumerator = cfg.gravityNumerator,
                )
                val accelTowardA = gravityAcceleration(
                    sourceMass = aMaterial.mass,
                    dist = dist,
                    gravityNumerator = cfg.gravityNumerator,
                )

                val normal = delta.norm
                val aImpulse = ImpulseComponent(vel=-(normal * accelTowardB))
                val bImpulse = ImpulseComponent(vel=(normal * accelTowardA))
                builder.update<ImpulseComponent>(aId) { aImpulse + it }
                builder.update<ImpulseComponent>(bId) { bImpulse + it }
            }
        }
    }

    private fun gravityAcceleration(
        sourceMass: UInt,
        dist: Frac,
        gravityNumerator: Frac,
    ): Frac {
        if (dist.raw <= 0 || gravityNumerator.sign <= 0 || dist.raw >= Int.MAX_VALUE) {
            return Frac(0)
        }
        var n = (dist-Frac(1,1))
        n *= n
        n *= n
        n *= n

        return n * Frac(sourceMass.toLong()) * gravityNumerator
    }
}
