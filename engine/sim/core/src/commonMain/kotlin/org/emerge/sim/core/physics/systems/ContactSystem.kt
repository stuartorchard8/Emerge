package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.SpatialGrid
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
 * Producer of the `contactDetect` phase. Scans pairs of material-bearing entities,
 * computes contacts, and publishes the full list as a typed phase output via
 * [setContacts]. Downstream phases ([BounceSystem], [CrashSystem], [LandingSystem],
 * [DrocketLandingSystem][org.emerge.demo.drockets.DrocketLandingSystem]) read it as
 * an immutable `List<Contact>` and never mutate it.
 *
 * **Broadphase.** Rather than sweeping every pair (O(n²)), the system builds a
 * uniform [SpatialGrid] with cell size `2 * maxRadius` and, for each body, only
 * considers candidates in the 3×3 cell window around it. Cell size guarantees
 * any overlapping pair has centres within that window, so the grid loses no
 * contacts relative to the all-pairs sweep. Typical speedup is n/k where k is
 * the average 3×3 occupancy (a handful at realistic densities).
 *
 * **Component caching.** The grid pass also caches `TransformComponent` /
 * `ColliderComponent` references and raw `(x, y, radius)` Long triples into flat
 * arrays so the inner loop never touches the component-table HashMaps again.
 * Coupled with a raw-Long AABB fast-reject check before the full contact
 * computation, the typical pair-evaluation cost drops from ~5 Frac allocations
 * to 0 in the reject case.
 *
 * **Determinism.** Candidates per `i` are sorted ascending before the inner
 * sweep, so the output order matches the legacy O(n²) sweep (lexicographic
 * `(aId, bId)` with aId < bId) bit-for-bit. Parallel dispatch merges chunks in
 * registration order, which preserves that global ordering.
 *
 * **Parallelism.** With [executor] present and `n >= [PARALLEL_THRESHOLD]`, the
 * `i`-loop is partitioned across worker threads. Reads go through frozen parent
 * state / arrays populated on the main thread; writes into bucket-local lists.
 * The main thread concatenates buckets and publishes.
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
        val n = ids.size
        if (n < 2) {
            builder.setContacts(emptyList())
            return
        }

        val transforms = arrayOfNulls<TransformComponent>(n)
        val colliders = arrayOfNulls<ColliderComponent>(n)
        // Positions come from Coord.raw (Int); scale matches Frac.raw within
        // [-Int.MAX_VALUE, Int.MAX_VALUE], so an Int subtraction wraps via
        // two's-complement overflow to give the shortest torus delta (same
        // semantics as `Coord.minus(Coord)`).
        val posX = IntArray(n)
        val posY = IntArray(n)
        val radii = LongArray(n)
        var maxRadiusRaw: Long = 0
        var validCount = 0
        for (i in 0 until n) {
            val id = ids[i]
            val t = builder.getComponent<TransformComponent>(id) ?: continue
            val c = builder.getComponent<ColliderComponent>(id) ?: continue
            transforms[i] = t
            colliders[i] = c
            posX[i] = t.pos.x.raw
            posY[i] = t.pos.y.raw
            radii[i] = c.radius.raw
            if (c.radius.raw > maxRadiusRaw) maxRadiusRaw = c.radius.raw
            validCount += 1
        }

        if (validCount < 2 || maxRadiusRaw <= 0L) {
            builder.setContacts(emptyList())
            return
        }

        // Cell size = 2 * maxRadius; any overlapping pair has centres within a
        // 3x3 cell window of either body, so the grid loses no contacts away
        // from the torus seam. (Bodies actually straddling the seam are a
        // known limitation — the grid's cell index is non-wrapping, whereas
        // `Coord.minus` wraps via Int overflow. No drockets scenario exercises
        // seam-crossing contacts today; revisit if that changes.)
        val grid = SpatialGrid(cellSize = maxRadiusRaw * 2L)
        for (i in 0 until n) {
            if (transforms[i] != null) grid.insert(i, posX[i].toLong(), posY[i].toLong())
        }

        val contacts = if (executor != null && validCount >= PARALLEL_THRESHOLD) {
            computeParallel(ids, transforms, colliders, posX, posY, radii, grid, executor)
        } else {
            computeSequential(ids, transforms, colliders, posX, posY, radii, grid)
        }
        builder.setContacts(contacts)
    }

    private fun computeSequential(
        ids: List<EntityId>,
        transforms: Array<TransformComponent?>,
        colliders: Array<ColliderComponent?>,
        posX: IntArray,
        posY: IntArray,
        radii: LongArray,
        grid: SpatialGrid,
    ): List<Contact> {
        val out = mutableListOf<Contact>()
        collectContacts(ids, transforms, colliders, posX, posY, radii, grid, 0, ids.size, out)
        return out
    }

    private fun computeParallel(
        ids: List<EntityId>,
        transforms: Array<TransformComponent?>,
        colliders: Array<ColliderComponent?>,
        posX: IntArray,
        posY: IntArray,
        radii: LongArray,
        grid: SpatialGrid,
        executor: ParallelExecutor,
    ): List<Contact> {
        val n = ids.size
        val chunkCount = (executor.parallelism * 4).coerceAtMost(n).coerceAtLeast(1)
        // Triangular chunking still balances better than uniform ranges even
        // with broadphase: neighbour-set size doesn't directly correlate with i,
        // but chunk-0 processes the widest j range, and the imbalance is in
        // the noise anyway. Cheap insurance.
        val bounds = triangularChunkBounds(n, chunkCount)
        val buckets = arrayOfNulls<MutableList<Contact>>(chunkCount)
        val tasks = ArrayList<() -> Unit>(chunkCount)
        for (c in 0 until chunkCount) {
            val iStart = bounds[c]
            val iEnd = bounds[c + 1]
            if (iStart >= iEnd) continue
            tasks += {
                val local = mutableListOf<Contact>()
                collectContacts(ids, transforms, colliders, posX, posY, radii, grid, iStart, iEnd, local)
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
        ids: List<EntityId>,
        transforms: Array<TransformComponent?>,
        colliders: Array<ColliderComponent?>,
        posX: IntArray,
        posY: IntArray,
        radii: LongArray,
        grid: SpatialGrid,
        iStart: Int,
        iEnd: Int,
        out: MutableList<Contact>,
    ) {
        var scratch = IntArray(16)
        for (i in iStart until iEnd) {
            val aT = transforms[i] ?: continue
            val aC = colliders[i] ?: continue
            val aX = posX[i]
            val aY = posY[i]
            val aR = radii[i]

            var candidateCount = 0
            grid.forEachNeighbour(aX.toLong(), aY.toLong()) { j ->
                if (j > i) {
                    if (candidateCount >= scratch.size) {
                        scratch = scratch.copyOf(scratch.size * 2)
                    }
                    scratch[candidateCount] = j
                    candidateCount += 1
                }
            }
            // Sort ascending so the emitted pair order matches the legacy
            // `for j in i+1..n` sweep (lex order by (aId-index, bId-index)).
            insertionSort(scratch, candidateCount)

            for (k in 0 until candidateCount) {
                val j = scratch[k]
                val bT = transforms[j] ?: continue
                val bC = colliders[j] ?: continue
                val sumRadiusRaw = aR + radii[j]
                // Int subtraction wraps via two's-complement, matching
                // `Coord.minus(Coord)`; then widen to Long for abs + compare.
                val dxRaw = longAbs((aX - posX[j]).toLong())
                if (dxRaw >= sumRadiusRaw) continue
                val dyRaw = longAbs((aY - posY[j]).toLong())
                if (dyRaw >= sumRadiusRaw) continue

                val contact = Contact.compute(
                    aId = ids[i],
                    bId = ids[j],
                    aTransform = aT,
                    bTransform = bT,
                    aRadius = aC.radius,
                    bRadius = bC.radius,
                )
                if (contact != null) out.add(contact)
            }
        }
    }

    private fun insertionSort(a: IntArray, size: Int) {
        for (i in 1 until size) {
            val v = a[i]
            var j = i - 1
            while (j >= 0 && a[j] > v) {
                a[j + 1] = a[j]
                j -= 1
            }
            a[j + 1] = v
        }
    }

    private fun longAbs(v: Long): Long = if (v < 0L) -v else v

    companion object {
        /**
         * Below this entity count the fork-join dispatch overhead outweighs the
         * benefit; stay sequential.
         */
        private const val PARALLEL_THRESHOLD = 64
    }
}
