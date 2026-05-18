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
     * [LIVING_STEINER] for an exact connecting subgraph or [LIVING_ANCESTRY] for full
     * ancestor chains.
     */
    LIVING_AND_CONNECTORS,

    /**
     * Single-LUCA Steiner subgraph: like [LIVING_STEINER] but when multiple LUCAs
     * coexist (typical in a sexually-reproducing DAG with multiple founder mates),
     * picks just one — the LUCA whose descendant subgraph has the fewest nodes,
     * with ties broken by smallest lineage id. Shows only that LUCA's descendants.
     *
     * For visualisation, this is the "narrowest" view: a single anchor at the top,
     * a tight tree below. Edges from descendants whose other parent lives in a
     * non-chosen LUCA's chain get clipped (dangling), which is the trade-off for
     * the visual simplification.
     */
    LIVING_FOCUSED,

    /**
     * Steiner subgraph: the minimal subgraph connecting every living individual, with
     * LUCA (the deepest universal common ancestor) sitting at the top. Nodes above LUCA
     * and parallel/redundant DAG branches are excluded.
     *
     * Definition: V is visible iff V is living OR V is reachable from a LUCA by walking
     * down the child edges through nodes with at least one living descendant. A LUCA is
     * a node V with `lDC[V] = T` (universal) AND no child of V also has `lDC = T` (no
     * deeper universal ancestor exists).
     *
     * For deep trees where most of the history sits above LUCA — the founder lineages
     * that everyone descends from but that don't contribute to differentiation — this is
     * a dramatic noise reduction over [LIVING_ANCESTRY].
     */
    LIVING_STEINER,

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
        CladogramFilterMode.LIVING_STEINER -> computeLivingSteinerVisibleIds(lineage, layout)
        CladogramFilterMode.LIVING_FOCUSED -> computeLivingFocusedVisibleIds(lineage, layout)
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
 * Steiner subgraph of every living. See [CladogramFilterMode.LIVING_STEINER] for
 * the user-facing description.
 *
 * Implementation:
 *   1. Walk up from every living to derive lDC[V] (livings descended, counting V
 *      itself if living) and child adjacency restricted to the visible set.
 *   2. Find LUCAs: nodes with `lDC[V] = T` whose children all have `lDC < T`. In a
 *      DAG with sexual reproduction this is usually a single node, but multiple
 *      LUCAs can coexist if the visible lineage is disconnected — each component
 *      contributes its own.
 *   3. Forward-BFS from each LUCA through child edges, including only children with
 *      `lDC > 0`. The visited set is the Steiner subgraph.
 *
 * Fallback: if no LUCA exists (no node has `lDC = T`), the visible lineage is
 * disconnected with no single universal ancestor of all livings. In that case we
 * fall back to full ancestry rather than returning an empty set.
 */
private fun computeLivingSteinerVisibleIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
): Set<Long> {
    val visibleIds = layout.depthById.keys
    if (visibleIds.isEmpty()) return emptySet()
    val livingList = lineage.livingLineageIds.filter { it in visibleIds }
    if (livingList.isEmpty()) return emptySet()
    val livingSet = livingList.toHashSet()
    val t = livingList.size

    // BFS up from every living to derive lDC and a visible-restricted children map.
    val ancestors = LinkedHashSet<Long>()
    val lDC = HashMap<Long, Int>()
    val children = HashMap<Long, MutableList<Long>>()
    for (l in livingList) {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        seen.add(l)
        queue.addLast(l)
        ancestors.add(l)
        lDC[l] = (lDC[l] ?: 0) + 1
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val node = lineage.nodes[v] ?: continue
            for (p in listOfNotNull(node.motherLineageId, node.fatherLineageId)) {
                if (p !in visibleIds) continue
                children.getOrPut(p) { mutableListOf() }.let {
                    if (v !in it) it.add(v)
                }
                if (seen.add(p)) {
                    ancestors.add(p)
                    lDC[p] = (lDC[p] ?: 0) + 1
                    queue.addLast(p)
                }
            }
        }
    }

    // Find LUCAs: at-max nodes with no at-max children.
    val lucas = ArrayList<Long>()
    for (v in ancestors) {
        if ((lDC[v] ?: 0) != t) continue
        val kids = children[v]
        val hasAtMaxChild = kids?.any { (lDC[it] ?: 0) == t } ?: false
        if (!hasAtMaxChild) lucas.add(v)
    }
    if (lucas.isEmpty()) {
        // Disconnected lineage with no universal ancestor — fall back to full ancestry.
        return ancestors
    }

    // Forward-BFS from LUCAs, walking child edges through nodes with lDC > 0.
    val out = LinkedHashSet<Long>()
    val queue = ArrayDeque<Long>()
    for (l in lucas) {
        if (out.add(l)) queue.addLast(l)
    }
    while (queue.isNotEmpty()) {
        val v = queue.removeFirst()
        for (c in children[v] ?: emptyList()) {
            if ((lDC[c] ?: 0) <= 0 && c !in livingSet) continue
            if (out.add(c)) queue.addLast(c)
        }
    }
    return out
}

