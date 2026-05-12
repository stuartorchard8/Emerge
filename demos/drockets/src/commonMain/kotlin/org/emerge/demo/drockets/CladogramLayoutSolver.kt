package org.emerge.demo.drockets

import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Result of one solver invocation. [positions] is in *logical* (pre-projection) space:
 * x is in node-spacing units (offsets relative to the centroid), y is `-depth *
 * GENERATION_Y_SPACING` so depth 0 is at y=0 and depth N is below. The panel renderer
 * multiplies by panel zoom and translates by panel pan to get NDC.
 */
data class CladogramLayoutSolution(
    val positions: Map<Long, Pair<Float, Float>>,
    val filterMs: Float,
    val solveMs: Float,
)

/**
 * Pure-data layout solver for a cladogram (lineage DAG). Has no GPU or rendering
 * dependency and is safe to call from any context.
 *
 * The algorithm:
 *  1. Filter the lineage to currently visible nodes per [CladogramFilterMode].
 *  2. Group visible nodes by *compacted* depth (so dead-only generation gaps collapse out
 *     in living-only filter views).
 *  3. Identify disconnected components by BFS over the undirected edge set.
 *  4. For each component independently: seed initial x by sibling index, then iterate
 *     down-passes (children placed at the centroid of their visible parents) and
 *     up-passes (parents pulled toward the centroid of their visible children),
 *     re-applying a left-to-right / right-to-left non-overlap sweep at each layer.
 *  5. Pack components side-by-side with a one-node gap between them, then re-centre
 *     the whole graph around its mean x.
 *
 * When [seedLogicalPositions] is supplied and node churn is below 10%, only one
 * down-pass and one up-pass are run — this keeps frame-to-frame layouts stable for
 * incremental cladogram growth.
 */
object CladogramLayoutSolver {
    /** Horizontal spacing between sibling nodes at the same depth, in logical units. */
    const val NODE_X_SPACING: Float = 0.060f
    /** Vertical spacing between generation rows, in logical units. */
    const val GENERATION_Y_SPACING: Float = 0.100f

