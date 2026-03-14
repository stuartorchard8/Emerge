package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import kotlin.collections.set


object GravitySystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        if (cfg.gravityNumerator.sign <= 0) {
            return state
        }

        val motions = LinkedHashMap(state.motions.asMap())
        val ids = state.world.entities
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (state.landings.contains(aId) || state.landings.contains(bId)) continue

                val aTransform = state.transforms[aId] ?: continue
                val bTransform = state.transforms[bId] ?: continue
                val aMotion = motions[aId] ?: continue
                val bMotion = motions[bId] ?: continue
                val aMaterial = state.materials[aId] ?: continue
                val bMaterial = state.materials[bId] ?: continue
                val aCollider = state.colliders[aId] ?: continue
                val bCollider = state.colliders[bId] ?: continue
                val aShape = state.renderShapes[aId]?.shape ?: continue
                val bShape = state.renderShapes[bId]?.shape ?: continue
                val aIsAsteroid = aShape == BodyShape.CIRCLE
                val bIsAsteroid = bShape == BodyShape.CIRCLE
                if (aIsAsteroid == bIsAsteroid) continue

                val delta = aTransform.pos - bTransform.pos
                if (delta.lenSq.raw == 0) continue
                val minDist = aCollider.radius + bCollider.radius

                val accelTowardB = gravityAcceleration(
                    sourceMass = bMaterial.mass,
                    minDistSq = minDist*minDist,
                    dist = delta.len,
                    distSq = delta.lenSq,
                    gravityNumerator = cfg.gravityNumerator,
                )
                val accelTowardA = gravityAcceleration(
                    sourceMass = aMaterial.mass,
                    minDistSq = minDist*minDist,
                    dist = delta.len,
                    distSq = delta.lenSq,
                    gravityNumerator = cfg.gravityNumerator,
                )

                val normal = delta.norm
                motions[aId] = aMotion.copy(
                    vel = aMotion.vel - (normal * accelTowardB),
                )
                motions[bId] = bMotion.copy(
                    vel = bMotion.vel + (normal * accelTowardA),
                )
            }
        }

        return state.copy(motions = ComponentTable.fromMap(motions))
    }

    private fun gravityAcceleration(
        sourceMass: UInt,
        minDistSq: Frac,
        dist: Frac,
        distSq: Frac,
        gravityNumerator: Frac,
    ): Frac {
        if (distSq.raw <= 0 || gravityNumerator.sign <= 0) {
            return Frac(0)
        }
        var n = (dist-Frac(1,1))
        n *= n
        n *= n
        n *= n
        n *= n
        n *= n
        n *= n

        return n * Frac(sourceMass.toInt()) * gravityNumerator
    }
}
