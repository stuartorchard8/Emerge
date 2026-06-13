package org.emerge.demo.cyto.sim

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

    companion object {
        val RES = CytoLightField.RES
        val SPAN = CytoLightField.SPAN
        val HALF = CytoLightField.HALF

        /** Peak free-monomer count seeded at a source grid cell (⚙ tunable — the matter carrying
         *  capacity per source, hence base population). Seeded for each monomer species (a, b). Raised
         *  from 64 to support a larger population: enough individuals that a lucky unmodified minority
         *  persists by chance while the population as a whole explores the mutation space. */
        const val MATTER_PEAK = 256

        /** The free monomer species the world is seeded with. */
        val SEED_MONOMERS = listOf("a", "b")

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
            val inv = 1f / (CytoLightField.FALLOFF * CytoLightField.FALLOFF)
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