/**
 * Single-LUCA focused Steiner subgraph. See [CladogramFilterMode.LIVING_FOCUSED]
 * for the user-facing description.
 *
 * Implementation: same setup as [computeLivingSteinerVisibleIds] (BFS-up to derive
 * lDC + children adjacency, find LUCAs). With multiple LUCAs, compute each LUCA's
 * descendant subgraph and pick the one with the fewest nodes; ties broken by
 * smallest lineage id for determinism. With zero or one LUCAs, behave like Steiner.
 */
private fun computeLivingFocusedVisibleIds(
    lineage: DrocketLineageState,
    layout: CladogramLayout,
): Set<Long> {
    val visibleIds = layout.depthById.keys
    if (visibleIds.isEmpty()) return emptySet()
    val livingList = lineage.livingLineageIds.filter { it in visibleIds }
    if (livingList.isEmpty()) return emptySet()
    val livingSet = livingList.toHashSet()
    val t = livingList.size

    val ancestors = LinkedHashSet<Long>()
    val lDC = HashMap<Long, Int>()
    val children = HashMap<Long, MutableList<Long>>()
    for (l in livingList) {
        val seen = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        seen.add(l)
        queue.addLast(l)
        ancestors.add(l)
        lDC[l] = (lDC[l] ?: 0) + 1
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val node = lineage.nodes[v] ?: continue
            for (p in listOfNotNull(node.motherLineageId, node.fatherLineageId)) {
                if (p !in visibleIds) continue
                children.getOrPut(p) { mutableListOf() }.let {
                    if (v !in it) it.add(v)
                }
                if (seen.add(p)) {
                    ancestors.add(p)
                    lDC[p] = (lDC[p] ?: 0) + 1
                    queue.addLast(p)
                }
            }
        }
    }

    val lucas = ArrayList<Long>()
    for (v in ancestors) {
        if ((lDC[v] ?: 0) != t) continue
        val kids = children[v]
        val hasAtMaxChild = kids?.any { (lDC[it] ?: 0) == t } ?: false
        if (!hasAtMaxChild) lucas.add(v)
    }
    if (lucas.isEmpty()) return ancestors

    return pickSmallestLucaDescendants(lucas, children, lDC, livingSet)
}

/**
 * For each LUCA, BFS down its descendants (restricted to nodes with lDC > 0 or
 * living) and pick the LUCA with the smallest resulting set. Tie-break by smallest
 * LUCA id. Returns the chosen LUCA's descendant set.
 */
private fun pickSmallestLucaDescendants(
    lucas: List<Long>,
    children: Map<Long, List<Long>>,
    lDC: Map<Long, Int>,
    livingSet: Set<Long>,
): Set<Long> {
    var bestLuca: Long = lucas[0]
    var bestSet: LinkedHashSet<Long> = bfsDescendantsFrom(bestLuca, children, lDC, livingSet)
    for (i in 1 until lucas.size) {
        val candidate = lucas[i]
        val candidateSet = bfsDescendantsFrom(candidate, children, lDC, livingSet)
        val takeCandidate = candidateSet.size < bestSet.size ||
            (candidateSet.size == bestSet.size && candidate < bestLuca)
        if (takeCandidate) {
            bestLuca = candidate
            bestSet = candidateSet
        }
    }
    return bestSet
}

private fun bfsDescendantsFrom(
    luca: Long,
    children: Map<Long, List<Long>>,
    lDC: Map<Long, Int>,
    livingSet: Set<Long>,
): LinkedHashSet<Long> {
    val out = LinkedHashSet<Long>()
    val queue = ArrayDeque<Long>()
    out.add(luca)
    queue.addLast(luca)
    while (queue.isNotEmpty()) {
        val v = queue.removeFirst()
        for (c in children[v] ?: emptyList()) {
            if ((lDC[c] ?: 0) <= 0 && c !in livingSet) continue
            if (out.add(c)) queue.addLast(c)
        }
    }
    return out
}

