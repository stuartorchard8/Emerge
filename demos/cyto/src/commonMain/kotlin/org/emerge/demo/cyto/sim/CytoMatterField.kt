package org.emerge.demo.cyto.sim

import kotlin.math.floor

/**
 * The environment **matter field** as an adaptive quad-tree (replaces the flat `CytoMatterGrid`). Fine
 * (0.25 cell-diam) only where observed; collapses toward coarse in the void; cells exchange with it as
 * diffusion junctions. **Authoritative design: `QUADTREE.md`.**
 *
 * Geometry: a `BASE_RES²` torus-mod-indexed grid of tiles (the wrap lives only here), each an adaptive
 * quad-tree of depth ≤ [MAX_DEPTH]. A [QuadNode] is a leaf (`store` + `lastAccessTick`) or internal
 * (`children` + a `monomerRemainder` stash). All matter ops are **exact integer** (conservation) in a
 * **fixed traversal order** (determinism); only the *geometry* (which leaves a disc covers) uses Float —
 * same-platform deterministic, matching the existing light-field stance (not cross-platform lockstep).
 *
 * v1 allocates nodes/stores directly (no pool yet) — correctness first; object pooling is the noted
 * follow-up for split/merge churn.
 */
class CytoMatterField private constructor(private val roots: Array<QuadNode>) {

    // ── build ────────────────────────────────────────────────────────────────────────────────────────
    companion object {
        const val BASE_RES = 2
        const val MAX_DEPTH = 9
        val SPAN = CytoLightField.SPAN          // logical torus extent (256 cell-diam)
        val HALF = CytoLightField.HALF          // 128
        val TILE = SPAN / BASE_RES              // 128 cell-diam per tile
        /** Max disc half-extent (cell-diam) — bounds a giant cell's footprint. */
        const val MAX_DISC_RADIUS = 4f

        val A = SpeciesRegistry.id("a"); val B = SpeciesRegistry.id("b"); val C = SpeciesRegistry.id("c")
        private val MONO = intArrayOf(A, B, C)
        private fun monoSlot(id: Int): Int = when (id) { A -> 0; B -> 1; C -> 2; else -> -1 }

        fun empty(): CytoMatterField = CytoMatterField(Array(BASE_RES * BASE_RES) { QuadNode.leaf() })

        /** Uniform larder: every tile a leaf holding `level` of each monomer over its whole area. A tile is
         *  TILE×TILE cell-diam = (TILE/0.25)² finest cells, so a fully-merged tile holds `level · cells²`. */
        fun seededUniform(level: Int): CytoMatterField {
            val finest = 1 shl MAX_DEPTH                 // finest cells along a tile axis (512)
            val cellsPerTile = finest * finest           // a merged tile pools this many finest cells' worth
            return CytoMatterField(Array(BASE_RES * BASE_RES) {
                QuadNode.leaf().also { n -> for (m in MONO) n.store!!.add(m, level * cellsPerTile) }
            })
        }
    }

    // ── totals / iteration (digest, conservation, save) ────────────────────────────────────────────────
    fun totalAtoms(): Long {
        var sum = 0L
        forEachLeaf { _, _, _, store -> for (i in 0 until store.size) sum += SpeciesRegistry.atomCount(store.idAt(i)).toLong() * store.countAt(i) }
        // stashed monomers in internal nodes count too (each is 1 atom)
        forEachInternal { node -> for (s in 0..2) sum += node.monomerRemainder[s].toLong() }
        return sum
    }

