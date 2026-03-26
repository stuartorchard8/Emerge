package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.set


object GravitySystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        if (cfg.gravityNumerator.sign <= 0) {
            return
        }

        val motions = LinkedHashMap(state.raw.motions.asMap())
        val ids = state.raw.materials.keys().toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                if (state.raw.landings.contains(aId) || state.raw.landings.contains(bId)) continue

                val aTransform = state.raw.transforms[aId] ?: continue
                val bTransform = state.raw.transforms[bId] ?: continue
                val aMotion = motions[aId] ?: continue
                val bMotion = motions[bId] ?: continue
                val aMaterial = state.raw.materials[aId] ?: continue
                val bMaterial = state.raw.materials[bId] ?: continue
                val aCollider = state.raw.colliders[aId] ?: continue
                val bCollider = state.raw.colliders[bId] ?: continue
                val aShape = state.raw.renderShapes[aId]?.shape ?: continue
                val bShape = state.raw.renderShapes[bId]?.shape ?: continue
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
                motions[aId] = aMotion.copy(
                    vel = aMotion.vel - (normal * accelTowardB),
                )
                motions[bId] = bMotion.copy(
                    vel = bMotion.vel + (normal * accelTowardA),
                )
            }
        }

        state.raw = state.raw.copy(motions = ComponentTable.fromMap(motions))
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