/**
 * Incremental cache for [CladogramFilterMode.LIVING_ANCESTRY] AND
 * [CladogramFilterMode.LIVING_STEINER] AND [CladogramFilterMode.LIVING_FOCUSED].
 * All three filters share the same underlying per-node state — they differ only
 * in how the visible set is derived from it.
 *
 * State maintained:
 *
 *   - `parents` + `children`: visible-restricted adjacency in both directions. The
 *     children map is needed for forward-BFS from LUCAs in the Steiner computation.
 *   - `lDC[V]`: number of currently-living individuals descended from V (V itself
 *     counted if V is living).
 *   - `universalChildCount[V]`: how many of V's children currently satisfy
 *     `lDC = T`. V is a LUCA iff V is itself at `lDC = T` AND
 *     `universalChildCount[V] == 0` (no deeper universal ancestor exists below V).
 *   - `nodesByLDC[c]`: reverse index of `lDC` so we can find non-ancestor nodes
 *     whose at-max status flips when T changes.
 *   - `livingMembers`: last-known living set, diffed each frame.
 *   - `ancestryVisibleSet`: materialised full-ancestry result (= every node with
 *     `lDC > 0`), kept in sync with the underlying counts.
 *
 * The Steiner visible set is NOT materialised incrementally — instead it's computed
 * on each call to [steinerVisibleFor] by forward-BFS from the LUCAs. With LineageOverlay's
 * outer cache (per `versionStamp + filter`), this happens at most once per relevant
 * change. The compute itself is bounded by the size of the Steiner subgraph, which
 * is much smaller than the full lineage for a typical drockets sim.
 *
 * Updates are local to the affected node's ancestor DAG:
 *
 *   Birth of N:
 *     BFS-up from N; `lDC[V]++` for every visited ancestor. Visibility flips
 *     to "visible (ancestry)" for any V whose count transitioned 0 → 1. Each
 *     visited V that flipped INTO `lDC = T` bumps its parents' universalChildCount.
 *     Each non-ancestor V with pre-birth `lDC = T_old` flips OUT of at-max (because
 *     T grew), and its parents' universalChildCount decrements.
 *
 *   Death of D:
 *     BFS-up from D; `lDC[V]--` for every visited ancestor. 1 → 0 transitions
 *     remove V from ancestry visible. Each visited V that flipped OUT of `lDC = T`
 *     decrements its parents' universalChildCount. Each non-ancestor V with
 *     pre-death `lDC = T_new` flips INTO at-max (T shrank), bumping its parents'
 *     universalChildCount.
 *
 * Per-event cost: O(|ancestor DAG of N| × fan-in) plus O(|nodesByLDC[critical T]|)
 * for the universality flip check — bounded by the size of the "trunk above LUCA"
 * which is small in practice.
 *
 * On snapshot load (the lineage replaces ids the cache thought existed), the cache
 * detects via [parents] not matching [DrocketLineageState.nodes] and resets to a
 * full rebuild from scratch.
 */
internal class LivingAncestryCache {
    private val parents = HashMap<Long, List<Long>>()
    private val children = HashMap<Long, MutableList<Long>>()
    private val livingDescCount = HashMap<Long, Int>()
    private val universalChildCount = HashMap<Long, Int>()
    private val nodesByLDC = HashMap<Int, MutableSet<Long>>()
    private val livingMembers = LinkedHashSet<Long>()
    private val ancestryVisibleSet = LinkedHashSet<Long>()

    // Watermarks used to (a) detect snapshot replacement cheaply and (b) iterate
    // only newly-born ids in `ensureCurrent`, so per-tick discovery cost scales
    // with births rather than total ever-born node count.
    private var lastNextLineageId: Long = 0L
    private var lastNodeCount: Int = 0

    /** Returns the full-ancestry visible set (every ancestor of every living). */
    fun ancestryVisibleFor(lineage: DrocketLineageState, layout: CladogramLayout): Set<Long> {
        ensureCurrent(lineage, layout)
        return ancestryVisibleSet
    }

    /** Returns the Steiner subgraph visible set (LUCA at top, no trunk above, no
     *  parallel-only DAG branches). Computed on each call from the maintained
     *  per-node state; expected to be invoked once per LineageOverlay cache miss. */
    fun steinerVisibleFor(lineage: DrocketLineageState, layout: CladogramLayout): Set<Long> {
        ensureCurrent(lineage, layout)
        return computeSteinerFromMaintainedState()
    }