    fun solve(
        layout: CladogramLayout,
        lineage: DrocketLineageState,
        filterMode: CladogramFilterMode,
        seedLogicalPositions: Map<Long, Pair<Float, Float>>? = null,
    ): CladogramLayoutSolution {
        if (layout.depthById.isEmpty()) return CladogramLayoutSolution(emptyMap(), 0f, 0f)

        val filterStart = TimeSource.Monotonic.markNow()
        val visibleIds = computeVisibleLineageIds(lineage, layout, filterMode)
        val filterMs = filterStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        if (visibleIds.isEmpty()) return CladogramLayoutSolution(emptyMap(), filterMs, 0f)

        val solveStart = TimeSource.Monotonic.markNow()
        val visibleByDepth = LinkedHashMap<Int, MutableList<Long>>()
        for (id in visibleIds) {
            val depth = layout.depthById[id] ?: continue
            visibleByDepth.getOrPut(depth) { mutableListOf() }.add(id)
        }
        if (visibleByDepth.isEmpty()) return CladogramLayoutSolution(emptyMap(), filterMs, 0f)

        // Reindex visible generations so dead-only gaps collapse out in living-only view.
        val visibleDepths = visibleByDepth.keys.sorted()
        val compactDepthByOriginal = HashMap<Int, Int>(visibleDepths.size)
        for ((idx, depth) in visibleDepths.withIndex()) {
            compactDepthByOriginal[depth] = idx
        }

        val compactDepthToIds = LinkedHashMap<Int, MutableList<Long>>(visibleDepths.size)
        for ((originalDepth, ids) in visibleByDepth) {
            val compactDepth = compactDepthByOriginal[originalDepth] ?: continue
            compactDepthToIds.getOrPut(compactDepth) { mutableListOf() }.addAll(ids)
        }
        for (ids in compactDepthToIds.values) {
            ids.sortWith(compareBy<Long> { lineage.nodes[it]?.birthTick ?: Long.MAX_VALUE }.thenBy { it })
        }

        val visibleIdsSet = compactDepthToIds.values.flatten().toHashSet()
        val parentById = HashMap<Long, List<Long>>(visibleIds.size)
        val childrenById = HashMap<Long, MutableList<Long>>(visibleIds.size)
        for (id in visibleIdsSet) {
            val node = lineage.nodes[id] ?: continue
            val parents = buildList<Long>(2) {
                val m = node.motherLineageId
                val f = node.fatherLineageId
                if (m != null && visibleIdsSet.contains(m)) add(m)
                if (f != null && visibleIdsSet.contains(f)) add(f)
            }
            parentById[id] = parents
            for (p in parents) {
                childrenById.getOrPut(p) { mutableListOf() }.add(id)
            }
        }

        // Stage 1: identify disconnected trees/components.
        val undirected = HashMap<Long, MutableList<Long>>(visibleIdsSet.size)
        for (id in visibleIdsSet) {
            if (!undirected.containsKey(id)) undirected[id] = mutableListOf()
        }
        for ((from, to) in layout.edges) {
            if (!visibleIdsSet.contains(from) || !visibleIdsSet.contains(to)) continue
            undirected[from]?.add(to)
            undirected[to]?.add(from)
        }
        val components = ArrayList<List<Long>>()
        val seen = HashSet<Long>()
        for (id in visibleIdsSet) {
            if (!seen.add(id)) continue
            val queue = ArrayDeque<Long>()
            val comp = ArrayList<Long>()
            queue.addLast(id)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                comp += cur
                for (n in undirected[cur].orEmpty()) {
                    if (seen.add(n)) queue.addLast(n)
                }
            }
            components += comp
        }
        val orderedComponents = components.sortedBy { comp ->
            comp.minOfOrNull { layout.depthById[it] ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
        }

        val xById = HashMap<Long, Float>(visibleIdsSet.size)
        for (ids in compactDepthToIds.values) {
            for ((idx, id) in ids.withIndex()) {
                val seededX = seedLogicalPositions?.get(id)?.first
                xById[id] = seededX ?: (idx.toFloat() * NODE_X_SPACING)
            }
        }

        // Stage 2: organise each component independently with up/down sweeps.
        for (comp in orderedComponents) {
            solveComponent(
                comp = comp,
                compactDepthToIds = compactDepthToIds,
                parentById = parentById,
                childrenById = childrenById,
                xById = xById,
                seedLogicalPositions = seedLogicalPositions,
            )
        }

        // Stage 3: place components side-by-side with a 1-node gap, then centre overall.
        var cursorX = 0f
        for (comp in orderedComponents) {
            val minX = comp.minOfOrNull { xById[it] ?: 0f } ?: 0f
            val maxX = comp.maxOfOrNull { xById[it] ?: 0f } ?: 0f
            val shift = cursorX - minX
            for (id in comp) {
                xById[id] = (xById[id] ?: 0f) + shift
            }
            cursorX = (maxX + shift) + 2f * NODE_X_SPACING
        }

        val xMean = if (xById.isNotEmpty()) xById.values.sum() / xById.size.toFloat() else 0f
        val out = LinkedHashMap<Long, Pair<Float, Float>>(visibleByDepth.values.sumOf { it.size })
        for ((depth, ids) in compactDepthToIds.entries.sortedBy { it.key }) {
            if (ids.isEmpty()) continue
            for (id in ids) {
                val logicalX = (xById[id] ?: 0f) - xMean
                val logicalY = -depth * GENERATION_Y_SPACING
                out[id] = Pair(logicalX, logicalY)
            }
        }
        val solveMs = solveStart.elapsedNow().inWholeNanoseconds.toFloat() / 1_000_000f
        return CladogramLayoutSolution(out, filterMs, solveMs)
    }

