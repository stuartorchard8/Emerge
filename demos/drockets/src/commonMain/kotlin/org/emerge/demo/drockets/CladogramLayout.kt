package org.emerge.demo.drockets

/**
 * Screen-space layout for a lineage DAG in panel coordinates: x in [0,1] left→right by
 * generation depth, y in [0,1] bottom→top within each depth band.
 */
data class CladogramStats(
    val nodeCount: Int,
    val livingCount: Int,
    val deadCount: Int,
    val maxDepth: Int,
    val edgeCount: Int,
    val rootCount: Int,
)

data class CladogramLayout(
    val positions: Map<Long, Pair<Float, Float>>,
    val edges: List<Pair<Long, Long>>,
    val depthById: Map<Long, Int>,
    val stats: CladogramStats,
) {
    fun summaryLine(): String =
        "CLADE N=${stats.nodeCount} L=${stats.livingCount} D=${stats.deadCount} DEPTH=${stats.maxDepth} E=${stats.edgeCount} R=${stats.rootCount}"

    companion object {
        fun build(lineage: DrocketLineageState): CladogramLayout {
            val nodes = lineage.nodes
            if (nodes.isEmpty()) {
                return CladogramLayout(
                    positions = emptyMap(),
                    edges = emptyList(),
                    depthById = emptyMap(),
                    stats = CladogramStats(0, 0, 0, 0, 0, 0),
                )
            }

            val sortedByBirth = nodes.values.sortedBy { it.birthTick }
            val depthById = LinkedHashMap<Long, Int>(nodes.size)

            for (node in sortedByBirth) {
                val md = node.motherLineageId?.let { depthById[it] }
                val fd = node.fatherLineageId?.let { depthById[it] }
                val d = when {
                    md == null && fd == null -> 0
                    md == null -> (fd ?: -1) + 1
                    fd == null -> md + 1
                    else -> maxOf(md, fd) + 1
                }
                depthById[node.lineageId] = d
            }

            val maxDepth = depthById.values.maxOrNull() ?: 0
            val depthGroups = LinkedHashMap<Int, MutableList<Long>>()
            for ((id, d) in depthById) {
                depthGroups.getOrPut(d) { mutableListOf() }.add(id)
            }
            for (list in depthGroups.values) {
                list.sort()
            }

            val positions = LinkedHashMap<Long, Pair<Float, Float>>()
            val denom = maxOf(maxDepth, 1)
            for ((depth, ids) in depthGroups.toSortedMap()) {
                val n = ids.size.coerceAtLeast(1)
                ids.forEachIndexed { index, id ->
                    val x = 0.08f + (depth.toFloat() / denom) * 0.84f
                    val y = 0.08f + (index + 1).toFloat() / (n + 1).toFloat() * 0.84f
                    positions[id] = Pair(x, y)
                }
            }

            val edgeSet = LinkedHashSet<Pair<Long, Long>>()
            for (node in nodes.values) {
                val id = node.lineageId
                node.motherLineageId?.let { m ->
                    if (nodes.containsKey(m)) edgeSet.add(m to id)
                }
                node.fatherLineageId?.let { f ->
                    if (nodes.containsKey(f)) edgeSet.add(f to id)
                }
            }

            val roots = nodes.values.count { it.motherLineageId == null && it.fatherLineageId == null }
            val living = lineage.livingLineageIds.size
            val stats = CladogramStats(
                nodeCount = nodes.size,
                livingCount = living,
                deadCount = nodes.size - living,
                maxDepth = maxDepth,
                edgeCount = edgeSet.size,
                rootCount = roots,
            )

            return CladogramLayout(
                positions = positions,
                edges = edgeSet.toList(),
                depthById = depthById,
                stats = stats,
            )
        }
    }
}