    /** Returns the single-LUCA focused Steiner subgraph. When multiple LUCAs exist,
     *  picks the one with the smallest descendant subgraph (ties: smallest id). */
    fun lucaFocusedVisibleFor(lineage: DrocketLineageState, layout: CladogramLayout): Set<Long> {
        ensureCurrent(lineage, layout)
        return computeLucaFocusedFromMaintainedState()
    }

    /** Resets the cache to empty. Called automatically on stale-cache detection
     *  (e.g. snapshot load); also useful in tests. */
    fun reset() {
        parents.clear()
        children.clear()
        livingDescCount.clear()
        universalChildCount.clear()
        nodesByLDC.clear()
        livingMembers.clear()
        ancestryVisibleSet.clear()
        lastNextLineageId = 0L
        lastNodeCount = 0
    }

    private fun ensureCurrent(lineage: DrocketLineageState, layout: CladogramLayout) {
        // Detect wholesale replacement (snapshot load). `nextLineageId` only ever
        // increases under normal play and `nodes` only ever grows (deaths flip
        // `deathTick` but keep the node in the map). A regression on either is a
        // signal that the lineage was replaced, not advanced.
        if (lineage.nextLineageId < lastNextLineageId || lineage.nodes.size < lastNodeCount) {
            reset()
        }

        val visibleIds = layout.depthById.keys

        // Discover only ids born since the last sync. In steady state this loop
        // iterates a handful of new births per tick, not the full lineage.
        for (id in lastNextLineageId until lineage.nextLineageId) {
            val node = lineage.nodes[id] ?: continue
            if (id !in visibleIds || id in parents) continue
            val ps = buildList<Long>(2) {
                node.motherLineageId?.let { if (it in visibleIds) add(it) }
                node.fatherLineageId?.let { if (it in visibleIds) add(it) }
            }
            parents[id] = ps
            for (p in ps) {
                children.getOrPut(p) { mutableListOf() }.add(id)
            }
        }
        lastNextLineageId = lineage.nextLineageId
        lastNodeCount = lineage.nodes.size

        val currentLiving = lineage.livingLineageIds.filterTo(LinkedHashSet()) { it in visibleIds }
        val deaths = livingMembers.filter { it !in currentLiving }
        val births = currentLiving.filter { it !in livingMembers }

        // Process deaths first so the lDC bookkeeping is consistent if a birth and
        // death involve overlapping ancestors in the same tick.
        for (dead in deaths) processDeath(dead)
        for (newLife in births) processBirth(newLife)
    }

    private fun processBirth(n: Long) {
        val tOld = livingMembers.size
        livingMembers.add(n)
        val tNew = livingMembers.size

        // Pre-BFS snapshot of nodes at `lDC = tOld` — these flip OUT of at-max when T
        // grows, unless they're ancestors of N (then they stay at-max under tNew).
        val flipOutCandidates = nodesByLDC[tOld]?.toList() ?: emptyList()

        val visited = HashSet<Long>()
        val visitedFlippedToUniversal = mutableListOf<Long>()
        val queue = ArrayDeque<Long>()
        visited.add(n)
        queue.addLast(n)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val cOld = livingDescCount[v] ?: 0
            val cNew = cOld + 1
            val wasAtMax = cOld >= 1 && cOld == tOld
            val isAtMax = cNew == tNew
            updateLDC(v, cNew)
            if (cOld == 0) ancestryVisibleSet.add(v)
            if (!wasAtMax && isAtMax) visitedFlippedToUniversal.add(v)
            for (p in parents[v] ?: emptyList()) {
                if (visited.add(p)) queue.addLast(p)
            }
        }

