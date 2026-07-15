package org.emerge.demo.cyto.sim

import kotlin.math.floor
import kotlin.math.min

/**
 * The environment **matter field**: a flat, dense grid of [RES]² texels, stored as one contiguous
 * `IntArray` **column per species** (structure-of-arrays). A column is allocated the first time a species
 * appears anywhere in the field and holds that species' count for every texel, indexed `iy * RES + ix`
 * row-major with the torus wrap living only in [texelIndex].
 *
 * **Why dense.** This was an adaptive quad-tree that pooled unobserved regions back toward coarse leaves.
 * That collapse doubled as the world's only diffusion, but it could only ever fire where no cell was, so
 * it was removed (see the commit that dropped it) — leaving refinement one-way, which meant the tree grew
 * monotonically toward a dense grid anyway while still paying four dependent pointer hops per texel
 * (`node → store → ids → counts`) to read a count. A census of a real save found only **16 distinct
 * species** present in the field out of [SpeciesRegistry]'s 1884 legal molecules, four of them (`b`, `rg`,
 * `r`, `g`) in *every* leaf — so per-species columns are both small (~1 MB each at the default world) and
 * mostly full. Dense trades the void's laziness for flat sequential reads and index arithmetic.
 *
 * **There is no diffusion.** The field is inert: matter stays exactly where it was last left, and the only
 * thing that ever moves it is life — a cell's footprint balances all its texels toward a common level
 * (mixing *through* the cell), [deposit] returns death/spill, and [decayLeaf] atomises polymers in place.
 * If diffusion returns it should be an explicit, isotropic step, not a side effect of observation.
 *
 * Geometry is world-size-coupled: [RES] = [BASE_RES]·2^[TILE_SHIFT], and `BASE_RES` tracks the world size
 * so a texel stays a fixed logical size and a cell's matter footprint is world-size-invariant. Memory is
 * therefore quadratic in world size (~17 MB at the default 64-world, ~270 MB at a 256-world) — a deliberate
 * trade against the tree's adaptivity; chunked columns are the escape hatch if a big world ever needs one.
 *
 * All matter ops are **exact integer** (conservation) in a **fixed traversal order** (determinism); only the
 * *geometry* (which texels a disc covers) uses Float — same-platform deterministic, matching the light
 * field's stance (not cross-platform lockstep).
 */