    /** Visit every leaf with its region (origin x,y + size) and store — stable order (tiles, then DFS NW→SE).
     *  Used for digest/save/conservation; coordinates are the leaf's lower-left corner. */
    fun forEachLeaf(visit: (x: Float, y: Float, size: Float, store: MoleculeStore) -> Unit) {
        for (gy in 0 until BASE_RES) for (gx in 0 until BASE_RES) {
            val ox = -HALF + gx * TILE; val oy = -HALF + gy * TILE
            leafWalk(roots[gy * BASE_RES + gx], ox, oy, TILE, visit)
        }
    }
    fun leafWalk(node: QuadNode, x: Float, y: Float, sz: Float, visit: (Float, Float, Float, MoleculeStore) -> Unit) {
        if (node.isLeaf) { visit(x, y, sz, node.store!!); return }
        val h = sz * 0.5f; val ch = node.children!!
        leafWalk(ch[0], x, y, h, visit); leafWalk(ch[1], x + h, y, h, visit)
        leafWalk(ch[2], x, y + h, h, visit); leafWalk(ch[3], x + h, y + h, h, visit)
    }
    private fun forEachInternal(visit: (QuadNode) -> Unit) {
        for (r in roots) internalWalk(r, visit)
    }
    private fun internalWalk(node: QuadNode, visit: (QuadNode) -> Unit) {
        if (node.isLeaf) return
        visit(node); for (c in node.children!!) internalWalk(c, visit)
    }

    // ── refine / pool (QUADTREE.md: splitLeaf / mergeNode) ─────────────────────────────────────────────
    /** Leaf → internal + 4 child leaves. Complex-species remainders atomise; monomer remainders (≤3 each)
     *  stash on the node; no remainder ever favours a child ⇒ no spatial bias. Conservation-exact. */
    private fun splitLeaf(node: QuadNode) {
        val store = node.store!!
        val children = Array(4) { QuadNode.leaf().also { it.lastAccessTick = node.lastAccessTick } }
        // monomer tallies start from the leaf's monomers; complex remainders atomise INTO these first.
        val mono = IntArray(3)
        for (s in 0..2) mono[s] = store.count(MONO[s])
        for (i in 0 until store.size) {
            val id = store.idAt(i); val c = store.countAt(i)
            if (SpeciesRegistry.atomCount(id) < 2) continue          // monomers handled below
            val q = c / 4; val r = c % 4
            if (q > 0) for (ch in children) ch.store!!.add(id, q)
            if (r > 0) atomise(id, r, mono)                          // remainder → monomers (rapid decay)
        }
        // now split the (augmented) monomer tallies; stash the ≤3 remainder each
        for (s in 0..2) {
            val c = mono[s]; val q = c / 4; val r = c % 4
            if (q > 0) for (ch in children) ch.store!!.add(MONO[s], q)
            node.monomerRemainder[s] = r
        }
        node.becomeInternal(children)
    }

    /** Add the monomers of `count` copies of molecule `id` into the `mono[3]` tally (decompose fully). */
    private fun atomise(id: Int, count: Int, mono: IntArray) {
        var rest = id
        while (SpeciesRegistry.atomCount(rest) >= 2) {
            val lead = SpeciesRegistry.splitLeftMono(rest); val slot = monoSlot(lead)
            if (slot >= 0) mono[slot] += count
            rest = SpeciesRegistry.splitLeftRest(rest)
        }
        val slot = monoSlot(rest); if (slot >= 0) mono[slot] += count
    }

    /** Internal node whose 4 children are ALL leaves → leaf. Sums children + releases the stash; merged leaf
     *  takes `currentTick` (→ progressive collapse). Conservation-exact. Precondition checked by caller. */
    private fun mergeNode(node: QuadNode, currentTick: Int) {
        val merged = MoleculeStore()
        for (ch in node.children!!) { val s = ch.store!!; for (i in 0 until s.size) merged.add(s.idAt(i), s.countAt(i)) }
        for (s in 0..2) if (node.monomerRemainder[s] > 0) merged.add(MONO[s], node.monomerRemainder[s])
        node.becomeLeaf(merged, currentTick)
    }

