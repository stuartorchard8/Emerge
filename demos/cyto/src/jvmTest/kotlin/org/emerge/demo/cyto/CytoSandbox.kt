package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.totalBiomassBonds
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test

/**
 * Throwaway sandbox: seed ONE founder with a hand-authored genome (mutation OFF) at the world origin and
 * watch how it develops over time — population, welds (multicellularity), the `ab` morphogen spread
 * (a proxy for differentiation), and matter conservation. NOT a gated test (no asserts) — a diagnostic.
 *   ./gradlew :demos:cyto:jvmTest --tests "*CytoSandbox*" [-Dsandboxticks=8000]   → /tmp/cytosandbox.txt
 */
class CytoSandbox {
    // Cell 1872 — the hand-authored monster, pulled via InspectCell.
    private val genomeText = """
        Break aa : aa > aab : FormBond c b @15
        Break bb : bb > abb : FormBond c b @10
        Break cb : ab > 900 & aa < 800 & a > ab : FormBond a a
        Break cb : ab < 900 & b > cb & aa < cb & b > ab : FormBond b b
        Break cb : Biomass < 2100 : Convert cb @12
        Break cb : cb > 5000 & bb < 200 & a > 1000 & b > 1000 : Mitosis ab
        Light : Conc(b) > bb : FormBond c b
        Light : aa < 20 & ab < 1000 : FormBond a b @15
        Light : aa > 100 : Repair
    """.trimIndent()

    @Test
    fun run() {
        val ticks = System.getProperty("sandboxticks")?.toIntOrNull() ?: 8000
        // -Dsandboxgenome=<file> runs any GeneCodec-text genome; else the hard-coded cell-1872 monster.
        val genome = System.getProperty("sandboxgenome")?.let { GeneCodec.parse(java.io.File(it).readText()) }
            ?: GeneCodec.parse(genomeText)
        val cfg = CytoConfig(mutationRateDenom = 0)   // mutation OFF — observe the *designed* organism

        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            b.spawnCell(
                pos = CytoUnits.coord2(0f, 0f), vel = Coord2.zero, type = CellType.Collector,
                cytoplasm = CytoSeed.SEED_CYTOPLASM, biomass = CytoSeed.STARTER_BIOMASS,
                logicalRadius = MIN_RADIUS, genome = genome,
            )
            // Abundant raw monomers everywhere (2000 a/b/c per grid cell) so proliferation isn't matter-limited.
            val grid = CytoMatterGrid.empty()
            for (idx in 0 until CytoMatterGrid.RES * CytoMatterGrid.RES) {
                grid.deposit(idx, "a", 2000); grid.deposit(idx, "b", 2000); grid.deposit(idx, "c", 2000)
            }
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }
            b.build()
        }

        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(initial)

        val sb = StringBuilder()
        sb.appendLine("=== cell-1872 sandbox (mutation OFF, $ticks ticks, bare SEED founder) ===")
        sb.appendLine(GeneCodec.serialize(genome))
        sb.appendLine()
        sb.appendLine("tick\tpop\twelds\tatomsΔ\tavgBio\tab min/med/max\taa min/med/max")

        var atoms0 = -1L
        fun report(t: Int, s: SimState) {
            val cells = s.components.getTable<CytoCellComponent>().asMap().values.toList()
            val welds = s.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size } / 2
            val atoms = totalAtoms(s); if (atoms0 < 0) atoms0 = atoms
            val avgBio = if (cells.isEmpty()) 0 else cells.sumOf { totalBiomassBonds(it.biomass) } / cells.size
            sb.appendLine(
                "$t\t${cells.size}\t$welds\t${atoms - atoms0}\t$avgBio\t" +
                    "${spread(cells.map { it.cytoplasm["ab"] ?: 0 })}\t${spread(cells.map { it.cytoplasm["aa"] ?: 0 })}",
            )
        }
        report(0, initial)
        val every = (ticks / 16).coerceAtLeast(1)
        for (t in 1..ticks) {
            w = soa.tick(w, CytoInput.EMPTY)
            if (t % every == 0) report(t, w.toSimState())
        }
        java.io.File("/tmp/cytosandbox.txt").writeText(sb.toString())
        println(sb)
    }

    private fun spread(xs: List<Int>): String {
        if (xs.isEmpty()) return "-/-/-"
        val s = xs.sorted()
        return "${s.first()}/${s[s.size / 2]}/${s.last()}"
    }

    private fun totalAtoms(s: SimState): Long {
        var n = 0L
        for (c in s.components.getTable<CytoCellComponent>().asMap().values) {
            for ((sp, k) in c.cytoplasm) n += sp.length.toLong() * k
            for ((sp, k) in c.biomass) n += sp.length.toLong() * k
        }
        n += s.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid?.totalAtoms() ?: 0L
        return n
    }
}
