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
     * Union of pair-wise MRCA paths over the living set. For every pair of living
     * individuals (L_i, L_j), finds their MRCA and the shortest path between them through
     * the visible parent edges; the visible subgraph is the union of all such paths plus
     * every living node.
     *
     * Properties:
     *   - **Guarantees connectivity within each weakly-connected component.** Every pair
     *     of living in the same component has a path between them in the result.
     *   - **Tighter than strict Steiner on a multi-parent DAG.** When a child has two
     *     parents that both lead to common ancestors, only one parent's path is included
     *     (whichever the BFS reached the MRCA through first); the redundant parent isn't
     *     pulled in. On a strict tree (single parent everywhere) this is equivalent to
     *     the Steiner subtree.
     *   - **Bounded above by the per-component all-living-MRCA.** Nothing above the
     *     deepest common ancestor of all component-living is included.
     *
     * Cost: O(L × (V+E)) for per-living BFS-up + O(L²) for pairwise intersections, where
     * L is the living count, V/E are the visible-node and visible-edge counts. For L≈200
     * this is well under a frame budget, and the consuming renderer caches the result.
     */
    LIVING_PAIRWISE_MRCA,
}

fun computeVisibleLineageIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
    filterMode: CladogramFilterMode,
): Set<Long> {
    val allIds = layout.depthById.keys
    return when (filterMode) {
        CladogramFilterMode.LIVING_PAIRWISE_MRCA -> computePairwiseMrcaUnionVisibleIds(lineage, layout)
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
 * Union of pair-wise MRCA paths over the living set. See [CladogramFilterMode.LIVING_PAIRWISE_MRCA]
 * for the user-facing description.
 *
 * Implementation:
 *   1. Build the parents adjacency restricted to visible nodes.
 *   2. BFS up from each living individual through parent edges, caching the per-ancestor
 *      (distance, predecessor) maps. The predecessor map points toward the seed, so a
 *      walk from any ancestor terminates at the seed.
 *   3. For every pair of living (i, j): intersect the reachable-ancestor sets; pick the
 *      common ancestor with minimum (dist-from-i + dist-from-j) as the pair's MRCA. Walk
 *      from the MRCA back to each seed via that seed's pred chain and add every visited
 *      node to the visible set.
 *
 * Why this is tighter than strict Steiner on a multi-parent DAG: when a child has two
 * parents and only one is needed to reach the common ancestor, the BFS picks one and
 * the other is never on the reconstructed path. Strict Steiner's "either parent has
 * subtree-living plus complement-living" inclusion rule would pull both in.
 *
 * Why connectivity is guaranteed: every pair (i, j) in the same weakly-connected
 * component shares at least one ancestor (the component's structure forces it). The
 * union of all pair-wise paths therefore forms a connected subgraph spanning all living
 * in that component. Pairs in different components contribute no path; each component's
 * living are visible on their own.
 */
private fun computePairwiseMrcaUnionVisibleIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
): Set<Long> {
    val allIds = layout.depthById.keys
    if (allIds.isEmpty()) return emptySet()
    val living = lineage.livingLineageIds.filter { it in allIds }
    if (living.isEmpty()) return emptySet()

    // Parents adjacency restricted to currently-visible nodes.
    val parents = HashMap<Long, MutableList<Long>>(allIds.size)
    for (id in allIds) {
        val node = lineage.nodes[id] ?: continue
        node.motherLineageId?.let { m -> if (m in allIds) parents.getOrPut(id) { mutableListOf() }.add(m) }
        node.fatherLineageId?.let { f -> if (f in allIds) parents.getOrPut(id) { mutableListOf() }.add(f) }
    }

    // BFS-up cache: one per living individual.
    val trees = HashMap<Long, Pair<Map<Long, Int>, Map<Long, Long>>>(living.size)
    for (l in living) trees[l] = bfsAncestorTree(l, parents)

    val visible = LinkedHashSet<Long>()
    visible.addAll(living)

    for (i in living.indices) {
        val (distI, predI) = trees[living[i]] ?: continue
        for (j in (i + 1) until living.size) {
            val (distJ, predJ) = trees[living[j]] ?: continue

            // Intersect ancestor sets; pick the meeting node minimising combined distance.
            var bestMrca: Long? = null
            var bestCost = Int.MAX_VALUE
            for ((id, dI) in distI) {
                val dJ = distJ[id] ?: continue
                val cost = dI + dJ
                if (cost < bestCost) {
                    bestCost = cost
                    bestMrca = id
                }
            }
            val mrca = bestMrca ?: continue

            // Walk MRCA -> living[i] via predI, and MRCA -> living[j] via predJ.
            // Predecessors point toward each seed, so these chains terminate at the seed.
            var cur: Long? = mrca
            while (cur != null) { visible += cur; cur = predI[cur] }
            cur = mrca
            while (cur != null) { visible += cur; cur = predJ[cur] }
        }
    }
    return visible
}
