package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.triangularChunkBounds
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.setContacts
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Producer of the `contactDetect` phase. Scans all pairs of material-bearing entities,
 * computes contacts, and publishes the full list as a typed phase output via
 * [setContacts]. Downstream phases ([BounceSystem], [CrashSystem], [LandingSystem],
 * [DrocketLandingSystem][org.emerge.demo.drockets.DrocketLandingSystem]) read it as
 * an immutable `List<Contact>` and never mutate it.
 *
 * Parallelism: when given an [executor] and the material-entity count crosses
 * [PARALLEL_THRESHOLD], the outer i-loop is partitioned into contiguous chunks and
 * dispatched across worker threads. Each worker writes to its own bucket; buckets
 * are concatenated in chunk order at the end, which yields the same element order
 * the sequential path produces (bit-identical output). Reads go through the frozen
 * parent builder: `workingData`/`tombstones`/`initial` are not mutated during the
 * `contactDetect` phase, so concurrent reads are race-free.
 */
class ContactSystem(
    private val executor: ParallelExecutor? = null,
) : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val ids = builder.entries<MaterialComponent>().keys.toList()
        val contacts = if (executor != null && ids.size >= PARALLEL_THRESHOLD) {
            computeParallel(builder, ids, executor)
        } else {
            computeSequential(builder, ids)
        }
        builder.setContacts(contacts)
    }

    private fun computeSequential(
        builder: PhysicsBuilder,
        ids: List<EntityId>,
    ): List<Contact> {
        val contacts = mutableListOf<Contact>()
        collectContacts(builder, ids, iStart = 0, iEnd = ids.size, out = contacts)
        return contacts
    }

    private fun computeParallel(
        builder: PhysicsBuilder,
        ids: List<EntityId>,
        executor: ParallelExecutor,
    ): List<Contact> {
        val n = ids.size
        val chunkCount = (executor.parallelism * 4).coerceAtMost(n).coerceAtLeast(1)
        val bounds = triangularChunkBounds(n, chunkCount)
        val buckets = arrayOfNulls<MutableList<Contact>>(chunkCount)
        val tasks = ArrayList<() -> Unit>(chunkCount)
        for (c in 0 until chunkCount) {
            val iStart = bounds[c]
            val iEnd = bounds[c + 1]
            if (iStart >= iEnd) continue
            tasks += {
                val local = mutableListOf<Contact>()
                collectContacts(builder, ids, iStart, iEnd, local)
                buckets[c] = local
            }
        }
        executor.invokeAll(tasks)
        var totalSize = 0
        for (bucket in buckets) if (bucket != null) totalSize += bucket.size
        val merged = ArrayList<Contact>(totalSize)
        for (c in 0 until chunkCount) {
            val bucket = buckets[c] ?: continue
            merged.addAll(bucket)
        }
        return merged
    }

    private fun collectContacts(
        builder: PhysicsBuilder,
        ids: List<EntityId>,
        iStart: Int,
        iEnd: Int,
        out: MutableList<Contact>,
    ) {
        val n = ids.size
        for (i in iStart until iEnd) {
            val aId = ids[i]
            val aTransform = builder.getComponent<TransformComponent>(aId) ?: continue
            val aCollider = builder.getComponent<ColliderComponent>(aId) ?: continue
            for (j in i + 1 until n) {
                val bId = ids[j]
                val bTransform = builder.getComponent<TransformComponent>(bId) ?: continue
                val bCollider = builder.getComponent<ColliderComponent>(bId) ?: continue
                val contact = Contact.compute(
                    aId = aId,
                    bId = bId,
                    aTransform = aTransform,
                    bTransform = bTransform,
                    aRadius = aCollider.radius,
                    bRadius = bCollider.radius,
                )
                if (contact != null) out.add(contact)
            }
        }
    }

    companion object {
        /**
         * Below this entity count the fork-join dispatch overhead outweighs the
         * benefit; fall back to the sequential path. Tuned loosely — the tight
         * inner loop is ~1 µs per pair, so at n=64 total work is ~2 ms which is
         * already comfortably under budget single-threaded.
         */
        private const val PARALLEL_THRESHOLD = 64
    }
}
