package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Inverse-law gravity between asteroid/planet bodies (shape == CIRCLE) and
 * ship bodies (shape != CIRCLE). Ship↔ship and asteroid↔asteroid pairs do
 * not gravitate; [LandingAttachmentComponent]-bearing entities are also
 * excluded (an attached drocket is effectively rigid with its parent and
 * must not induce or receive impulses).
 *
 * **Why not all-pairs.** The previous implementation iterated O(n²) pairs
 * and filtered out same-shape and attached pairs after fetching five
 * components per side; at n=500 that's ~125k wasted component lookups per
 * tick. Partitioning up front into `sources` (asteroids) and `ships`
 * collapses the outer/inner loops to `sources × ships`, which at drockets'
 * typical density (1 planet, a few hundred ships) is two orders of
 * magnitude less work. All component reads for each entity happen exactly
 * once per tick, cached into the partition arrays.
 *
 * **Determinism.** [ImpulseComponent.plus] is commutative and associative,
 * so accumulating a ship's impulses from its sources (or a source's
 * impulses from its ships) in any order produces identical totals. The
 * [applyDeltas] step iterates the merged delta map in insertion order so
 * write-log sequences are stable tick-to-tick.
 *
 * **Parallelism.** With [executor] present and `ships.size >=
 * [PARALLEL_THRESHOLD]`, the ship list is sliced into contiguous chunks;
 * each worker accumulates ship-local impulses and its slice's contribution
 * to every source impulse into a local map. Main thread merges maps in
 * chunk order.
 */
