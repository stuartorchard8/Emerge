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

            val mrca = findPairwiseMrca(distI, distJ) ?: continue

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

/**
 * Incremental cache for [CladogramFilterMode.LIVING_PAIRWISE_MRCA].
 *
 * The stateless [computeVisibleLineageIds] for this mode is O(L × (V+E) + L²) per call.
 * Re-running it every frame in a sim with active reproduction (cache invalidates on each
 * birth or death) is the source of the ~1s frame stalls.
 *
 * This cache instead maintains the pair-wise structure across frames and updates it
 * incrementally:
 *
 *   - [parents]:        adjacency from lineage edges, grows monotonically
 *   - [livingBfs]:      per-living `(distances, predecessors)` BFS-up; computed once
 *                       at birth, retained until death. Existing living individuals'
 *                       BFS results never change because new births only add children,
 *                       not new ancestors of anyone already alive.
 *   - [pairPaths]:      per-(living, living) unordered-pair → set-of-nodes-on-shortest-path
 *   - [nodeRefCount]:   per-node count of paths that reference it; node is visible iff
 *                       count > 0 (or the node is itself currently living)
 *   - [pairsByLiving]:  reverse index so death-cleanup is O(L × pathLength) instead of
 *                       O(L² × pathLength)
 *
 * Per delta cost:
 *
 *   Birth of new living N:
 *     - one BFS-up from N (O(|ancestors of N|))
 *     - L−1 new pair paths (each: O(|N's ancestors| + pathLength))
 *
 *   Death of living D:
 *     - look up D's L−1 pairs via [pairsByLiving]
 *     - for each, decrement [nodeRefCount] for path nodes
 *
 * First frame is still O(L²) — there's no shortcut for the initial build. Subsequent
 * frames pay only the delta.
 *
 * On snapshot load (or any lineage state that has nodes the cache thought existed but
 * no longer do), the cache detects via [parents] not matching [DrocketLineageState.nodes]
 * and resets to a full rebuild.
 */
internal class PairwiseMrcaUnionCache {
    private val parents = HashMap<Long, List<Long>>()
    private val livingBfs = HashMap<Long, Pair<Map<Long, Int>, Map<Long, Long>>>()
    private val pairPaths = HashMap<UnorderedPair, Set<Long>>()
    private val pairsByLiving = HashMap<Long, MutableSet<UnorderedPair>>()
    private val nodeRefCount = HashMap<Long, Int>()
    private val livingMembers = LinkedHashSet<Long>()
    private val visibleSet = LinkedHashSet<Long>()

    /** Returns the current pair-wise MRCA union, applying any deltas since the last call. */
    fun visibleFor(lineage: DrocketLineageState, layout: CladogramLayout): Set<Long> {
        ensureCurrent(lineage, layout)
        return visibleSet
    }

    /**
     * Resets the cache to empty. Called automatically when a stale-cache condition is
     * detected (e.g. snapshot load); also useful in tests.
     */
    fun reset() {
        parents.clear(); livingBfs.clear(); pairPaths.clear()
        pairsByLiving.clear(); nodeRefCount.clear()
        livingMembers.clear(); visibleSet.clear()
    }

    private fun ensureCurrent(lineage: DrocketLineageState, layout: CladogramLayout) {
        // Stale detection: if any cached node ID has disappeared (e.g. snapshot reload
        // replaced the lineage with an earlier state), full reset.
        if (parents.keys.any { it !in lineage.nodes }) reset()

        val visibleIds = layout.depthById.keys

        // Step 1: catch up parents adjacency for any new visible nodes. Births never
        // mutate existing parents entries, so this is purely additive.
        for ((id, node) in lineage.nodes) {
            if (id !in visibleIds || id in parents) continue
            val ps = buildList<Long>(2) {
                node.motherLineageId?.let { if (it in visibleIds) add(it) }
                node.fatherLineageId?.let { if (it in visibleIds) add(it) }
            }
            parents[id] = ps
        }

        // Step 2: diff the living set.
        val currentLiving = lineage.livingLineageIds.filterTo(LinkedHashSet()) { it in visibleIds }
        val deaths = livingMembers.filter { it !in currentLiving }
        val births = currentLiving.filter { it !in livingMembers }

        // Step 3: remove pairs for deaths first (so newly-born pairs don't reference
        // about-to-be-removed BFS results).
        for (dead in deaths) {
            removeAllPairsInvolving(dead)
            livingBfs.remove(dead)
            livingMembers.remove(dead)
            if ((nodeRefCount[dead] ?: 0) == 0) visibleSet.remove(dead)
        }

        // Step 4: add new births. BFS-up once each, then compute pair paths with every
        // already-known living. The order of `births` here matters only for the
        // tie-break order of equal-distance pair paths; the resulting visible set is
        // the same.
        for (newLife in births) {
            livingBfs[newLife] = bfsAncestorTree(newLife, parents)
            visibleSet.add(newLife)
            for (other in livingMembers) {
                if (other == newLife) continue
                addPair(newLife, other)
            }
            livingMembers.add(newLife)
        }
    }

