package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.triangularChunkBounds
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Pairwise inverse-law gravity between asteroid/planet bodies and ship bodies.
 *
 * This is an O(n²) all-pairs sweep — at entity counts in the hundreds it's the
 * single largest slice of [forceGather][org.emerge.sim.core.physics.PhysicsReducer]
 * wall time. Pass an [executor] to partition the outer i-loop across worker threads;
 * each worker accumulates per-entity impulse deltas in a local map and the main
 * thread drains them via [PhysicsBuilder.update] at the end.
 *
 * Bit-identical output to the sequential path:
 *  - [ImpulseComponent.plus] is commutative and associative on its three additive
 *    fields, so summing a pair's contribution in any order yields the same result.
 *  - Bucket merge iterates chunks in registration order and each chunk's map in
 *    insertion order, so the final `update` sequence is deterministic.
 *
 * Write-logging still sees one `update` per (entity, frame) rather than one per
 * (pair, frame), which is a minor but harmless change to replay length.
 */
class GravitySystem(
    private val executor: ParallelExecutor? = null,
) : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        if (cfg.gravityNumerator.sign <= 0) return

        val ids = builder.entries<MaterialComponent>().keys.toList()
        val n = ids.size
        if (n < 2) return

        if (executor != null && n >= PARALLEL_THRESHOLD) {
            updateParallel(cfg, builder, ids, executor)
        } else {
            updateSequential(cfg, builder, ids)
        }
    }

    private fun updateSequential(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        ids: List<EntityId>,
    ) {
        val deltas = LinkedHashMap<EntityId, ImpulseComponent>()
        collectDeltas(cfg, builder, ids, iStart = 0, iEnd = ids.size, out = deltas)
        applyDeltas(builder, deltas)
    }

    private fun updateParallel(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        ids: List<EntityId>,
        executor: ParallelExecutor,
    ) {
        val n = ids.size
        val chunkCount = (executor.parallelism * 4).coerceAtMost(n).coerceAtLeast(1)
        val bounds = triangularChunkBounds(n, chunkCount)
        val buckets = arrayOfNulls<LinkedHashMap<EntityId, ImpulseComponent>>(chunkCount)
        val tasks = ArrayList<() -> Unit>(chunkCount)
        for (c in 0 until chunkCount) {
            val iStart = bounds[c]
            val iEnd = bounds[c + 1]
            if (iStart >= iEnd) continue
            tasks += {
                val local = LinkedHashMap<EntityId, ImpulseComponent>()
                collectDeltas(cfg, builder, ids, iStart, iEnd, local)
                buckets[c] = local
            }
        }
        executor.invokeAll(tasks)
        val merged = LinkedHashMap<EntityId, ImpulseComponent>()
        for (c in 0 until chunkCount) {
            val bucket = buckets[c] ?: continue
            for ((id, delta) in bucket) {
                merged[id] = delta + merged[id]
            }
        }
        applyDeltas(builder, merged)
    }

    private fun applyDeltas(
        builder: PhysicsBuilder,
        deltas: Map<EntityId, ImpulseComponent>,
    ) {
        for ((id, delta) in deltas) {
            builder.update<ImpulseComponent>(id) { delta + it }
        }
    }

    /**
     * Accumulates gravitational impulse contributions from all pairs (i, j) with
     * `iStart <= i < iEnd` and `i < j < ids.size`. Reads go through the builder's
     * frozen view; no writes happen here — callers drain [out] via [applyDeltas].
     */
    private fun collectDeltas(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        ids: List<EntityId>,
        iStart: Int,
        iEnd: Int,
        out: MutableMap<EntityId, ImpulseComponent>,
    ) {
        val n = ids.size
        for (i in iStart until iEnd) {
            val aId = ids[i]
            if (builder.getComponent<LandingAttachmentComponent>(aId) != null) continue
            val aTransform = builder.getComponent<TransformComponent>(aId) ?: continue
            val aMaterial = builder.getComponent<MaterialComponent>(aId) ?: continue
            val aCollider = builder.getComponent<ColliderComponent>(aId) ?: continue
            val aShape = builder.getComponent<RenderShapeComponent>(aId)?.shape ?: continue
            val aIsAsteroid = aShape == BodyShape.CIRCLE

            for (j in i + 1 until n) {
                val bId = ids[j]
                if (builder.getComponent<LandingAttachmentComponent>(bId) != null) continue

                val bTransform = builder.getComponent<TransformComponent>(bId) ?: continue
                val bMaterial = builder.getComponent<MaterialComponent>(bId) ?: continue
                val bCollider = builder.getComponent<ColliderComponent>(bId) ?: continue
                val bShape = builder.getComponent<RenderShapeComponent>(bId)?.shape ?: continue
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
                val aImpulse = ImpulseComponent(vel = -(normal * accelTowardB))
                val bImpulse = ImpulseComponent(vel = (normal * accelTowardA))
                out[aId] = aImpulse + out[aId]
                out[bId] = bImpulse + out[bId]
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
        var n = (dist - Frac(1, 1))
        n *= n
        n *= n
        n *= n

        return n * Frac(sourceMass.toLong()) * gravityNumerator
    }

    companion object {
        /** Below this entity count the dispatch overhead dominates; stay sequential. */
        private const val PARALLEL_THRESHOLD = 64
    }
}