    // ── access traversal (descendDisc) ─────────────────────────────────────────────────────────────────
    /** Visit the finest leaves whose centre is within `r` of (cx,cy), splitting on the way + stamping access.
     *  Float geometry (same-platform deterministic). */
    fun descendDisc(cx: Float, cy: Float, rRaw: Float, tick: Int, visit: (QuadNode) -> Unit) {
        val r = if (rRaw > MAX_DISC_RADIUS) MAX_DISC_RADIUS else rRaw
        val gxMin = floor((cx - r + HALF) / TILE).toInt(); val gxMax = floor((cx + r + HALF) / TILE).toInt()
        val gyMin = floor((cy - r + HALF) / TILE).toInt(); val gyMax = floor((cy + r + HALF) / TILE).toInt()
        for (gyRaw in gyMin..gyMax) for (gxRaw in gxMin..gxMax) {
            val ox = -HALF + gxRaw * TILE; val oy = -HALF + gyRaw * TILE      // UNWRAPPED tile origin
            val root = roots[mod(gyRaw) * BASE_RES + mod(gxRaw)]
            descendNode(root, ox, oy, TILE, 0, cx, cy, r, tick, visit)
        }
    }
    fun descendNode(node: QuadNode, x: Float, y: Float, sz: Float, depth: Int,
                    cx: Float, cy: Float, r: Float, tick: Int, visit: (QuadNode) -> Unit) {
        // box–circle prune
        val dx = maxOf(x - cx, cx - (x + sz), 0f); val dy = maxOf(y - cy, cy - (y + sz), 0f)
        if (dx * dx + dy * dy > r * r) return
        if (depth == MAX_DEPTH) {
            val ccx = x + sz * 0.5f; val ccy = y + sz * 0.5f
            val ex = ccx - cx; val ey = ccy - cy
            if (ex * ex + ey * ey <= r * r) { node.lastAccessTick = tick; visit(node) }
            return
        }
        if (node.isLeaf) splitLeaf(node)
        val h = sz * 0.5f; val ch = node.children!!
        descendNode(ch[0], x, y, h, depth + 1, cx, cy, r, tick, visit)
        descendNode(ch[1], x + h, y, h, depth + 1, cx, cy, r, tick, visit)
        descendNode(ch[2], x, y + h, h, depth + 1, cx, cy, r, tick, visit)
        descendNode(ch[3], x + h, y + h, h, depth + 1, cx, cy, r, tick, visit)
    }

    private fun mod(i: Int): Int = ((i % BASE_RES) + BASE_RES) % BASE_RES

    // ── the diffusion junction (exchange) ──────────────────────────────────────────────────────────────
    /** Reusable scratch: the fine leaves of one cell's footprint, collected by [openFootprint]. */
    private val fpLeaves = ArrayList<QuadNode>()

    /** Open a cell's footprint: refine + stamp + collect its N fine leaves. Returns N (0 = nothing). Follow
     *  with [balance] per species, then [closeFootprint]. NOT re-entrant (single scratch) — exchange runs
     *  sequentially (id-order), never on the parallel gene path. */
    fun openFootprint(cx: Float, cy: Float, radius: Float, tick: Int): Int {
        fpLeaves.clear()
        descendDisc(cx, cy, radius, tick) { fpLeaves.add(it) }
        return fpLeaves.size
    }

    /** Collect the species present across the open footprint into [out] (deduped); returns count. */
    fun footprintSpecies(out: HashSet<Int>) {
        out.clear()
        for (leaf in fpLeaves) { val s = leaf.store!!; for (i in 0 until s.size) out.add(s.idAt(i)) }
    }

    /** Balance the open footprint's `sp` toward `cEff/N` (bidirectional diffusion), larger source keeping the
     *  odd unit. Returns the net Δ to apply to the cell's cytoplasm (grid changes by −Δ). Conservation-exact. */
    fun balance(sp: Int, cEff: Int): Int {
        val n = fpLeaves.size; if (n == 0) return 0
        val bucket = cEff / n                       // remainder kept in cell (untransacted)
        var returned = 0
        for (leaf in fpLeaves) {
            val store = leaf.store!!
            val e = store.count(sp); val total = e + bucket; val half = total / 2
            val eNew: Int; val bNew: Int
            if (e >= bucket) { eNew = total - half; bNew = half } else { eNew = half; bNew = total - half }
            val d = eNew - e
            if (d != 0) store.add(sp, d)             // exact integer move
            returned += bNew
        }
        return returned - bucket * n                 // Δ into the cell
    }