class CytoMatterField private constructor(
    /** Texels per axis, captured at construction (see [RES] — it tracks the live world config). */
    private val res: Int,
    /** `speciesId → dense per-texel counts`, or null when the species is absent from the whole field.
     *  Sized to the registry, so a lookup is one array load and an absent species costs a null check. */
    private val columns: Array<IntArray?>,
) {
    private val texels = res * res

    // ── renderer read model ────────────────────────────────────────────────────────────────────────────
    // Per-channel atom totals per texel, refilled by [maintain] on the same cadence the summary used to be.
    // The overlay is a straight texel scan of these: no tree, no store deref, no per-leaf normalisation.
    private var chR = IntArray(texels)
    private var chG = IntArray(texels)
    private var chB = IntArray(texels)

    /** Whether [maintain] refreshes the channel read model. Tallying every texel is the one part that is
     *  NOT free, so a host that isn't drawing the matter overlay can switch it off. */
    var summaryEnabled = true

    val channelRed: IntArray get() = chR
    val channelGreen: IntArray get() = chG
    val channelBlue: IntArray get() = chB
    /** Texels per axis for this field (row-major; index = `iy * resolution + ix`). */
    val resolution: Int get() = res

    init { rebuildChannels() }

    // ── build ────────────────────────────────────────────────────────────────────────────────────────
    companion object {
        /** Base tiles per torus axis — runtime, coupled to the world size so a texel stays a fixed logical
         *  size and a cell's matter footprint is world-size-invariant (see [CytoWorldConfig.matterBaseRes]). */
        val BASE_RES: Int get() = CytoWorldConfig.matterBaseRes
        /** Texels per base tile axis, as a shift (128) — the old quad-tree's MAX_DEPTH. */
        const val TILE_SHIFT = 7
        val SPAN: Float get() = CytoLightField.SPAN
        val HALF: Float get() = CytoLightField.HALF
        val TILE: Float get() = SPAN / BASE_RES
        /** Texels across the whole torus per axis (512 at the default 64-world). */
        val RES: Int get() = BASE_RES shl TILE_SHIFT
        /** World-space size of one texel. */
        val TEXEL: Float get() = SPAN / RES
        /** Max disc half-extent (cell-diam) — bounds a giant cell's footprint. */
        const val MAX_DISC_RADIUS = 4f

        val A = SpeciesRegistry.id("r"); val B = SpeciesRegistry.id("g"); val C = SpeciesRegistry.id("b")
        private val MONO = intArrayOf(A, B, C)

        fun empty(): CytoMatterField = CytoMatterField(RES, arrayOfNulls(SpeciesRegistry.size))

        /** Uniform larder: `level` of each monomer in every texel. (The quad-tree seeded `level · texels`
         *  into one leaf per tile and divided it down on refinement; since a tile is exactly 4^TILE_SHIFT
         *  texels that division was always exact, so this is the same field.) */
        fun seededUniform(level: Int): CytoMatterField {
            val f = empty()
            if (level > 0) {
                for (m in MONO) f.column(m).fill(level)
                f.rebuildChannels()   // empty()'s init tallied a field with no columns yet
            }
            return f
        }

        /** Migration reader for the v9 quad-tree save format: walk the encoded tree and splat each leaf's
         *  store evenly across the texels it covered, so an old save loads into the dense field. Lossy only
         *  where a coarse leaf's count doesn't divide its texel span — the remainder lands on the region's
         *  first texels in index order (deterministic, conservation-exact). */
        fun decodeTree(readByte: () -> Int, readStore: () -> Map<String, Int>, readInt: () -> Int): CytoMatterField {
            val f = empty()
            val tileTexels = 1 shl TILE_SHIFT
            for (gy in 0 until BASE_RES) for (gx in 0 until BASE_RES) {
                f.decodeNodeInto(readByte, readStore, readInt, gx * tileTexels, gy * tileTexels, tileTexels)
            }
            f.rebuildChannels()
            return f
        }
    }

    /** Get species [sp]'s column, allocating it on first use. */
    private fun column(sp: Int): IntArray {
        val existing = columns[sp]
        if (existing != null) return existing
        val fresh = IntArray(texels)
        columns[sp] = fresh
        return fresh
    }

    private fun decodeNodeInto(
        rb: () -> Int, rs: () -> Map<String, Int>, ri: () -> Int, x0: Int, y0: Int, size: Int,
    ) {
        if (rb() == 0) {
            val store = rs()
            for ((species, count) in store) if (count > 0) splat(SpeciesRegistry.id(species), count, x0, y0, size)
            return
        }
        // Internal: the stashed monomer remainder (≤3 each) first, matching the v9 encode order.
        val rem = IntArray(3) { ri() }
        for (s in 0..2) if (rem[s] > 0) splat(MONO[s], rem[s], x0, y0, size)
        val h = size / 2
        decodeNodeInto(rb, rs, ri, x0, y0, h)
        decodeNodeInto(rb, rs, ri, x0 + h, y0, h)
        decodeNodeInto(rb, rs, ri, x0, y0 + h, h)
        decodeNodeInto(rb, rs, ri, x0 + h, y0 + h, h)
    }

    /** Spread `count` of `sp` evenly over the `size`×`size` texel block at (x0,y0); remainder to the first
     *  texels in index order. Conservation-exact. */
    private fun splat(sp: Int, count: Int, x0: Int, y0: Int, size: Int) {
        if (count <= 0) return
        val col = column(sp)
        val cells = size * size
        val per = count / cells
        var extra = count - per * cells
        for (dy in 0 until size) {
            val row = (y0 + dy) * res
            for (dx in 0 until size) {
                var a = per
                if (extra > 0) { a++; extra-- }
                if (a > 0) col[row + x0 + dx] += a
            }
        }
    }

    // ── indexing ─────────────────────────────────────────────────────────────────────────────────────
    private fun wrap(i: Int): Int = ((i % res) + res) % res
    /** World (x,y) → torus-wrapped texel index. */
    fun texelIndex(x: Float, y: Float): Int {
        val t = SPAN / res
        return wrap(floor((y + HALF) / t).toInt()) * res + wrap(floor((x + HALF) / t).toInt())
    }

    /** Visit the texels whose CENTRE lies within [rRaw] of (cx,cy) — the same disc rule the quad-tree's
     *  finest-depth test used, so a footprint covers exactly the texels it covered as leaves. Torus-wrapped;
     *  index order (row-major over the unwrapped bounding box) is fixed, so traversal is deterministic. */
    inline fun forEachFootprintTexel(cx: Float, cy: Float, rRaw: Float, visit: (idx: Int) -> Unit) {
        val r = if (rRaw > MAX_DISC_RADIUS) MAX_DISC_RADIUS else rRaw
        val n = resolution
        val t = SPAN / n
        // Texel i's centre sits at -HALF + (i + 0.5)·t, so the disc spans these unwrapped indices.
        val ixMin = floor((cx - r + HALF) / t - 0.5f).toInt()
        val ixMax = floor((cx + r + HALF) / t + 0.5f).toInt()
        val iyMin = floor((cy - r + HALF) / t - 0.5f).toInt()
        val iyMax = floor((cy + r + HALF) / t + 0.5f).toInt()
        val rr = r * r
        for (iyRaw in iyMin..iyMax) {
            val ccy = -HALF + (iyRaw + 0.5f) * t
            val ey = ccy - cy
            val row = (((iyRaw % n) + n) % n) * n
            for (ixRaw in ixMin..ixMax) {
                val ccx = -HALF + (ixRaw + 0.5f) * t
                val ex = ccx - cx
                if (ex * ex + ey * ey <= rr) visit(row + (((ixRaw % n) + n) % n))
            }
        }
    }

    // ── totals / iteration (digest, conservation, save) ────────────────────────────────────────────────
    fun totalAtoms(): Long {
        var sum = 0L
        for (sp in columns.indices) {
            val col = columns[sp] ?: continue
            val atoms = SpeciesRegistry.atomCount(sp).toLong()
            var c = 0L
            for (i in 0 until texels) c += col[i]
            sum += c * atoms
        }
        return sum
    }

    /** Read-only diagnostic: accumulate per-element (monomer) atom totals into [out] (indexed by element
     *  id, e.g. 0=r,1=g,2=b). Complete — decomposes polymers — so ΣΣ[out] equals [totalAtoms]. Used by
     *  conservation checks to localise WHICH element leaks. */
    fun elementTotals(out: LongArray) {
        for (sp in columns.indices) {
            val col = columns[sp] ?: continue
            var c = 0L
            for (i in 0 until texels) c += col[i]
            if (c == 0L) continue
            var cur = sp
            while (SpeciesRegistry.atomCount(cur) > 1) {       // peel one lead monomer per step
                out[SpeciesRegistry.firstAtom(cur)] += c
                cur = SpeciesRegistry.splitLeftRest(cur)
            }
            out[SpeciesRegistry.firstAtom(cur)] += c           // final lone atom
        }
    }

    /** Visit every NON-EMPTY texel with its region (lower-left corner + size) and contents — row-major, so
     *  the order is stable. Used for digest / save / conservation / the headless image view.
     *
     *  The [MoleculeStore] handed to [visit] is a **reused scratch**: read it, don't retain it. */
    fun forEachTexel(visit: (x: Float, y: Float, size: Float, store: MoleculeStore) -> Unit) {
        val t = SPAN / res
        val scratch = MoleculeStore()
        val present = presentSpecies()
        for (iy in 0 until res) {
            val row = iy * res
            for (ix in 0 until res) {
                val i = row + ix
                scratch.clear()
                for (sp in present) {
                    val c = columns[sp]!![i]
                    if (c > 0) scratch.add(sp, c)          // ascending sp ⇒ store stays sorted
                }
                if (scratch.size == 0) continue
                visit(-HALF + ix * t, -HALF + iy * t, t, scratch)
            }
        }
    }

    /** Species ids with an allocated column, ascending (== lexicographic, see [SpeciesRegistry]). */
    fun presentSpecies(): IntArray {
        var n = 0
        for (sp in columns.indices) if (columns[sp] != null) n++
        val out = IntArray(n)
        var k = 0
        for (sp in columns.indices) if (columns[sp] != null) out[k++] = sp
        return out
    }

    // ── the diffusion junction (exchange) ──────────────────────────────────────────────────────────────
    /** Per-texel count of cells whose footprint touched it this exchange batch, lazily reset via the stamp
     *  ([touchTick] == the exchange tick means the count is live). ≥2 ⇒ contested ⇒ dropped. */
    private val touch = IntArray(texels)
    private val touchTick = IntArray(texels) { -1 }

    /** PASS 0 (serial, cheap): count how many batch cells touch each footprint texel. A texel touched by
     *  ≥2 cells is contested and will be dropped, which keeps the parallel exchange order-independent. */
    fun countFootprint(cx: Float, cy: Float, radius: Float, tick: Int) {
        forEachFootprintTexel(cx, cy, radius) { i ->
            if (touchTick[i] != tick) { touch[i] = 0; touchTick[i] = tick }
            touch[i]++
        }
    }

    /** PASS 1 (one cell, read-only w.r.t. other cells): collect this cell's UNCONTESTED (single-owner)
     *  footprint texels into [out]. Single-owner ⇒ no two cells write the same texel ⇒ order-independent
     *  ⇒ bit-identical to a sequential run (the determinism gate). */
    fun collectUncontestedFootprint(cx: Float, cy: Float, radius: Float, tick: Int, out: IntList) {
        out.clear()
        forEachFootprintTexel(cx, cy, radius) { i ->
            if (touchTick[i] == tick && touch[i] < 2) out.add(i)
        }
    }

    /** Visit each species present (count > 0) in ANY of [texelIdx], ascending id. Read-only, and every
     *  column read is independent, so this is safe to call concurrently from the per-cell exchange pass.
     *  Scans column-major (one flat column per species) rather than texel-major, so an absent species costs
     *  a single null check instead of a lookup per texel. */
    inline fun forEachPresentSpeciesIn(texelIdx: IntList, visit: (sp: Int) -> Unit) {
        for (sp in 0 until speciesCapacity) {
            val col = columnOrNull(sp) ?: continue
            for (k in 0 until texelIdx.size) {
                if (col[texelIdx[k]] > 0) { visit(sp); break }
            }
        }
    }

    /** Size of the species-id space (== [SpeciesRegistry.size]); see [columnOrNull]. */
    val speciesCapacity: Int get() = columns.size
    /** Species [sp]'s dense column, or null when it is absent from the whole field. Exposed for the inline
     *  footprint scans; treat as read-only — mutating it bypasses the field's own bookkeeping. */
    fun columnOrNull(sp: Int): IntArray? = columns[sp]

    /** Thread-safe batched balance for the parallel drop-contested exchange: balances every transfer species
     *  over an explicit, caller-owned [texelIdx] list in a single pass, applying each net Δ straight into
     *  [cyt] (the field loses −Δ). All state is per-cell-disjoint (uncontested texels, own cytoplasm), so
     *  parallel workers never race. [n] is the divisor for the per-texel target (`cEff/n`) — the caller
     *  passes the *uncontested* count, so a cell equilibrates across only the texels it still owns. */
    fun balanceBatchedOn(
        texelIdx: IntList, n: Int, transferN: Int,
        transferIdx: IntArray, transferCeffs: IntArray, transferDir: IntArray, scaleFactor: Float,
        cyt: MoleculeStore,
    ) {
        if (n == 0 || transferN == 0) return
        for (t in 0 until transferN) {
            val sid = transferIdx[t]
            // A species absent from the whole field has no column ⇒ absent from every texel here too.
            val col = columns[sid] ?: continue
            val bucket = transferCeffs[t] / n
            val denom = 2.shl(min(31, (SpeciesRegistry.atomCount(sid) * scaleFactor).toInt()))
            val dir = transferDir[t]
            for (k in 0 until texelIdx.size) {
                val i = texelIdx[k]
                // A texel that doesn't already hold this species is skipped, NOT balanced toward the target.
                // This looks like a fast path but is load-bearing: the tree stored a texel as a sorted
                // id→count store, and the exchange looked its species up with a binary search that bailed on
                // a miss — so a cell could never introduce a species into a texel that lacked it, only trade
                // one that was already there. Balancing an absent species would push matter OUT of the cell
                // into empty ground (bucket ≥ 0 ⇒ negative movement) and change the biology.
                if (col[i] == 0) continue
                // +movement flows INTO the cell (cyt gains, texel loses). A one-way gate clamps the sign:
                // dir +1 (Import) permits inward only, dir -1 (Export) permits outward only, 0 is free.
                var movement = (col[i] - bucket) / denom
                if (dir > 0 && movement < 0) movement = 0
                else if (dir < 0 && movement > 0) movement = 0
                // Never push out more than the cell actually holds (Export's inflated cEff can demand more
                // than cytoplasm has; the outflow is spread across texels until the reserve is spent). Reads
                // the live count so successive texels see the depleted remainder. Keeps cytoplasm ≥ 0 without
                // breaking conservation (texel and cyt exchange the same clamped amount).
                if (dir < 0 && movement < 0) movement = movement.coerceAtLeast(-cyt.count(sid))
                if (movement != 0) { col[i] -= movement; cyt.add(sid, movement) }
            }
        }
    }

    // ── sequential exchange (the single-cell path; used by the field's own tests) ───────────────────────
    private val fpTexels = IntList()

    /** Open a cell's footprint: collect its N texels. Returns N (0 = nothing). Follow with [balance] per
     *  species, then [closeFootprint]. NOT re-entrant (single scratch). */
    fun openFootprint(cx: Float, cy: Float, radius: Float): Int {
        fpTexels.clear()
        forEachFootprintTexel(cx, cy, radius) { fpTexels.add(it) }
        return fpTexels.size
    }

    /** Balance the open footprint's `sp` toward `cEff/N` (bidirectional), with size-dependent dampening via
     *  [scaleFactor] so larger molecules equilibrate more slowly. Returns the net Δ to apply to the cell's
     *  cytoplasm (the field changes by −Δ). Conservation-exact. */
    fun balance(sp: Int, cEff: Int, scaleFactor: Float): Int {
        val n = fpTexels.size; if (n == 0) return 0
        val col = columns[sp] ?: return 0
        val denom = 2.shl(min(31, (SpeciesRegistry.atomCount(sp) * scaleFactor).toInt()))
        val bucket = cEff / n                       // remainder kept in cell (untransacted)
        var totalMovement = 0
        for (k in 0 until n) {
            val i = fpTexels[k]
            val movement = (col[i] - bucket) / denom
            if (movement != 0) { col[i] -= movement; totalMovement += movement }
        }
        return totalMovement                        // Δ into the cell
    }

    fun closeFootprint() = fpTexels.clear()

    // ── death / export deposit ─────────────────────────────────────────────────────────────────────────
    /** Add `amount` of `sp` spread across a footprint. Conservation-exact. */
    fun deposit(cx: Float, cy: Float, radius: Float, sp: Int, amount: Int) {
        if (amount <= 0) return
        fpTexels.clear()
        forEachFootprintTexel(cx, cy, radius) { fpTexels.add(it) }
        val n = fpTexels.size; if (n == 0) return
        val col = column(sp)
        val per = amount / n; var extra = amount - per * n
        for (k in 0 until n) {
            var a = per
            if (extra > 0) { a++; extra-- }
            if (a > 0) col[fpTexels[k]] += a
        }
        fpTexels.clear()
    }

    // ── maintenance: species decay ─────────────────────────────────────────────────────────────────────
    /** Decay every polymer in place, then refill the renderer's channel read model. The field is otherwise
     *  inert: nothing here moves matter between texels. */
    fun maintain(decayPeriod: Int) {
        decayAll(decayPeriod)
        if (summaryEnabled) rebuildChannels()
    }

    /** Atomise ⌊count/period⌋ of each polymer per texel (peel the leftmost bond). Conservation-exact.
     *
     *  Each species must break exactly ONCE per call, not geometrically — so a fragment produced here must
     *  not itself be re-broken in the same pass. Peeling the lead monomer off a polymer always yields a
     *  strictly SHORTER rest, so visiting polymers in **ascending atom count** guarantees a species is
     *  already done by the time anything can deposit fragments into its column; those wait for the next
     *  pass. (Ties broken by ascending id — a lone chain length can't feed itself — so the order is total
     *  and the pass is deterministic.) */
    private fun decayAll(period: Int) {
        if (period <= 0) return
        val polymers = presentSpecies()
            .filter { SpeciesRegistry.atomCount(it) >= 2 }
            .sortedWith(compareBy({ SpeciesRegistry.atomCount(it) }, { it }))
        for (sp in polymers) {
            val col = columns[sp] ?: continue
            val leadCol = column(SpeciesRegistry.splitLeftMono(sp))
            val restCol = column(SpeciesRegistry.splitLeftRest(sp))
            for (i in 0 until texels) {
                val broken = col[i] / period
                if (broken <= 0) continue
                col[i] -= broken
                leadCol[i] += broken
                restCol[i] += broken
            }
        }
    }

    /** Refill the per-channel atom totals from the columns. One tight pass per (species, contributing
     *  channel): a species contributes to at most 3 channels and usually 1 (a monomer), so this is far
     *  fewer passes than it looks — and each is a branch-free accumulate the JIT can vectorise, which beats
     *  a single fused loop testing every channel per texel. */
    private fun rebuildChannels() {
        chR.fill(0); chG.fill(0); chB.fill(0)
        for (sp in columns.indices) {
            val col = columns[sp] ?: continue
            val ar = SpeciesRegistry.atomsInChannel(sp, 0)
            val ag = SpeciesRegistry.atomsInChannel(sp, 1)
            val ab = SpeciesRegistry.atomsInChannel(sp, 2)
            if (ar != 0) for (i in 0 until texels) chR[i] += col[i] * ar
            if (ag != 0) for (i in 0 until texels) chG[i] += col[i] * ag
            if (ab != 0) for (i in 0 until texels) chB[i] += col[i] * ab
        }
    }

    // ── snapshot / read / serialise (for the reducer bridge, UI, save codec) ───────────────────────────
    /** Deep clone (snapshot isolation: the lifecycle bridge mutates a copy). */
    fun copy(): CytoMatterField {
        val cols = arrayOfNulls<IntArray>(columns.size)
        for (sp in columns.indices) cols[sp] = columns[sp]?.copyOf()
        return CytoMatterField(res, cols)
    }

    /** Read-only contents of the texel containing (cx,cy) — for the UI panel. */
    fun contentsAt(cx: Float, cy: Float): Map<String, Int> {
        val i = texelIndex(cx, cy)
        val m = LinkedHashMap<String, Int>()
        for (sp in columns.indices) {
            val c = columns[sp]?.get(i) ?: 0
            if (c > 0) m[SpeciesRegistry.string(sp)] = c
        }
        return m
    }

    /** Structured serialise: the present species, then each one's full dense column. Columns are written in
     *  ascending species id, and the reader reconstructs by id, so the format is independent of registry
     *  iteration order. */
    fun encode(writeInt: (Int) -> Unit, writeString: (String) -> Unit) {
        val present = presentSpecies()
        writeInt(res)
        writeInt(present.size)
        for (sp in present) {
            writeString(SpeciesRegistry.string(sp))
            val col = columns[sp]!!
            for (i in 0 until texels) writeInt(col[i])
        }
    }

    /** Inverse of [encode]. A saved field whose resolution differs from this world's is rejected by the
     *  caller (the world geometry is restored first), so [res] is read back purely as a cross-check. */
    fun decodeInto(readInt: () -> Int, readString: () -> String) {
        val savedRes = readInt()
        require(savedRes == res) { "matter field resolution mismatch: save has $savedRes, world is $res" }
        val count = readInt()
        repeat(count) {
            val sp = SpeciesRegistry.id(readString())
            val col = column(sp)
            for (i in 0 until texels) col[i] = readInt()
        }
        rebuildChannels()
    }
}

/** A minimal growable int list — the exchange's per-cell texel scratch. Avoids boxing an `ArrayList<Int>`
 *  on the hot path, and unlike `IntArray` it can be reused across footprints of differing size. */
class IntList(initialCapacity: Int = 1024) {
    private var a = IntArray(initialCapacity)
    var size = 0
        private set

    operator fun get(i: Int): Int = a[i]
    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    fun clear() { size = 0 }
}
