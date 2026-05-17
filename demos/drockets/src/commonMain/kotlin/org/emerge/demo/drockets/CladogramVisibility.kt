package org.emerge.demo.drockets

enum class CladogramFilterMode {
    ALL,
    LIVING_ONLY,

    /**
     * Per-living BFS-up approach: for each living individual, walk up to its closest
     * branching ancestor (one with ≥2 living-line child branches), include the path back,
     * then sweep down from each branching ancestor to gather all living-line descendants.
     * Approximates MRCA-style relatedness; doesn't guarantee that *all* living pairs share
     * a visible ancestor (each living's walk stops at its nearest LCA, which may differ
     * across the population — disjoint clades can end up disconnected in the view). Use
     * [LIVING_ANCESTRY] when full pair-wise connectivity matters.
     */
    LIVING_AND_CONNECTORS,

    /**
     * Full ancestry of every living individual. A node is visible iff it is itself
     * living OR it has at least one living descendant via the parent edges.
     *
     * Properties:
     *   - **Connectivity within each weakly-connected component is automatic.** Every
     *     living's ancestor chain is included; two livings that share an ancestor are
     *     connected through it.
     *   - **Both parents' lineages are visible in DAG cases.** Where a node has two
     *     parents (sexual reproduction), both ancestor chains are walked — unlike a
     *     BFS-chosen-path scheme that would arbitrarily pick one.
     *   - **Births are essentially free.** Conception adds the newborn (and, in the
     *     rare case where N has ancestors with no other living descendants, that chain
     *     too). For drockets-style births where both parents are themselves living,
     *     the visible-set gain is just `{N}`.
     *   - **Death prunes branches that lose their last living.** Any ancestor of D
     *     whose only living descendant was D drops out.
     *
     * Cost: per node V, a count of "livings descended from V (incl. V itself if
     * living)". Per birth/death is O(D × fan-in) — walk N's ancestor DAG, update
     * counts. No per-pair state, so no L factor in the update cost.
     */
    LIVING_ANCESTRY,
}