        // Visited flips IN to at-max (first-birth edge case where tOld = 0).
        for (v in visitedFlippedToUniversal) {
            for (p in parents[v] ?: emptyList()) {
                universalChildCount[p] = (universalChildCount[p] ?: 0) + 1
            }
        }
        // Non-ancestors with pre-birth lDC = tOld flip OUT (T grew past their count).
        for (v in flipOutCandidates) {
            if (v in visited) continue
            for (p in parents[v] ?: emptyList()) {
                val before = universalChildCount[p] ?: 0
                if (before <= 1) universalChildCount.remove(p) else universalChildCount[p] = before - 1
            }
        }
    }

    private fun processDeath(d: Long) {
        val tOld = livingMembers.size
        livingMembers.remove(d)
        val tNew = livingMembers.size

        // Pre-BFS snapshot of nodes at `lDC = tNew` (= tOld - 1) — these flip INTO
        // at-max when T shrinks past their count.
        val flipInCandidates = nodesByLDC[tNew]?.toList() ?: emptyList()

        val visited = HashSet<Long>()
        val visitedFlippedFromUniversal = mutableListOf<Long>()
        val queue = ArrayDeque<Long>()
        visited.add(d)
        queue.addLast(d)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val cOld = livingDescCount[v] ?: 0
            val cNew = cOld - 1
            val wasAtMax = cOld >= 1 && cOld == tOld
            val isAtMax = cNew >= 1 && cNew == tNew
            updateLDC(v, cNew)
            if (cNew <= 0) ancestryVisibleSet.remove(v)
            if (wasAtMax && !isAtMax) visitedFlippedFromUniversal.add(v)
            for (p in parents[v] ?: emptyList()) {
                if (visited.add(p)) queue.addLast(p)
            }
        }

        // Visited flips OUT of at-max (last-death edge case where tNew = 0).
        for (v in visitedFlippedFromUniversal) {
            for (p in parents[v] ?: emptyList()) {
                val before = universalChildCount[p] ?: 0
                if (before <= 1) universalChildCount.remove(p) else universalChildCount[p] = before - 1
            }
        }
        // Non-ancestors with pre-death lDC = tNew flip INTO at-max (T shrank to their count).
        for (v in flipInCandidates) {
            if (v in visited) continue
            for (p in parents[v] ?: emptyList()) {
                universalChildCount[p] = (universalChildCount[p] ?: 0) + 1
            }
        }
    }

    /** Move V between [nodesByLDC] buckets and update [livingDescCount]. */
    private fun updateLDC(v: Long, newValue: Int) {
        val old = livingDescCount[v] ?: 0
        nodesByLDC[old]?.let { bucket ->
            bucket.remove(v)
            if (bucket.isEmpty()) nodesByLDC.remove(old)
        }
        if (newValue <= 0) {
            livingDescCount.remove(v)
        } else {
            livingDescCount[v] = newValue
            nodesByLDC.getOrPut(newValue) { mutableSetOf() }.add(v)
        }
    }

    /** Compute the Steiner visible set from `lDC` + `universalChildCount`. */
    private fun computeSteinerFromMaintainedState(): Set<Long> {
        val lucas = findLucasOrNull() ?: return ancestryVisibleSet
        if (lucas.isEmpty()) return ancestryVisibleSet

        val out = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        for (l in lucas) {
            if (out.add(l)) queue.addLast(l)
        }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (c in children[v] ?: emptyList()) {
                if ((livingDescCount[c] ?: 0) <= 0 && c !in livingMembers) continue
                if (out.add(c)) queue.addLast(c)
            }
        }
        return out
    }

    /** Compute the LUCA-focused visible set: pick the single LUCA whose descendant
     *  subgraph is smallest (ties: smallest id), return its descendants. */
    private fun computeLucaFocusedFromMaintainedState(): Set<Long> {
        val lucas = findLucasOrNull() ?: return ancestryVisibleSet
        if (lucas.isEmpty()) return ancestryVisibleSet

        var bestLuca = lucas[0]
        var bestSet = descendantsFromMaintained(bestLuca)
        for (i in 1 until lucas.size) {
            val candidate = lucas[i]
            val candidateSet = descendantsFromMaintained(candidate)
            val takeCandidate = candidateSet.size < bestSet.size ||
                (candidateSet.size == bestSet.size && candidate < bestLuca)
            if (takeCandidate) {
                bestLuca = candidate
                bestSet = candidateSet
            }
        }
        return bestSet
    }

    /** LUCAs from the maintained state, or null if T = 0 or no node has `lDC = T`
     *  (fallback condition for callers — they typically degrade to full ancestry). */
    private fun findLucasOrNull(): List<Long>? {
        val t = livingMembers.size
        if (t == 0) return null
        val atMax = nodesByLDC[t] ?: return null
        val lucas = ArrayList<Long>()
        for (v in atMax) {
            if ((universalChildCount[v] ?: 0) == 0) lucas.add(v)
        }
        return lucas
    }

    /** Forward-BFS from a single LUCA via maintained children adjacency. */
    private fun descendantsFromMaintained(luca: Long): LinkedHashSet<Long> {
        val out = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        out.add(luca)
        queue.addLast(luca)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (c in children[v] ?: emptyList()) {
                if ((livingDescCount[c] ?: 0) <= 0 && c !in livingMembers) continue
                if (out.add(c)) queue.addLast(c)
            }
        }
        return out
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
