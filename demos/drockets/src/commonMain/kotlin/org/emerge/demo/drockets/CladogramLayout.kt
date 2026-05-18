package org.emerge.demo.drockets

/**
 * Aggregate counts for the HUD summary line — purely derived from the layout.
 */
data class CladogramStats(
    val nodeCount: Int,
    val livingCount: Int,
    val deadCount: Int,
    val maxDepth: Int,
    val edgeCount: Int,
    val rootCount: Int,
)

/**
 * Topology + per-node depth for a lineage DAG. The downstream solvers
 * ([CladogramLayoutSolver], [ForceDirectedLayoutSolver]) read `depthById` and
 * `edges`; `stats` feeds the HUD summary line. There is intentionally no
 * `positions` field — actual placement is computed by the layout solvers per
 * frame from these primitives.
 */
data class CladogramLayout(
    val edges: List<Pair<Long, Long>>,
    val depthById: Map<Long, Int>,
    val stats: CladogramStats,
) {
    fun summaryLine(): String =
        "CLADE N=${stats.nodeCount} L=${stats.livingCount} D=${stats.deadCount} DEPTH=${stats.maxDepth} E=${stats.edgeCount} R=${stats.rootCount}"

    companion object {
        /** Build from scratch. Used by tests and as a reference implementation;
         *  the runtime hot path goes through [CladogramLayoutMemo]. */
        fun build(lineage: DrocketLineageState): CladogramLayout {
            val nodes = lineage.nodes
            if (nodes.isEmpty()) {
                return CladogramLayout(
                    edges = emptyList(),
                    depthById = emptyMap(),
                    stats = CladogramStats(0, 0, 0, 0, 0, 0),
                )
            }

            val depthById = LinkedHashMap<Long, Int>(nodes.size)
            var maxDepth = 0
            var rootCount = 0
            val edges = ArrayList<Pair<Long, Long>>()
            // Lineage ids are assigned in birth order, so iterating in id order
            // (== LinkedHashMap insertion order) processes parents before
            // children — no sort by birthTick required.
            for (node in nodes.values) {
                val id = node.lineageId
                val md = node.motherLineageId?.let { depthById[it] }
                val fd = node.fatherLineageId?.let { depthById[it] }
                val d = when {
                    md == null && fd == null -> { rootCount++; 0 }
                    md == null -> (fd ?: -1) + 1
                    fd == null -> md + 1
                    else -> maxOf(md, fd) + 1
                }
                depthById[id] = d
                if (d > maxDepth) maxDepth = d
                node.motherLineageId?.let { if (depthById.containsKey(it)) edges.add(it to id) }
                node.fatherLineageId?.let { if (depthById.containsKey(it)) edges.add(it to id) }
            }

            val living = lineage.livingLineageIds.size
            return CladogramLayout(
                edges = edges,
                depthById = depthById,
                stats = CladogramStats(
                    nodeCount = nodes.size,
                    livingCount = living,
                    deadCount = nodes.size - living,
                    maxDepth = maxDepth,
                    edgeCount = edges.size,
                    rootCount = rootCount,
                ),
            )
        }
    }
}

/**
 * Incremental cladogram-layout maintainer. Maintains `depthById` and `edges`
 * across ticks, appending one entry per birth in O(1) (depth =
 * max(parent depth) + 1, edge added directly) instead of the full O(n)
 * rebuild that [CladogramLayout.build] performs. Deaths don't affect topology
 * — a dead node keeps its depth and edges — so death events only touch the
 * `livingCount` / `deadCount` fields of `stats`.
 *
 * Snapshot regression (`nextLineageId` or `nodes.size` shrinks) resets the
 * builder, forcing a fresh incremental walk over the new state.
 *
 * The returned [CladogramLayout] aliases the builder's internal mutable maps,
 * so callers that hold the reference across multiple `get()` calls will see
 * the latest state (acceptable in the single-threaded game loop).
 */
class CladogramLayoutMemo {
    private val depthById = LinkedHashMap<Long, Int>()
    private val edges = ArrayList<Pair<Long, Long>>()
    private var maxDepth = 0
    private var rootCount = 0
    private var nextLineageIdWatermark = 0L
    private var lastNodeCount = 0
    private var lastLivingCount = -1
    private var cached: CladogramLayout? = null

    fun get(lineage: DrocketLineageState): CladogramLayout {
        if (lineage.nextLineageId < nextLineageIdWatermark || lineage.nodes.size < lastNodeCount) {
            reset()
        }
        var structuralChange = false
        for (id in nextLineageIdWatermark until lineage.nextLineageId) {
            val node = lineage.nodes[id] ?: continue
            val md = node.motherLineageId?.let { depthById[it] }
            val fd = node.fatherLineageId?.let { depthById[it] }
            val d = when {
                md == null && fd == null -> { rootCount++; 0 }
                md == null -> (fd ?: -1) + 1
                fd == null -> md + 1
                else -> maxOf(md, fd) + 1
            }
            depthById[id] = d
            if (d > maxDepth) maxDepth = d
            node.motherLineageId?.let { if (depthById.containsKey(it)) edges.add(it to id) }
            node.fatherLineageId?.let { if (depthById.containsKey(it)) edges.add(it to id) }
            structuralChange = true
        }
        nextLineageIdWatermark = lineage.nextLineageId
        lastNodeCount = lineage.nodes.size

        val livingCount = lineage.livingLineageIds.size
        if (cached == null || structuralChange || livingCount != lastLivingCount) {
            cached = CladogramLayout(
                edges = edges,
                depthById = depthById,
                stats = CladogramStats(
                    nodeCount = depthById.size,
                    livingCount = livingCount,
                    deadCount = depthById.size - livingCount,
                    maxDepth = maxDepth,
                    edgeCount = edges.size,
                    rootCount = rootCount,
                ),
            )
            lastLivingCount = livingCount
        }
        return cached!!
    }

    fun reset() {
        depthById.clear()
        edges.clear()
        maxDepth = 0
        rootCount = 0
        nextLineageIdWatermark = 0L
        lastNodeCount = 0
        lastLivingCount = -1
        cached = null
    }
}