fun computeVisibleLineageIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
    filterMode: CladogramFilterMode,
): Set<Long> {
    val allIds = layout.depthById.keys
    return when (filterMode) {
        CladogramFilterMode.LIVING_ANCESTRY -> computeLivingAncestryVisibleIds(lineage, layout)
        CladogramFilterMode.ALL -> allIds.toSet()
        CladogramFilterMode.LIVING_ONLY -> allIds.filterTo(LinkedHashSet()) { lineage.livingLineageIds.contains(it) }
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
 * Full ancestry of every living individual. See [CladogramFilterMode.LIVING_ANCESTRY]
 * for the user-facing description.
 *
 * Implementation: BFS-up from every living, accumulating every visited node. A node
 * appears in the result iff it's living or some living is reachable via parent edges
 * starting at it (i.e., the node is an ancestor in the DAG sense).
 */
private fun computeLivingAncestryVisibleIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
): Set<Long> {
    val visibleIds = layout.depthById.keys
    if (visibleIds.isEmpty()) return emptySet()
    val living = lineage.livingLineageIds.filter { it in visibleIds }
    if (living.isEmpty()) return emptySet()

    val out = LinkedHashSet<Long>()
    val queue = ArrayDeque<Long>()
    for (l in living) {
        if (out.add(l)) queue.addLast(l)
    }
    while (queue.isNotEmpty()) {
        val v = queue.removeFirst()
        val node = lineage.nodes[v] ?: continue
        val m = node.motherLineageId
        val f = node.fatherLineageId
        if (m != null && m in visibleIds && out.add(m)) queue.addLast(m)
        if (f != null && f in visibleIds && out.add(f)) queue.addLast(f)
    }
    return out
}

/**
 * Incremental cache for [CladogramFilterMode.LIVING_ANCESTRY].
 *
 * Maintains a per-node count `lDC[V]` = #livings whose ancestor chain reaches V
 * (counting V itself if V is currently living). A node is visible iff `lDC[V] > 0`.
 *
 * Updates are local to the affected node's ancestor DAG:
 *
 *   Birth of N:
 *     BFS-up from N; `lDC[V]++` for every visited ancestor. Visibility flips
 *     to "visible" for any V whose count transitioned 0 → 1 — an ancestor that
 *     previously had no living descendants. For drockets-style births where both
 *     parents are themselves living, every ancestor above N already has at least
 *     one living descendant (the parent), so the visible-set gain is just `{N}`.
 *
 *   Death of D:
 *     BFS-up from D; `lDC[V]--` for every visited ancestor. Visibility flips to
 *     "invisible" for any V whose count transitioned 1 → 0 — the "branch propped
 *     up by D" case where D was its only living descendant.
 *
 * Per-event cost: O(|ancestor DAG of N| × fan-in), where fan-in ≤ 2 for drockets.
 * No L (livings count) factor, unlike the prior pair-wise MRCA cache that iterated
 * every existing living on each birth and was the source of the per-frame stalls
 * under active reproduction.
 *
 * On snapshot load (the lineage replaces ids the cache thought existed), the cache
 * detects via [parents] not matching [DrocketLineageState.nodes] and resets to a
 * full rebuild from scratch.
 */
internal class LivingAncestryCache {
    /** Adjacency from the visible-restricted lineage edges (child → parents). Grows
     *  monotonically as new visible nodes appear. */
    private val parents = HashMap<Long, List<Long>>()
    /** lDC[V] = number of currently-living individuals descended from V (V itself
     *  counted if V is living). Nodes whose count hits 0 are removed for memory
     *  hygiene — they re-enter on the next birth that reaches them. */
    private val livingDescCount = HashMap<Long, Int>()
    /** Last-known living set; diffed against the current frame to derive births/deaths. */
    private val livingMembers = LinkedHashSet<Long>()
    /** Materialised visible set, kept in sync with [livingDescCount] on every delta
     *  so [visibleFor] is O(1) after [ensureCurrent] returns. */
    private val visibleSet = LinkedHashSet<Long>()

    /** Returns the current visible set, applying any deltas since the last call. */
    fun visibleFor(lineage: DrocketLineageState, layout: CladogramLayout): Set<Long> {
        ensureCurrent(lineage, layout)
        return visibleSet
    }

    /** Resets the cache to empty. Called automatically on stale-cache detection
     *  (e.g. snapshot load); also useful in tests. */
    fun reset() {
        parents.clear()
        livingDescCount.clear()
        livingMembers.clear()
        visibleSet.clear()
    }

    private fun ensureCurrent(lineage: DrocketLineageState, layout: CladogramLayout) {
        // Stale detection: any cached id missing from the current lineage means the
        // lineage went backwards (snapshot reload, typically). Full rebuild.
        if (parents.keys.any { it !in lineage.nodes }) reset()

        val visibleIds = layout.depthById.keys

        // Catch up parents adjacency for any newly-visible nodes. Births never mutate
        // existing entries, so this is purely additive.
        for ((id, node) in lineage.nodes) {
            if (id !in visibleIds || id in parents) continue
            val ps = buildList<Long>(2) {
                node.motherLineageId?.let { if (it in visibleIds) add(it) }
                node.fatherLineageId?.let { if (it in visibleIds) add(it) }
            }
            parents[id] = ps
        }

        val currentLiving = lineage.livingLineageIds.filterTo(LinkedHashSet()) { it in visibleIds }
        val deaths = livingMembers.filter { it !in currentLiving }
        val births = currentLiving.filter { it !in livingMembers }

        // Process deaths first so the lDC bookkeeping is consistent if a birth and
        // death involve overlapping ancestors in the same tick.
        for (dead in deaths) processDeath(dead)
        for (newLife in births) processBirth(newLife)
    }

    private fun processBirth(n: Long) {
        livingMembers.add(n)
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        seen.add(n)
        queue.addLast(n)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val before = livingDescCount[v] ?: 0
            livingDescCount[v] = before + 1
            if (before == 0) visibleSet.add(v)
            for (p in parents[v] ?: emptyList()) {
                if (seen.add(p)) queue.addLast(p)
            }
        }
    }

    private fun processDeath(d: Long) {
        livingMembers.remove(d)
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        seen.add(d)
        queue.addLast(d)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val before = livingDescCount[v] ?: 0
            val after = before - 1
            if (after <= 0) {
                livingDescCount.remove(v)
                visibleSet.remove(v)
            } else {
                livingDescCount[v] = after
            }
            for (p in parents[v] ?: emptyList()) {
                if (seen.add(p)) queue.addLast(p)
            }
        }
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
 * Used by the shift+click pair-wise MRCA highlight in [LineageOverlay], which is
 * an interactive feature distinct from the [CladogramFilterMode.LIVING_ANCESTRY]
 * visibility computation.
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
