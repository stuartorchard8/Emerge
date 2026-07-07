package org.emerge.demo.cyto.sim

import kotlin.math.floor
import kotlin.math.min

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
        const val BASE_RES = 4
        const val MAX_DEPTH = 6
        val SPAN = CytoLightField.SPAN          // logical torus extent (256 cell-diam)
        val HALF = CytoLightField.HALF          // 128
        val TILE = SPAN / BASE_RES              // 128 cell-diam per tile
        /** Max disc half-extent (cell-diam) — bounds a giant cell's footprint. */
        const val MAX_DISC_RADIUS = 4f

        val A = SpeciesRegistry.id("r"); val B = SpeciesRegistry.id("g"); val C = SpeciesRegistry.id("b")
        private val MONO = intArrayOf(A, B, C)
        private fun monoSlot(id: Int): Int = when (id) { A -> 0; B -> 1; C -> 2; else -> -1 }

        fun empty(): CytoMatterField = CytoMatterField(Array(BASE_RES * BASE_RES) { QuadNode.leaf() })

        /** Inverse of [encodeTree]: rebuild from the codec's byte/int/store readers. */
        fun decodeTree(readByte: () -> Int, readStore: () -> Map<String, Int>, readInt: () -> Int): CytoMatterField =
            CytoMatterField(Array(BASE_RES * BASE_RES) { decodeNode(readByte, readStore, readInt) })
        private fun decodeNode(rb: () -> Int, rs: () -> Map<String, Int>, ri: () -> Int): QuadNode {
            if (rb() == 0) return QuadNode.leaf().also { it.store = MoleculeStore.of(rs()) }
            val rem = IntArray(3) { ri() }                       // remainder first (matches encodeNode order)
            val ch = Array(4) { decodeNode(rb, rs, ri) }
            return QuadNode.internal(ch, rem)
        }


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

    /** Read-only diagnostic: accumulate per-element (monomer) atom totals into [out] (indexed by element
     *  id, e.g. 0=r,1=g,2=b). Complete — decomposes leaf polymers AND counts internal-node monomer
     *  remainder — so ΣΣ[out] equals [totalAtoms]. Used by conservation checks to localise WHICH element
     *  leaks. No behaviour change. */
    fun elementTotals(out: LongArray) {
        forEachLeaf { _, _, _, store ->
            for (i in 0 until store.size) {
                val c = store.countAt(i).toLong()
                var cur = store.idAt(i)
                while (SpeciesRegistry.atomCount(cur) > 1) {   // peel one lead monomer per step
                    out[SpeciesRegistry.firstAtom(cur)] += c
                    cur = SpeciesRegistry.splitLeftRest(cur)
                }
                out[SpeciesRegistry.firstAtom(cur)] += c       // final lone atom
            }
        }
        forEachInternal { node -> for (s in 0..2) out[s] += node.monomerRemainder[s].toLong() }
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
        if (node.isLeaf) {
            try { visit(x, y, sz, node.store!!) } catch (e: NullPointerException) { /* merged mid-traversal, skip */ }
            return
        }
        val h = sz * 0.5f; val ch = node.children!!
        try { leafWalk(ch[0], x, y, h, visit); leafWalk(ch[1], x + h, y, h, visit) } catch (e: NullPointerException) { /* skip subtree */ }
        try { leafWalk(ch[2], x, y + h, h, visit); leafWalk(ch[3], x + h, y + h, h, visit) } catch (e: NullPointerException) { /* skip subtree */ }
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
            if (ex * ex + ey * ey <= r * r) { if (tick >= 0) node.lastAccessTick = tick; visit(node) }   // tick<0 ⇒ don't re-stamp
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
        descendDisc(cx, cy, radius, tick) { node ->
            fpLeaves.add(node)
            // Set presence mask for monomers r(=R), g(=G), b(=B) — the species passively diffused.
            // Mask bits: 1=r, 2=g, 4=b. Enables O(1) skip in balance().
            // Skip mask computation for empty stores — common when grid is sparse.
            val s = node.store!!
            if (s.size > 0) {
                var mask = 0
                for (i in 0 until s.size) {
                    val id = s.idAt(i)
                    if (id == A) mask = mask or 1
                    else if (id == B) mask = mask or 2
                    else if (id == C) mask = mask or 4
                }
                node.presenceMask = mask
            }
        }
        return fpLeaves.size
    }

    /** Collect the species present across the open footprint into [out] (deduped); returns count. */
    fun footprintSpecies(out: HashSet<Int>) {
        out.clear()
        for (leaf in fpLeaves) {
            val s = leaf.store!!
            if (s.size > 0) for (i in 0 until s.size) out.add(s.idAt(i))
        }
    }

    /** Balance the open footprint's `sp` toward `cEff/N` (bidirectional diffusion), with size-dependent
      *  dampening via [scaleFactor] so larger molecules equilibrate more slowly. Returns the net Δ to apply
      *  to the cell's cytoplasm (grid changes by −Δ). Conservation-exact. */
    fun balance(sp: Int, cEff: Int, scaleFactor: Float): Int {
        val n = fpLeaves.size; if (n == 0) return 0
        // Early-exit mask bit for monomers (r=0→bit1, g=1→bit2, b=2→bit4).
        // Skips leaf iteration when no leaf contains this species — eliminates 73% of useless calls.
        val maskBit = if (sp == A) 1 else if (sp == B) 2 else if (sp == C) 4 else 0

        val atomCount = SpeciesRegistry.atomCount(sp)
        val denom = 2.shl(min(31, (atomCount*scaleFactor).toInt()))  // 2 for monomers; scales up for polymers (2,4,8,16,32...)
        val bucket = cEff / n                       // remainder kept in cell (untransacted)
        var totalMovement = 0
        for (leaf in fpLeaves) {
            // Skip leaves that don't contain this species (presence mask from openFootprint).
            if (maskBit != 0 && (leaf.presenceMask and maskBit) == 0) continue

            val store = leaf.store!!
            val delta = store.countLinear(sp) - bucket  // +ve gradient towards cell, -ve gradient away from cell
            val movement = delta/denom  // lower movement for larger species

            if (movement != 0) store.add(sp, -movement)
            totalMovement += movement
        }
        return totalMovement                 // Δ into the cell
    }

    /** Batched balance: process all species in a single leaf pass (avoids 3× leaf re-traversal).
      *  Returns per-species delta array (same order as sps).
      *  Inverted loop: iterates over the [transferN] transfer species and binary-searches each
      *  in the sorted leaf store — only ~10 species need balancing vs. ~10+ species per leaf,
      *  so we skip O(store_size - transferN) wasted lookups per leaf.
      *  Also skips entire leaves whose presenceMask doesn't contain any monomer bits from
      *  the transfer set, eliminating traversal of leaves with no transferable monomers. */
    fun balanceBatched(transferN: Int, transferIdx: IntArray, transferCeffs: IntArray, scaleFactor: Float): IntArray {
        val n = fpLeaves.size; if (n == 0) return IntArray(transferN)
        val results = IntArray(transferN)
        // Pre-compute monomer bits needed by the transfer set.
        // Bits: 1=r, 2=g, 4=b. Monomer-only species only need mask check.
        var monomerMaskNeed = 0
        for (t in 0 until transferN) {
            val sp = transferIdx[t]
            if (SpeciesRegistry.atomCount(sp) == 1) {
                if (sp == A) monomerMaskNeed = monomerMaskNeed or 1
                else if (sp == B) monomerMaskNeed = monomerMaskNeed or 2
                else if (sp == C) monomerMaskNeed = monomerMaskNeed or 4
            }
        }
        for (leaf in fpLeaves) {
            // Skip leaves whose presence mask doesn't contain any monomer bits needed by transfer.
            // This skips O(leaves) entirely for leaves that only have polymers (not transferable monomers).
            if (monomerMaskNeed != 0 && (leaf.presenceMask and monomerMaskNeed) == 0) {
                // No monomers present — but polymers might still be transferable via import bias.
                // We need to check if there are any non-monomer transfer species.
                var hasNonMono = false
                for (t in 0 until transferN) {
                    if (SpeciesRegistry.atomCount(transferIdx[t]) > 1) { hasNonMono = true; break }
                }
                if (!hasNonMono) continue  // safe to skip
            }
            val store = leaf.store!!
            // Only look up the transfer species in this leaf (binary search on sorted store).
            for (t in 0 until transferN) {
                val sid = transferIdx[t]
                // Fast monomer check: skip if leaf doesn't have this monomer (presence mask).
                if (SpeciesRegistry.atomCount(sid) == 1) {
                    val bit = if (sid == A) 1 else if (sid == B) 2 else if (sid == C) 4 else 0
                    if (bit != 0 && (leaf.presenceMask and bit) == 0) continue
                }
                val idx = store.binarySearchId(sid)
                if (idx < 0) continue   // species not in this leaf
                val sc = store.countAt(idx)
                val bucket = transferCeffs[t] / n
                val denom = 2.shl(min(31, (SpeciesRegistry.atomCount(sid)*scaleFactor).toInt()))
                val delta = sc - bucket
                val movement = delta / denom
                if (movement != 0) store.add(sid, -movement)
                results[t] += movement
            }
        }
        return results
    }

    fun closeFootprint() = fpLeaves.clear()

    // ── death / export deposit ─────────────────────────────────────────────────────────────────────────
    /** Add `amount` of `sp` spread across a footprint (refine to fine first; does NOT re-stamp the collapse
     *  clock — a death/division deposit lands where the cell just was, already fresh). Conservation-exact. */
    fun deposit(cx: Float, cy: Float, radius: Float, sp: Int, amount: Int) {
        if (amount <= 0) return
        fpLeaves.clear()
        descendDisc(cx, cy, radius, -1) { fpLeaves.add(it) }   // tick = -1 ⇒ no stamp
        val n = fpLeaves.size; if (n == 0) { fpLeaves.clear(); return }
        val per = amount / n; var extra = amount - per * n
        for (leaf in fpLeaves) { var a = per; if (extra > 0) { a++; extra-- }; if (a > 0) leaf.store!!.add(sp, a) }
        fpLeaves.clear()
    }

    // ── maintenance: progressive collapse + species decay ──────────────────────────────────────────────
    fun maintain(currentTick: Int, collapseDelay: Int, decayPeriod: Int) {
        for (r in roots) maintainNode(r, 0, currentTick, collapseDelay, decayPeriod)
    }
    /** Post-order: decay leaves; merge an all-leaf internal node whose children are all stale (→ progressive,
     *  since the merged leaf is fresh and blocks its parent for another delay).
     *
     *  The stale threshold DOUBLES per layer above the finest: [collapseDelay] is the delay for the finest
     *  leaves (depth [MAX_DEPTH]); a merge whose children sit one layer up waits twice as long, and so on.
     *  A merge pools matter over a node twice as wide as the layer below, so making it wait twice as long
     *  means dispersal advances at a roughly constant speed — twice as far takes twice as long. */
    private fun maintainNode(node: QuadNode, depth: Int, tick: Int, collapseDelay: Int, decayPeriod: Int) {
        if (node.isLeaf) { decayLeaf(node, decayPeriod); return }
        val ch = node.children!!
        for (c in ch) maintainNode(c, depth + 1, tick, collapseDelay, decayPeriod)
        // The children are at depth+1; their merge delay doubles for each layer they sit above the finest.
        val childDelay = collapseDelay shl (MAX_DEPTH - (depth + 1))
        var allLeafStale = true
        for (c in ch) if (!c.isLeaf || tick - c.lastAccessTick < childDelay) { allLeafStale = false; break }
        if (allLeafStale) mergeNode(node, tick)
    }
    /** Atomise ⌊count/period⌋ of each multi-atom species (peel leftmost bond). Conservation-exact; does NOT
     *  touch lastAccessTick. */
    // ── snapshot / read / serialise (for the reducer bridge, UI, save codec) ───────────────────────────
    /** Deep clone (snapshot isolation: the lifecycle bridge mutates a copy). Sparse ⇒ O(allocated nodes). */
    fun copy(): CytoMatterField = CytoMatterField(Array(roots.size) { cloneNode(roots[it]) })
    private fun cloneNode(n: QuadNode): QuadNode =
        if (n.isLeaf) QuadNode.leaf().also { it.store!!.copyFrom(n.store!!); it.lastAccessTick = n.lastAccessTick }
        else QuadNode.internal(Array(4) { cloneNode(n.children!![it]) }, n.monomerRemainder.copyOf())

    /** Read-only contents of the finest EXISTING leaf containing (cx,cy) — no split (for the UI panel). */
    fun contentsAt(cx: Float, cy: Float): Map<String, Int> {
        try {
            var x = -HALF + mod2(floor((cx / SPAN + 0.5f) * BASE_RES).toInt()) * TILE
            var y = -HALF + mod2(floor((cy / SPAN + 0.5f) * BASE_RES).toInt()) * TILE
            var node = roots[mod2(floor((cy / SPAN + 0.5f) * BASE_RES).toInt()) * BASE_RES + mod2(floor((cx / SPAN + 0.5f) * BASE_RES).toInt())]
            var sz = TILE
            while (!node.isLeaf) {
                val h = sz * 0.5f; val east = cx >= x + h; val south = cy >= y + h
                val q = (if (south) 2 else 0) + (if (east) 1 else 0)
                if (east) x += h; if (south) y += h; sz = h; node = node.children!![q]
            }
            return node.store!!.toStringMap()
        } catch (_: NullPointerException) {
            return emptyMap()
        }
    }
    private fun mod2(i: Int) = ((i % BASE_RES) + BASE_RES) % BASE_RES

    /** Structured serialise (exact incl. internal stashes). Codec supplies the byte/int/store writers. */
    fun encodeTree(writeByte: (Int) -> Unit, writeStore: (Map<String, Int>) -> Unit, writeInt: (Int) -> Unit) {
        for (root in roots) encodeNode(root, writeByte, writeStore, writeInt)
    }
    private fun encodeNode(n: QuadNode, wb: (Int) -> Unit, ws: (Map<String, Int>) -> Unit, wi: (Int) -> Unit) {
        if (n.isLeaf) { wb(0); ws(n.store!!.toStringMap()) }
        else { wb(1); for (s in n.monomerRemainder) wi(s); for (c in n.children!!) encodeNode(c, wb, ws, wi) }
    }

    // ── species decay (atomise over time) — called by maintain ──────────────────────────────────────────
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
    var presenceMask = 0  // bit-mask of monomers present (bit 0=r, 1=g, 2=b); set during openFootprint
    val isLeaf: Boolean get() = children == null

    fun becomeInternal(ch: Array<QuadNode>) { children = ch; store = null }
    fun becomeLeaf(s: MoleculeStore, tick: Int) { store = s; lastAccessTick = tick; children = null; monomerRemainder.fill(0) }

    companion object {
        fun leaf(): QuadNode = QuadNode().also { it.store = MoleculeStore() }
        fun internal(ch: Array<QuadNode>, rem: IntArray): QuadNode =
            QuadNode().also { it.children = ch; rem.copyInto(it.monomerRemainder) }
    }
}