class GravitySystem(
    private val executor: ParallelExecutor? = null,
) : EcsSystem<PhysicsTuning, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        if (cfg.gravityNumerator.sign <= 0) return

        val ids = builder.entries<MaterialComponent>().keys.toList()
        val n = ids.size
        if (n < 2) return

        val partition = partition(builder, ids) ?: return

        val deltas = if (executor != null && partition.ships.size >= PARALLEL_THRESHOLD) {
            computeParallel(cfg, partition, executor)
        } else {
            computeSequential(cfg, partition)
        }
        applyDeltas(builder, deltas)
    }

    private fun computeSequential(cfg: PhysicsTuning, p: Partition): Map<EntityId, ImpulseComponent> {
        val out = LinkedHashMap<EntityId, ImpulseComponent>()
        collectDeltas(cfg, p, shipStart = 0, shipEnd = p.ships.size, out)
        return out
    }

    private fun computeParallel(
        cfg: PhysicsTuning,
        p: Partition,
        executor: ParallelExecutor,
    ): Map<EntityId, ImpulseComponent> {
        val shipCount = p.ships.size
        val chunkCount = executor.parallelism.coerceAtMost(shipCount).coerceAtLeast(1)
        // Uniform ship slices: each ship does the same work (one loop over
        // sources), so load balancing is trivial — no triangular weighting
        // needed.
        val step = (shipCount + chunkCount - 1) / chunkCount
        val buckets = arrayOfNulls<LinkedHashMap<EntityId, ImpulseComponent>>(chunkCount)
        val tasks = ArrayList<() -> Unit>(chunkCount)
        for (c in 0 until chunkCount) {
            val shipStart = c * step
            val shipEnd = ((c + 1) * step).coerceAtMost(shipCount)
            if (shipStart >= shipEnd) continue
            tasks += {
                val local = LinkedHashMap<EntityId, ImpulseComponent>()
                collectDeltas(cfg, p, shipStart, shipEnd, local)
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
        return merged
    }

    private fun applyDeltas(
        builder: PhysicsBuilder,
        deltas: Map<EntityId, ImpulseComponent>,
    ) {
        for ((id, delta) in deltas) {
            builder.update<ImpulseComponent>(id) { delta + it }
        }
    }

    private fun collectDeltas(
        cfg: PhysicsTuning,
        p: Partition,
        shipStart: Int,
        shipEnd: Int,
        out: MutableMap<EntityId, ImpulseComponent>,
    ) {
        val sourceCount = p.sources.size
        for (s in shipStart until shipEnd) {
            val shipId = p.ships[s]
            val shipTransform = p.shipTransforms[s]
            val shipMaterial = p.shipMaterials[s]
            val shipRadius = p.shipRadii[s]
            for (k in 0 until sourceCount) {
                val sourceId = p.sources[k]
                val sourceTransform = p.sourceTransforms[k]
                val sourceMaterial = p.sourceMaterials[k]
                val sourceRadius = p.sourceRadii[k]

                val delta = shipTransform.pos - sourceTransform.pos
                if (delta.lenSq.raw == 0L) continue
                val minDist = shipRadius + sourceRadius
                val dist = if (delta > minDist) delta.lenSq else minDist

                val accelTowardSource = gravityAcceleration(
                    sourceMass = sourceMaterial.mass,
                    dist = dist,
                    gravityNumerator = cfg.gravityNumerator,
                )
                val accelTowardShip = gravityAcceleration(
                    sourceMass = shipMaterial.mass,
                    dist = dist,
                    gravityNumerator = cfg.gravityNumerator,
                )

                val normal = delta.norm
                val shipImpulse = ImpulseComponent(vel = -(normal * accelTowardSource))
                val sourceImpulse = ImpulseComponent(vel = (normal * accelTowardShip))
                out[shipId] = shipImpulse + out[shipId]
                out[sourceId] = sourceImpulse + out[sourceId]
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

    /**
     * Snapshot of the gravity-relevant entities for one tick. `sources` are
     * asteroids/planets (CIRCLE); `ships` are everything else. Each list is
     * parallel with its component arrays so we never re-hit the component
     * HashMap during the main compute.
     */
    private class Partition(
        val sources: List<EntityId>,
        val sourceTransforms: Array<TransformComponent>,
        val sourceMaterials: Array<MaterialComponent>,
        val sourceRadii: Array<Frac>,
        val ships: List<EntityId>,
        val shipTransforms: Array<TransformComponent>,
        val shipMaterials: Array<MaterialComponent>,
        val shipRadii: Array<Frac>,
    )

    private fun partition(builder: PhysicsBuilder, ids: List<EntityId>): Partition? {
        val sources = ArrayList<EntityId>()
        val sourceTransforms = ArrayList<TransformComponent>()
        val sourceMaterials = ArrayList<MaterialComponent>()
        val sourceRadii = ArrayList<Frac>()
        val ships = ArrayList<EntityId>()
        val shipTransforms = ArrayList<TransformComponent>()
        val shipMaterials = ArrayList<MaterialComponent>()
        val shipRadii = ArrayList<Frac>()

        for (id in ids) {
            if (builder.getComponent<LandingAttachmentComponent>(id) != null) continue
            val transform = builder.getComponent<TransformComponent>(id) ?: continue
            val material = builder.getComponent<MaterialComponent>(id) ?: continue
            val collider = builder.getComponent<ColliderComponent>(id) ?: continue
            val shape = builder.getComponent<RenderShapeComponent>(id)?.shape ?: continue

            if (shape == BodyShape.CIRCLE) {
                sources.add(id)
                sourceTransforms.add(transform)
                sourceMaterials.add(material)
                sourceRadii.add(collider.radius)
            } else {
                ships.add(id)
                shipTransforms.add(transform)
                shipMaterials.add(material)
                shipRadii.add(collider.radius)
            }
        }

        if (sources.isEmpty() || ships.isEmpty()) return null
        return Partition(
            sources = sources,
            sourceTransforms = sourceTransforms.toTypedArray(),
            sourceMaterials = sourceMaterials.toTypedArray(),
            sourceRadii = sourceRadii.toTypedArray(),
            ships = ships,
            shipTransforms = shipTransforms.toTypedArray(),
            shipMaterials = shipMaterials.toTypedArray(),
            shipRadii = shipRadii.toTypedArray(),
        )
    }

    companion object {
        /**
         * Below this ship count the fork-join dispatch overhead dominates the
         * actual pair math (the work per ship is tiny: one iteration per
         * source).
         */
        private const val PARALLEL_THRESHOLD = 64
    }
}
