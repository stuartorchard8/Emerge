package org.emerge.demo.drockets

enum class CladogramFilterMode {
    ALL,
    LIVING_ONLY,
    LIVING_AND_PARENTS,

    /**
     * Per-living BFS-up approach: for each living individual, walk up to its closest
     * branching ancestor (one with ≥2 living-line child branches), include the path back,
     * then sweep down from each branching ancestor to gather all living-line descendants.
     * Approximates MRCA-style relatedness; tends to include slightly more nodes than the
     * strict Steiner subtree (see [LIVING_STEINER]) because the per-individual walks can
     * cover sibling branches that aren't on a path between two living.
     */
    LIVING_AND_CONNECTORS,

    /**
     * Strict Steiner-subtree of the living set. A node is included iff:
     *   - it is itself living, OR
     *   - removing it would disconnect some pair of living individuals.
     *
     * Equivalently (for our 2-parent DAG): it is living, OR it has ≥2 child-branches each
     * containing a living descendant (a branching ancestor / LCA), OR it has at least one
     * living descendant in its subtree AND at least one living elsewhere in the same
     * weakly-connected component (it's on a path between in-subtree and out-of-subtree
     * living).
     *
     * Tends to be a tighter view than [LIVING_AND_CONNECTORS]: long sibling branches with
     * only one living descendant don't pull in their ancestors past the closest LCA.
     */
    LIVING_STEINER,
}

fun computeVisibleLineageIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
    filterMode: CladogramFilterMode,
): Set<Long> {
    val allIds = layout.depthById.keys
    return when (filterMode) {
        CladogramFilterMode.LIVING_STEINER -> computeSteinerVisibleIds(lineage, layout)
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
            if (living.isEmpty()) return emptySet()
            val nodesById = lineage.nodes
            val children = HashMap<Long, MutableList<Long>>()
            for (id in allIds) children[id] = mutableListOf()
            for (id in allIds) {
                val node = nodesById[id] ?: continue
                node.motherLineageId?.let { p -> if (allIds.contains(p)) children.getOrPut(p) { mutableListOf() }.add(id) }
                node.fatherLineageId?.let { p -> if (allIds.contains(p)) children.getOrPut(p) { mutableListOf() }.add(id) }
            }

            // Bottom-up precompute of living-descendant counts (capped to 2).
            val livingCountById = HashMap<Long, Int>(allIds.size)
            for (id in allIds) livingCountById[id] = if (living.contains(id)) 1 else 0
            val idsByDepthDesc = layout.depthById.entries
                .sortedByDescending { it.value }
                .map { it.key }
            for (id in idsByDepthDesc) {
                var count = livingCountById[id] ?: 0
                if (count >= 2) continue
                for (child in children[id].orEmpty()) {
                    count += livingCountById[child] ?: 0
                    if (count >= 2) {
                        count = 2
                        break
                    }
                }
                livingCountById[id] = count
            }

            fun hasAtLeastTwoLivingChildBranches(nodeId: Long): Boolean {
                var branches = 0
                for (child in children[nodeId].orEmpty()) {
                    if ((livingCountById[child] ?: 0) > 0) {
                        branches++
                        if (branches >= 2) return true
                    }
                }
                return false
            }

            val out = LinkedHashSet<Long>()
            out.addAll(living)
            val sharedHits = LinkedHashSet<Long>()

            for (seed in living) {
                val q = ArrayDeque<Long>()
                val seen = HashSet<Long>()
                val prevById = HashMap<Long, Long?>()
                val startNode = nodesById[seed]
                val m0 = startNode?.motherLineageId
                val f0 = startNode?.fatherLineageId
                if (m0 != null) {
                    val p = m0
                    if (allIds.contains(p) && seen.add(p)) {
                        prevById[p] = seed
                        q.addLast(p)
                    }
                }
                if (f0 != null) {
                    val p = f0
                    if (allIds.contains(p) && seen.add(p)) {
                        prevById[p] = seed
                        q.addLast(p)
                    }
                }

                var hitShared: Long? = null
                while (q.isNotEmpty()) {
                    val cur = q.removeFirst()
                    if (hasAtLeastTwoLivingChildBranches(cur)) {
                        hitShared = cur
                        break
                    }
                    val n = nodesById[cur] ?: continue
                    val m = n.motherLineageId
                    val f = n.fatherLineageId
                    if (m != null) {
                        val p = m
                        if (allIds.contains(p) && seen.add(p)) {
                            prevById[p] = cur
                            q.addLast(p)
                        }
                    }
                    if (f != null) {
                        val p = f
                        if (allIds.contains(p) && seen.add(p)) {
                            prevById[p] = cur
                            q.addLast(p)
                        }
                    }
                }

                var cur: Long? = hitShared
                while (cur != null && cur != seed) {
                    out.add(cur)
                    cur = prevById[cur]
                }
                if (hitShared != null) sharedHits.add(hitShared)
            }

            for (root in sharedHits) {
                val q = ArrayDeque<Long>()
                val seen = HashSet<Long>()
                q.addLast(root)
                seen.add(root)
                while (q.isNotEmpty()) {
                    val cur = q.removeFirst()
                    for (child in children[cur].orEmpty()) {
                        if ((livingCountById[child] ?: 0) <= 0) continue
                        out.add(child)
                        if (seen.add(child)) q.addLast(child)
                    }
                }
            }
            out
        }
    }
}