    /**
     * Iterates a single connected component to convergence (or to the incremental budget
     * if we have a recent seed). Mutates [xById] in place for all ids in [comp].
     */
    private fun solveComponent(
        comp: List<Long>,
        compactDepthToIds: Map<Int, MutableList<Long>>,
        parentById: Map<Long, List<Long>>,
        childrenById: Map<Long, MutableList<Long>>,
        xById: HashMap<Long, Float>,
        seedLogicalPositions: Map<Long, Pair<Float, Float>>?,
    ) {
        val allowed = comp.toHashSet()
        val depthToIds = LinkedHashMap<Int, MutableList<Long>>()
        for ((depth, ids) in compactDepthToIds) {
            val subset = ids.filterTo(mutableListOf()) { allowed.contains(it) }
            if (subset.isNotEmpty()) depthToIds[depth] = subset
        }
        val maxDepth = depthToIds.keys.maxOrNull() ?: 0
        val tieEps = 1e-6f

        fun computeDescendantBias(): Map<Long, Float> {
            val bias = HashMap<Long, Float>(allowed.size)
            for (depth in maxDepth downTo 0) {
                for (id in depthToIds[depth].orEmpty()) {
                    var sum = 0f
                    var count = 0
                    for (child in childrenById[id].orEmpty()) {
                        if (!allowed.contains(child)) continue
                        val cx = bias[child] ?: xById[child] ?: continue
                        sum += cx
                        count++
                    }
                    bias[id] = if (count > 0) sum / count.toFloat() else (xById[id] ?: 0f)
                }
            }
            return bias
        }

        fun applyTieBiasOrdering(
            ids: MutableList<Long>,
            primary: Map<Long, Float>,
            bias: Map<Long, Float>,
        ) {
            ids.sortWith(compareBy<Long> { primary[it] ?: Float.POSITIVE_INFINITY }.thenBy { it })
            var start = 0
            while (start < ids.size) {
                val base = primary[ids[start]] ?: Float.POSITIVE_INFINITY
                var end = start + 1
                while (end < ids.size) {
                    val v = primary[ids[end]] ?: Float.POSITIVE_INFINITY
                    if (abs(v - base) > tieEps) break
                    end++
                }
                if (end - start > 1) {
                    ids.subList(start, end).sortWith(
                        compareBy<Long> { bias[it] ?: Float.POSITIVE_INFINITY }.thenBy { it }
                    )
                }
                start = end
            }
        }

        fun applyNonOverlapInLayer(ids: List<Long>, targetX: Map<Long, Float>) {
            if (ids.isEmpty()) return
            val leftToRight = FloatArray(ids.size)
            for (i in ids.indices) {
                val id = ids[i]
                val t = targetX[id] ?: xById[id] ?: 0f
                leftToRight[i] = if (i == 0) t else maxOf(t, leftToRight[i - 1] + NODE_X_SPACING)
            }
            val rightToLeft = FloatArray(ids.size)
            for (i in ids.lastIndex downTo 0) {
                val id = ids[i]
                val t = targetX[id] ?: xById[id] ?: 0f
                rightToLeft[i] = if (i == ids.lastIndex) t else minOf(t, rightToLeft[i + 1] - NODE_X_SPACING)
            }
            for (i in ids.indices) {
                val id = ids[i]
                xById[id] = (leftToRight[i] + rightToLeft[i]) * 0.5f
            }
        }

        fun doDownPass() {
            val descendantBias = computeDescendantBias()
            for (depth in 1..maxDepth) {
                val ids = depthToIds[depth] ?: continue
                val target = HashMap<Long, Float>(ids.size)
                for (id in ids) {
                    var sum = 0f
                    var count = 0
                    for (parent in parentById[id].orEmpty()) {
                        if (!allowed.contains(parent)) continue
                        val px = xById[parent] ?: continue
                        sum += px
                        count++
                    }
                    target[id] = if (count > 0) sum / count.toFloat() else (xById[id] ?: 0f)
                }
                applyTieBiasOrdering(ids, target, descendantBias)
                applyNonOverlapInLayer(ids, target)
            }
        }

        fun doUpPass() {
            val descendantBias = computeDescendantBias()
            for (depth in (maxDepth - 1) downTo 0) {
                val ids = depthToIds[depth] ?: continue
                val current = HashMap<Long, Float>(ids.size)
                for (id in ids) current[id] = xById[id] ?: 0f
                applyTieBiasOrdering(ids, current, descendantBias)
                applyNonOverlapInLayer(ids, current)
            }
        }

        val seededIds = seedLogicalPositions?.keys ?: emptySet()
        val removedCount = seededIds.count { !allowed.contains(it) }
        val addedCount = allowed.count { !seededIds.contains(it) }
        val churnRatio = if (allowed.isEmpty()) 1f else (addedCount + removedCount).toFloat() / allowed.size.toFloat()
        val useIncrementalPassBudget = seedLogicalPositions != null && churnRatio <= 0.10f

        var downRemaining = if (useIncrementalPassBudget) 1 else maxDepth
        var upRemaining = if (useIncrementalPassBudget) 1 else (maxDepth - 1).coerceAtLeast(0)
        if (downRemaining > 0) {
            doDownPass()
            downRemaining--
        }
        while (downRemaining > 0 || upRemaining > 0) {
            if (upRemaining > 0) {
                doUpPass()
                upRemaining--
            }
            if (downRemaining > 0) {
                doDownPass()
                downRemaining--
            }
        }
    }
}
