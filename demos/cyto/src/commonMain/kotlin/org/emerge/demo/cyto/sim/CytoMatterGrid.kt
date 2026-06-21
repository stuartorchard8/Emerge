package org.emerge.demo.cyto.sim

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The world's finite **matter reservoir** — a depletable, spatial store of integer molecule counts per
 * grid cell over the torus (matter model, MORPHOGENESIS.md). It replaces the old depletable *energy*
 * grid: matter (atoms) is the conserved/limiting resource, energy is open (light).
 *
 * Cells **import** molecules from the grid cell they sit in (depleting it) and, on **death**, deposit
 * all their molecules back. Atoms are only ever moved, never minted, so total atoms across
 * {reservoir + every cell's cytoplasm + biomass} is **conserved** — a hard carrying capacity. Access is
 * per **discrete** grid cell (no interpolation) and integer, so conservation holds exactly.
 *
 * Counts are stored id-keyed in a [MoleculeStore] per cell (the dense-chemistry path): the hot biology
 * accesses them by [SpeciesRegistry] id ([count]`(idx, id)`, [draw], [deposit]`(idx, id, …)`); the
 * string-keyed overloads + [cellAt] are the cold boundary (save / render / lifecycle / inspection).
 *
 * Geometry (resolution / extent / source layout) is shared with the static [CytoLightField], which
 * remains the (non-depletable) energy source.
 */
class CytoMatterGrid private constructor(
    private val cells: Array<MoleculeStore>,
    /** Copy-on-write ownership: `owned[i]` is true once this grid has cloned cell `i` and may mutate it
     *  freely. [copy] / [diffused] hand out grids that *share* the inner stores (one array copy, not 4096
     *  store copies) and flip a bit + clone only the cells a tick actually touches — so per-tick allocation
     *  tracks the handful of occupied cells, not the whole grid. */
    private val owned: BooleanArray,
) {

    /** Ensure cell [i] is privately owned before mutating it (clone the shared store on first write). */
    private fun own(i: Int) {
        if (!owned[i]) {
            cells[i] = cells[i].copy()
            owned[i] = true
        }
    }

    /** Grid-cell index for a logical world position (nearest cell, torus-wrapped). */
    fun indexOf(logicalX: Float, logicalY: Float): Int {
        val gx = wrapIndex(floor((logicalX / SPAN + 0.5f) * RES).toInt())
        val gy = wrapIndex(floor((logicalY / SPAN + 0.5f) * RES).toInt())
        return gy * RES + gx
    }

    // ── hot path (id-keyed) ──────────────────────────────────────────────────────────────────────────
    fun count(idx: Int, id: Int): Int = cells[idx].count(id)

    /** Number of distinct species present in cell [idx], and the i-th one's id — for iterating a cell's
     *  contents without exposing the mutable store (used by passive exchange's species union). */
    fun cellSize(idx: Int): Int = cells[idx].size
    fun cellIdAt(idx: Int, i: Int): Int = cells[idx].idAt(i)

    /** Draw up to [want] molecules of species [id] from cell [idx]; returns how many were available (and
     *  removes exactly that many). The only way matter enters a cell. */
    fun draw(idx: Int, id: Int, want: Int): Int {
        if (want <= 0 || id < 0) return 0
        val have = cells[idx].count(id)
        val taken = if (want < have) want else have
        if (taken > 0) { own(idx); cells[idx].add(id, -taken) }
        return taken
    }

    /** Return [amount] molecules of species [id] to cell [idx] (death / export). The only way matter
     *  leaves a cell back to the world. */
    fun deposit(idx: Int, id: Int, amount: Int) {
        if (amount <= 0 || id < 0) return
        own(idx)
        cells[idx].add(id, amount)
    }

    // ── string boundary (cold: lifecycle / save / inspection) ─────────────────────────────────────────
    fun count(idx: Int, species: String): Int = cells[idx].count(SpeciesRegistry.id(species))
    fun deposit(idx: Int, species: String, amount: Int) = deposit(idx, SpeciesRegistry.id(species), amount)

    /** Total **atoms** held in the reservoir (Σ count × molecule length) — the conserved quantity, with
     *  every cell's cytoplasm + biomass atoms. */
    fun totalAtoms(): Long {
        var sum = 0L
        for (cell in cells) for (i in 0 until cell.size) sum += SpeciesRegistry.atomCount(cell.idAt(i)).toLong() * cell.countAt(i)
        return sum
    }

    /** Read-only string view of one grid cell's contents (for save / inspection / digest). */
    fun cellAt(idx: Int): Map<String, Int> = cells[idx].toStringMap()

    /** Copy-on-write clone — so a tick's draws/deposits don't mutate the source snapshot. Shares the
     *  inner stores (one array + one BooleanArray allocation); each cell is cloned lazily the first time
     *  this grid mutates it (see [own]). */
    fun copy(): CytoMatterGrid = CytoMatterGrid(cells.copyOf(), BooleanArray(cells.size))

    /** Fill [out] with the grid-cell indices whose CENTRE lies within [radiusLogical] of (cx,cy) on the
     *  torus — a cell's circular absorption footprint (the disc gather). Row-major over the disc's bounding
     *  box (deterministic order); returns the count (always ≥ 1, the centre cell). The half-extent is capped
     *  at [MAX_DISC_SPAN] so a giant cell can't gather from an unbounded disc; [out] must be sized
     *  `(2·MAX_DISC_SPAN+1)²`. */
    fun fillDisc(cx: Float, cy: Float, radiusLogical: Float, out: IntArray): Int {
        val cellSize = SPAN / RES
        val span = ((radiusLogical / cellSize).toInt() + 1).coerceAtMost(MAX_DISC_SPAN)
        val gcx = floor((cx / SPAN + 0.5f) * RES).toInt()
        val gcy = floor((cy / SPAN + 0.5f) * RES).toInt()
        val r2 = radiusLogical * radiusLogical
        var n = 0
        for (dy in -span..span) {
            val gy = wrapIndex(gcy + dy)
            val ddy = wrapDelta((-HALF + (gy + 0.5f) * cellSize) - cy)   // grid-cell centre → torus delta
            for (dx in -span..span) {
                val gx = wrapIndex(gcx + dx)
                val ddx = wrapDelta((-HALF + (gx + 0.5f) * cellSize) - cx)
                if (ddx * ddx + ddy * ddy <= r2 && n < out.size) { out[n] = gy * RES + gx; n++ }
            }
        }
        if (n == 0) { out[0] = wrapIndex(gcy) * RES + wrapIndex(gcx); return 1 }   // always ≥ the centre cell
        return n
    }

    private fun bump(idx: Int, id: Int, delta: Int) {
        own(idx)
        cells[idx].add(id, delta)
    }

    /**
     * Spontaneous **environmental decay**: free molecules break their leftmost bond at rate `1/[period]`
     * (⌊count/period⌋ of each multi-atom species per call), peeling the leading monomer off — `abc` →
     * `a` + `bc` — with both fragments deposited in the same grid-cell. Over time every molecule erodes
     * back to monomers, returning matter that selective uptake stranded (species no live cell can
     * metabolise) to the accessible pool. Snapshot-based (reads `this`, writes copy-on-write `next`) and
     * conservation-exact (a molecule's atoms = its two fragments' atoms). Same primitive as biomass decay.
     */
    fun decayed(period: Int): CytoMatterGrid {
        if (period <= 0) return this
        val next = CytoMatterGrid(cells.copyOf(), BooleanArray(cells.size))
        for (i in cells.indices) {
            val cell = cells[i]
            for (s in 0 until cell.size) {
                val id = cell.idAt(s)
                if (SpeciesRegistry.atomCount(id) < 2) continue   // monomers don't decay further
                val broken = cell.countAt(s) / period
                if (broken <= 0) continue
                next.bump(i, id, -broken)
                next.bump(i, SpeciesRegistry.splitLeftMono(id), broken)   // leading monomer
                next.bump(i, SpeciesRegistry.splitLeftRest(id), broken)   // remainder
            }
        }
        return next
    }

    companion object {
        /** Matter resolution is DECOUPLED from the light grid (sub-cell — see CytoTuning.MATTER_GRID_RES);
         *  SPAN/HALF (the torus extent) still come from the shared world geometry. */
        val RES = CytoTuning.MATTER_GRID_RES
        val SPAN = CytoLightField.SPAN
        val HALF = CytoLightField.HALF

        /** Disc-gather half-extent cap (grid cells): bounds a giant cell's footprint for perf. [fillDisc]'s
         *  `out` must be sized `(2·MAX_DISC_SPAN+1)²`. */
        const val MAX_DISC_SPAN = 8
        const val DISC_CAPACITY = (2 * MAX_DISC_SPAN + 1) * (2 * MAX_DISC_SPAN + 1)

        // The reservoir *seed* (peak/falloff/species) is initial data in CytoSeed. (Diffusion removed — the
        // disc gather replaces its "feed sessile cells" role; environmental decay stays.)
        const val MATTER_PEAK = CytoSeed.MATTER_PEAK
        const val MATTER_FALLOFF = CytoSeed.MATTER_FALLOFF
        val SEED_MONOMERS = CytoSeed.SEED_MONOMERS

        /**
         * A fresh reservoir: free monomers in a Gaussian bump around each of the 4 light sources, so the
         * good real estate (near light) also has matter. Counts are integer (floored). NB: the Gaussian
         * uses `Float` `exp` — fine for a single-player local sim; for lockstep the seeded grid travels
         * in the serialized snapshot (it is not re-derived per peer), so cross-platform `exp` drift is
         * not conservation-critical.
         */
        fun seeded(): CytoMatterGrid {
            val cells = Array(RES * RES) { MoleculeStore() }
            val monomerIds = SEED_MONOMERS.map { SpeciesRegistry.id(it) }
            if (CytoSeed.MATTER_UNIFORM) {   // flat substrate everywhere (the moving-light world)
                for (cell in cells) for (m in monomerIds) cell.add(m, CytoSeed.MATTER_UNIFORM_LEVEL)
                return CytoMatterGrid(cells, BooleanArray(cells.size) { true })
            }
            val cellSize = SPAN / RES
            val inv = 1f / (MATTER_FALLOFF * MATTER_FALLOFF)
            for (gy in 0 until RES) {
                val wy = -HALF + (gy + 0.5f) * cellSize
                for (gx in 0 until RES) {
                    val wx = -HALF + (gx + 0.5f) * cellSize
                    var g = 0f
                    for ((sx, sy) in CytoLightField.SOURCES) {
                        val dx = wrapDelta(wx - sx); val dy = wrapDelta(wy - sy)
                        g += exp(-(dx * dx + dy * dy) * inv)
                    }
                    val n = (MATTER_PEAK * g).roundToInt()
                    if (n > 0) {
                        val cell = cells[gy * RES + gx]
                        for (m in monomerIds) cell.add(m, n)
                    }
                }
            }
            return CytoMatterGrid(cells, BooleanArray(cells.size) { true })
        }

        /** Reconstruct from saved per-cell string maps. */
        fun fromCells(saved: Array<HashMap<String, Int>>): CytoMatterGrid =
            CytoMatterGrid(Array(saved.size) { MoleculeStore.of(saved[it]) }, BooleanArray(saved.size) { true })

        /** An empty reservoir of the right size (for save decode to fill). */
        fun empty(): CytoMatterGrid = CytoMatterGrid(Array(RES * RES) { MoleculeStore() }, BooleanArray(RES * RES) { true })

        private fun wrapIndex(i: Int): Int = ((i % RES) + RES) % RES
        private fun wrapDelta(d: Float): Float {
            var x = d
            while (x > HALF) x -= SPAN
            while (x < -HALF) x += SPAN
            return x
        }
    }
}