    fun closeFootprint() = fpLeaves.clear()

    // ── death / export deposit ─────────────────────────────────────────────────────────────────────────
    /** Add `amount` of `sp` spread across the cell's footprint (refine to fine first). Conservation-exact. */
    fun deposit(cx: Float, cy: Float, radius: Float, sp: Int, amount: Int, tick: Int) {
        if (amount <= 0) return
        val n = openFootprint(cx, cy, radius, tick)
        if (n == 0) { closeFootprint(); return }
        val per = amount / n; var extra = amount - per * n
        for (leaf in fpLeaves) { var a = per; if (extra > 0) { a++; extra-- }; if (a > 0) leaf.store!!.add(sp, a) }
        closeFootprint()
    }

    // ── maintenance: progressive collapse + species decay ──────────────────────────────────────────────
    fun maintain(currentTick: Int, collapseDelay: Int, decayPeriod: Int) {
        for (r in roots) maintainNode(r, currentTick, collapseDelay, decayPeriod)
    }
    /** Post-order: decay leaves; merge an all-leaf internal node whose children are all stale (→ progressive,
     *  since the merged leaf is fresh and blocks its parent for another delay). */
    private fun maintainNode(node: QuadNode, tick: Int, collapseDelay: Int, decayPeriod: Int) {
        if (node.isLeaf) { decayLeaf(node, decayPeriod); return }
        val ch = node.children!!
        for (c in ch) maintainNode(c, tick, collapseDelay, decayPeriod)
        var allLeafStale = true
        for (c in ch) if (!c.isLeaf || tick - c.lastAccessTick < collapseDelay) { allLeafStale = false; break }
        if (allLeafStale) mergeNode(node, tick)
    }
    /** Atomise ⌊count/period⌋ of each multi-atom species (peel leftmost bond). Conservation-exact; does NOT
     *  touch lastAccessTick. */
    private fun decayLeaf(node: QuadNode, period: Int) {
        if (period <= 0) return
        val store = node.store!!
        val sz = store.size; if (sz == 0) return
        // snapshot ids+counts first — adding fragments mutates the store mid-iteration otherwise (and we must
        // break each species' ⌊count/period⌋ exactly ONCE per call, not geometrically).
        val ids = IntArray(sz); val cnts = IntArray(sz)
        for (i in 0 until sz) { ids[i] = store.idAt(i); cnts[i] = store.countAt(i) }
        for (i in 0 until sz) {
            val id = ids[i]
            if (SpeciesRegistry.atomCount(id) < 2) continue
            val broken = cnts[i] / period; if (broken <= 0) continue
            store.add(id, -broken)
            store.add(SpeciesRegistry.splitLeftMono(id), broken)
            store.add(SpeciesRegistry.splitLeftRest(id), broken)
        }
    }
}

/** A quad-tree node: leaf (store != null, children == null) or internal (children != null). Mutated in place
 *  by split/merge so a node keeps its identity across refinement. */
class QuadNode private constructor() {
    var store: MoleculeStore? = null
    var lastAccessTick = 0
    var children: Array<QuadNode>? = null
    val monomerRemainder = IntArray(3)
    val isLeaf: Boolean get() = children == null

    fun becomeInternal(ch: Array<QuadNode>) { children = ch; store = null }
    fun becomeLeaf(s: MoleculeStore, tick: Int) { store = s; lastAccessTick = tick; children = null; monomerRemainder.fill(0) }

    companion object {
        fun leaf(): QuadNode = QuadNode().also { it.store = MoleculeStore() }
    }
}