    private fun addPair(a: Long, b: Long) {
        val (distA, predA) = livingBfs[a] ?: return
        val (distB, predB) = livingBfs[b] ?: return

        val mrca = findPairwiseMrca(distA, distB) ?: return

        // Reconstruct the path by walking pred chains from MRCA back to each seed.
        val path = LinkedHashSet<Long>()
        var cur: Long? = mrca
        while (cur != null) { path += cur; cur = predA[cur] }
        cur = mrca
        while (cur != null) { path += cur; cur = predB[cur] }

        val key = UnorderedPair.of(a, b)
        pairPaths[key] = path
        pairsByLiving.getOrPut(a) { mutableSetOf() }.add(key)
        pairsByLiving.getOrPut(b) { mutableSetOf() }.add(key)
        for (n in path) {
            nodeRefCount[n] = (nodeRefCount[n] ?: 0) + 1
            visibleSet.add(n)
        }
    }

    private fun removeAllPairsInvolving(dead: Long) {
        val pairs = pairsByLiving.remove(dead) ?: return
        for (key in pairs) {
            val path = pairPaths.remove(key) ?: continue
            // Also drop the pair from the OTHER endpoint's index so we don't leak.
            val otherEndpoint = if (key.a == dead) key.b else key.a
            pairsByLiving[otherEndpoint]?.remove(key)
            for (n in path) {
                val rc = (nodeRefCount[n] ?: 0) - 1
                if (rc <= 0) {
                    nodeRefCount.remove(n)
                    // Keep n visible if it's currently a living member (it'll be removed
                    // from visibleSet later when its own death is processed, if it is dead).
                    if (n !in livingMembers) visibleSet.remove(n)
                } else {
                    nodeRefCount[n] = rc
                }
            }
        }
    }
}

/** Unordered pair of lineage IDs, normalised so `a < b`. */
internal data class UnorderedPair(val a: Long, val b: Long) {
    companion object {
        fun of(x: Long, y: Long): UnorderedPair =
            if (x < y) UnorderedPair(x, y) else UnorderedPair(y, x)
    }
}

/**
 * Finds the MRCA between two lineage nodes given their BFS-up distance maps.
 *
 * The intersection of `distA.keys` and `distB.keys` is the set of common ancestors.
 * Each candidate has a cost `dA + dB`. Tie-break order, to make the choice
 * deterministic and (importantly) independent of which side's distance map is iterated:
 *
 *   1. Smallest cost `dA + dB`.
 *   2. Smallest `min(dA, dB)` — prefers MRCAs that ARE one of the endpoints (cost
 *      split 0+k) over symmetrically-distant cousins (split k+k). Semantically this
 *      matches conventional "lowest common ancestor": when one endpoint is an
 *      ancestor of the other, the MRCA is the ancestor endpoint itself.
 *   3. Smallest id — final deterministic catch.
 *
 * Without rule 2 the cache and the stateless function can pick different MRCAs for the
 * same pair because they iterate `distA` from different sides; that disagreement was
 * the source of cache-drift caught by the long-sequence fuzz test.
 */
internal fun findPairwiseMrca(distA: Map<Long, Int>, distB: Map<Long, Int>): Long? {
    var bestMrca: Long? = null
    var bestCost = Int.MAX_VALUE
    var bestMinDist = Int.MAX_VALUE
    for ((id, dA) in distA) {
        val dB = distB[id] ?: continue
        val cost = dA + dB
        val minDist = if (dA < dB) dA else dB
        val better = when {
            cost < bestCost -> true
            cost > bestCost -> false
            minDist < bestMinDist -> true
            minDist > bestMinDist -> false
            else -> id < (bestMrca ?: Long.MAX_VALUE)
        }
        if (better) {
            bestCost = cost
            bestMinDist = minDist
            bestMrca = id
        }
    }
    return bestMrca
}
