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
 * Geometry (resolution / extent / source layout) is shared with the static [CytoLightField], which
 * remains the (non-depletable) energy source.
 */
class CytoMatterGrid private constructor(private val cells: Array<HashMap<String, Int>>) {

    /** Grid-cell index for a logical world position (nearest cell, torus-wrapped). */
    fun indexOf(logicalX: Float, logicalY: Float): Int {
        val gx = wrapIndex(floor((logicalX / SPAN + 0.5f) * RES).toInt())
        val gy = wrapIndex(floor((logicalY / SPAN + 0.5f) * RES).toInt())
        return gy * RES + gx
    }

    fun count(idx: Int, species: String): Int = cells[idx][species] ?: 0

    /** Draw up to [want] molecules of [species] from cell [idx]; returns how many were available (and
     *  removes exactly that many). The only way matter enters a cell. */
    fun draw(idx: Int, species: String, want: Int): Int {
        if (want <= 0) return 0
        val have = cells[idx][species] ?: 0
        val taken = if (want < have) want else have
        if (taken > 0) {
            val left = have - taken
            if (left == 0) cells[idx].remove(species) else cells[idx][species] = left
        }
        return taken
    }

    /** Return [amount] molecules of [species] to cell [idx] (death / export). The only way matter leaves
     *  a cell back to the world. */
    fun deposit(idx: Int, species: String, amount: Int) {
        if (amount <= 0) return
        cells[idx][species] = (cells[idx][species] ?: 0) + amount
    }

    /** Total **atoms** held in the reservoir (Σ count × molecule length) — the conserved quantity, with
     *  every cell's cytoplasm + biomass atoms. */
    fun totalAtoms(): Long {
        var sum = 0L
        for (cell in cells) for ((species, count) in cell) sum += species.length.toLong() * count
        return sum
    }

    /** Read-only view of one grid cell's contents (for save / inspection). */
    fun cellAt(idx: Int): Map<String, Int> = cells[idx]

    /** Deep copy — so a tick's draws/deposits don't mutate a source snapshot in place. */
    fun copy(): CytoMatterGrid = CytoMatterGrid(Array(cells.size) { HashMap(cells[it]) })

    /**
     * One **slow diffusion** step: matter spreads down-gradient between neighbouring grid cells so it
     * creeps out of source clumps toward where cells are depleting it. Snapshot-based (reads the current
     * `cells`, writes a fresh `next`) so it's order-independent; every move is grid-cell→grid-cell, so
     * total atoms are conserved exactly. Per species, each undirected edge moves `⌊|a−b|·num/den⌋` from
     * the richer cell to the poorer. Integer ⇒ rate-quantized: gradients below `den/num` don't move, so
     * it settles instead of perfectly equalising. Keep `4·num/den ≤ 1` (a cell touches 4 edges) so it
     * can't be over-drawn negative.
     */
    fun diffused(num: Int, den: Int): CytoMatterGrid {
        val next = Array(cells.size) { HashMap(cells[it]) }
        for (gy in 0 until RES) {
            for (gx in 0 until RES) {
                val i = gy * RES + gx
                diffuseEdge(i, gy * RES + wrapIndex(gx + 1), num, den, next) // right neighbour
                diffuseEdge(i, wrapIndex(gy + 1) * RES + gx, num, den, next) // down neighbour
            }
        }
        return CytoMatterGrid(next)
    }

    /** Move `⌊|a−b|·num/den⌋` of each shared species from the richer of cells [i],[j] to the poorer, in
     *  [next]. Reads the pre-step counts from `cells` (snapshot) so edge order doesn't matter. */
    private fun diffuseEdge(i: Int, j: Int, num: Int, den: Int, next: Array<HashMap<String, Int>>) {
        val ci = cells[i]; val cj = cells[j]
        if (ci.isEmpty() && cj.isEmpty()) return
        val species = HashSet<String>(ci.keys); species.addAll(cj.keys)
        for (s in species) {
            val diff = (ci[s] ?: 0) - (cj[s] ?: 0)
            val move = (abs(diff) * num) / den
            if (move <= 0) continue
            if (diff > 0) { bump(next[i], s, -move); bump(next[j], s, move) }
            else { bump(next[j], s, -move); bump(next[i], s, move) }
        }
    }

    private fun bump(map: HashMap<String, Int>, s: String, delta: Int) {
        val v = (map[s] ?: 0) + delta
        if (v <= 0) map.remove(s) else map[s] = v
    }

    companion object {
        val RES = CytoLightField.RES
        val SPAN = CytoLightField.SPAN
        val HALF = CytoLightField.HALF

        /** Peak free-monomer count seeded at a source grid cell (⚙ tunable — the matter carrying
         *  capacity per source, hence the population ceiling). Seeded for each monomer species (a, b). */
        const val MATTER_PEAK = 64

        /** Gaussian radius of the matter clumps (logical units) — decoupled from the (wider) light
         *  [CytoLightField.FALLOFF] so we can make the *nutrient* niches small (and total nutrients low)
         *  without dimming the light field. Total world matter ≈ MATTER_PEAK × 4 sources × π(σ/cellSize)²;
         *  keeping it small is the population cap while the per-cell sim is unoptimised. ⚙ */
        const val MATTER_FALLOFF = 70f

        /** Slow-diffusion rate: per tick, each grid-cell edge moves `⌊|gradient|·DIFFUSE_NUM/DIFFUSE_DEN⌋`
         *  of each species down-gradient. Keep `4·NUM/DEN ≤ 1`. Smaller = slower + coarser settle. ⚙ */
        const val DIFFUSE_NUM = 1
        const val DIFFUSE_DEN = 8

        /** The free monomer species the world is seeded with. */
        val SEED_MONOMERS = listOf("a", "b", "c", "d", "e", "f", "g")

        /**
         * A fresh reservoir: free monomers in a Gaussian bump around each of the 4 light sources, so the
         * good real estate (near light) also has matter. Counts are integer (floored). NB: the Gaussian
         * uses `Float` `exp` — fine for a single-player local sim; for lockstep the seeded grid travels
         * in the serialized snapshot (it is not re-derived per peer), so cross-platform `exp` drift is
         * not conservation-critical.
         */
        fun seeded(): CytoMatterGrid {
            val cells = Array(RES * RES) { HashMap<String, Int>() }
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
                        val map = cells[gy * RES + gx]
                        for (m in SEED_MONOMERS) map[m] = n
                    }
                }
            }
            return CytoMatterGrid(cells)
        }

        /** Reconstruct from saved per-cell maps. */
        fun fromCells(saved: Array<HashMap<String, Int>>): CytoMatterGrid = CytoMatterGrid(saved)

        /** An empty reservoir of the right size (for save decode to fill). */
        fun empty(): CytoMatterGrid = CytoMatterGrid(Array(RES * RES) { HashMap() })

        private fun wrapIndex(i: Int): Int = ((i % RES) + RES) % RES
        private fun wrapDelta(d: Float): Float {
            var x = d
            while (x > HALF) x -= SPAN
            while (x < -HALF) x += SPAN
            return x
        }
    }
}
