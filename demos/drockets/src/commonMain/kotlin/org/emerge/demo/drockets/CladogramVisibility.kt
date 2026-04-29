package org.emerge.demo.drockets

enum class CladogramFilterMode {
    ALL,
    LIVING_ONLY,
    LIVING_AND_PARENTS,
    LIVING_AND_CONNECTORS,
}

fun computeVisibleLineageIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
    filterMode: CladogramFilterMode,
): Set<Long> {
    val allIds = layout.depthById.keys
    return when (filterMode) {
        CladogramFilterMode.ALL -> allIds.toSet()
        CladogramFilterMode.LIVING_ONLY -> allIds.filterTo(LinkedHashSet()) { lineage.livingLineageIds.contains(it) }
        CladogramFilterMode.LIVING_AND_PARENTS -> {
            val out = LinkedHashSet<Long>()
            for (id in lineage.livingLineageIds) {
                if (!allIds.contains(id)) continue
                out.add(id)
                val node = lineage.nodes[id] ?: continue
                val m = node.motherLineageId
                val f = node.fatherLineageId
                if (m != null && allIds.contains(m)) out.add(m)
                if (f != null && allIds.contains(f)) out.add(f)
            }
            out
        }
        CladogramFilterMode.LIVING_AND_CONNECTORS -> {
            val living = lineage.livingLineageIds.filterTo(LinkedHashSet()) { allIds.contains(it) }
            val children = HashMap<Long, MutableList<Long>>()
            for (id in allIds) children[id] = mutableListOf()
            for (id in allIds) {
                val node = lineage.nodes[id] ?: continue
                node.motherLineageId?.let { p -> if (allIds.contains(p)) children.getOrPut(p) { mutableListOf() }.add(id) }
                node.fatherLineageId?.let { p -> if (allIds.contains(p)) children.getOrPut(p) { mutableListOf() }.add(id) }
            }
            fun livingCountCapped(nodeId: Long, memo: MutableMap<Long, Int>): Int {
                memo[nodeId]?.let { return it }
                var count = if (living.contains(nodeId)) 1 else 0
                if (count < 2) {
                    for (child in children[nodeId].orEmpty()) {
                        count += livingCountCapped(child, memo)
                        if (count >= 2) {
                            count = 2
                            break
                        }
                    }
                }
                memo[nodeId] = count
                return count
            }
            val memo = HashMap<Long, Int>(allIds.size)

            val out = LinkedHashSet<Long>()
            out.addAll(living)
            val sharedHits = LinkedHashSet<Long>()

            for (seed in living) {
                data class PathNode(val id: Long, val prev: Long?)
                val q = ArrayDeque<PathNode>()
                val seen = HashSet<Long>()
                val startNode = lineage.nodes[seed]
                val startParents = listOfNotNull(startNode?.motherLineageId, startNode?.fatherLineageId)
                for (p in startParents) {
                    if (!allIds.contains(p)) continue
                    if (seen.add(p)) q.addLast(PathNode(p, seed))
                }

                var hitShared: PathNode? = null
                val prevById = HashMap<Long, Long?>()
                while (q.isNotEmpty()) {
                    val cur = q.removeFirst()
                    prevById[cur.id] = cur.prev
                    if (livingCountCapped(cur.id, memo) >= 2) {
                        hitShared = cur
                        break
                    }
                    val n = lineage.nodes[cur.id] ?: continue
                    val parents = listOfNotNull(n.motherLineageId, n.fatherLineageId)
                    for (p in parents) {
                        if (!allIds.contains(p)) continue
                        if (seen.add(p)) q.addLast(PathNode(p, cur.id))
                    }
                }

                var cur: Long? = hitShared?.id
                while (cur != null && cur != seed) {
                    out.add(cur)
                    cur = prevById[cur]
                }
                if (hitShared != null) {
                    sharedHits.add(hitShared.id)
                }
            }

            for (root in sharedHits) {
                val q = ArrayDeque<Long>()
                q.addLast(root)
                while (q.isNotEmpty()) {
                    val cur = q.removeFirst()
                    for (child in children[cur].orEmpty()) {
                        if (!allIds.contains(child)) continue
                        if (livingCountCapped(child, memo) <= 0) continue
                        out.add(child)
                        q.addLast(child)
                    }
                }
            }
            out
        }
    }
}