/**
 * Strict Steiner-subtree of the living set on the lineage DAG. See [CladogramFilterMode.LIVING_STEINER]
 * for the inclusion rule.
 *
 * Implementation:
 *   1. BFS up from each living individual through parent edges, recording per-ancestor the
 *      *set* of living individuals that reach it. Set-based propagation handles the 2-parent
 *      DAG case where one living can be a descendant of two unrelated ancestors without
 *      double-counting.
 *   2. Build a children adjacency over visible edges (any edge whose endpoints exist in
 *      [layout]).
 *   3. Walk the weakly-connected components over the undirected edge graph to get a
 *      `component-id -> total-living-in-that-component` map; nodes inherit a component-id
 *      from this walk.
 *   4. For each node N, evaluate the three inclusion rules:
 *      (a) N is living.
 *      (b) `livingBranches(N)` >= 2 where `livingBranches` counts children of N whose
 *          `livingBelow` set is non-empty.  (Captures LCAs / branching ancestors.)
 *      (c) `livingBelow(N).size >= 1` AND `componentTotalLiving(N) - livingBelow(N).size >= 1`.
 *          (Captures "on a path between living inside and living outside N's subtree".)
 *
 * Cost is O(L × (V+E)) for the BFS-up phase (each living BFS visits each of its ancestors
 * once) + O(V+E) for the component walk + O(V + Σchildren) for the per-node inclusion test.
 * For a few hundred living drockets on a few-thousand-node lineage that's well under a
 * millisecond and the result is cached by [CladogramPanelRenderer] / [LineageOverlay].
 */
private fun computeSteinerVisibleIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
): Set<Long> {
    val allIds = layout.depthById.keys
    if (allIds.isEmpty()) return emptySet()
    val nodesById = lineage.nodes
    val living = lineage.livingLineageIds.filterTo(LinkedHashSet()) { it in allIds }
    if (living.isEmpty()) return emptySet()

    // Visible parents / children adjacency.
    val parents = HashMap<Long, MutableList<Long>>(allIds.size)
    val children = HashMap<Long, MutableList<Long>>(allIds.size)
    val undirected = HashMap<Long, MutableList<Long>>(allIds.size)
    for (id in allIds) {
        val node = nodesById[id] ?: continue
        node.motherLineageId?.let { m -> if (m in allIds) { parents.getOrPut(id) { mutableListOf() }.add(m); children.getOrPut(m) { mutableListOf() }.add(id) } }
        node.fatherLineageId?.let { f -> if (f in allIds) { parents.getOrPut(id) { mutableListOf() }.add(f); children.getOrPut(f) { mutableListOf() }.add(id) } }
    }
    for ((c, ps) in parents) for (p in ps) {
        undirected.getOrPut(c) { mutableListOf() }.add(p)
        undirected.getOrPut(p) { mutableListOf() }.add(c)
    }

    // BFS-up from each living: union L into livingBelow[N] for every N reachable.
    val livingBelow = HashMap<Long, MutableSet<Long>>(allIds.size)
    for (l in living) {
        val seen = HashSet<Long>()
        val q = ArrayDeque<Long>()
        q.addLast(l)
        seen.add(l)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            livingBelow.getOrPut(cur) { mutableSetOf() }.add(l)
            for (p in parents[cur].orEmpty()) {
                if (seen.add(p)) q.addLast(p)
            }
        }
    }

    // Weakly-connected components → per-component living total.
    val componentOf = HashMap<Long, Int>(allIds.size)
    val componentLiving = HashMap<Int, Int>()
    var nextComp = 0
    for (id in allIds) {
        if (componentOf.containsKey(id)) continue
        val compId = nextComp++
        var compLiving = 0
        val q = ArrayDeque<Long>()
        q.addLast(id)
        componentOf[id] = compId
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            if (cur in living) compLiving++
            for (n in undirected[cur].orEmpty()) {
                if (!componentOf.containsKey(n)) {
                    componentOf[n] = compId
                    q.addLast(n)
                }
            }
        }
        componentLiving[compId] = compLiving
    }

    // Inclusion test.
    val out = LinkedHashSet<Long>()
    for (id in allIds) {
        if (id in living) { out += id; continue }
        val below = livingBelow[id]?.size ?: 0
        if (below == 0) continue

        // Rule (b): N is a branching ancestor (≥2 child-branches with living below).
        var livingBranches = 0
        for (child in children[id].orEmpty()) {
            if ((livingBelow[child]?.size ?: 0) > 0) {
                livingBranches++
                if (livingBranches >= 2) break
            }
        }
        if (livingBranches >= 2) { out += id; continue }

        // Rule (c): some living below N, some living elsewhere in the same component.
        val compId = componentOf[id] ?: continue
        val total = componentLiving[compId] ?: 0
        if (total - below >= 1) { out += id; continue }
    }
    return out
}
