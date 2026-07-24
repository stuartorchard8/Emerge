package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.handleableOf
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.totalBiomass
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test

/**
 * Throwaway sandbox: seed ONE founder with a hand-authored genome (mutation OFF) at the origin and watch how
 * it develops — population, body shape, **centre-of-mass drift** (does it swim?), **radius spread** (is it
 * contracting?), and the concentration of up to two watch-species (a determinant / morphogen). NOT a gated
 * test. Properties (all forwarded in build.gradle.kts):
 *   -Dsandboxgenome=<file>   GeneCodec genome (default: cell 1872)
 *   -Dsandboxseed=r:500,b:200 founder cytoplasm (default: CytoSeed.SEED_CYTOPLASM)
 *   -Dsandboxwatch=bb,gb     two species to report counts of (default rr,rg)
 *   -Dsandboxticks=8000
 *   → /tmp/cytosandbox.txt
 */
class CytoSandbox {
    private val genomeText = """
        Break rr : rr > rrg : FormBond b g @15
        Break gg : gg > rgg : FormBond b g @10
        Break bg : rg > 900 & rr < 800 & r > rg : FormBond r r
        Break bg : rg < 900 & g > bg & rr < bg & g > rg : FormBond g g
        Break bg : Biomass < 2100 : Convert bg @12
        Break bg : bg > 5000 & gg < 200 & r > 1000 & g > 1000 : Divide rg
        Light : g > gg : FormBond b g
        Light : rr < 20 & rg < 1000 : FormBond r g @15
        Light : rr > 100 : Repair
    """.trimIndent()

    @Test
    fun run() {
        val ticks = System.getProperty("sandboxticks")?.toIntOrNull() ?: 8000
        val genome = System.getProperty("sandboxgenome")?.let { GeneCodec.parse(java.io.File(it).readText()) }
            ?: GeneCodec.parse(genomeText)
        val seed = System.getProperty("sandboxseed")?.let { s ->
            s.split(",").associate { it.substringBefore(":") to it.substringAfter(":").toInt() }
        } ?: emptyMap()
        val watch = (System.getProperty("sandboxwatch") ?: "rr,rg").split(",")
        val cfg = CytoConfig(mutationRateDenom = 0)   // mutation OFF — observe the *designed* organism

        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            b.spawnCell(
                pos = CytoUnits.coord2(0f, 0f), vel = Coord2.zero, type = CellType.Collector,
                cytoplasm = seed, biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = genome,
            )
            // abundant raw monomers everywhere so growth isn't matter-limited
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(2000)) }
            b.build()
        }

        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(initial)

        val sb = StringBuilder()
        sb.appendLine("=== sandbox (mutation OFF, $ticks ticks, seed=$seed) ===")
        sb.appendLine(GeneCodec.serialize(genome))
        sb.appendLine()
        val h = handleableOf(genome)
        sb.appendLine("watch species $watch — " + watch.joinToString(" | ") {
            "$it: canDiffuse=${h.canDiffuse(SpeciesRegistry.id(it))} canHold=${h.canHold(SpeciesRegistry.id(it))}"
        })
        sb.appendLine()
        sb.appendLine("tick\tpop\tCOM(x,y)\tdrift\tshape WxH\torgOff\t${watch[0]} count\t${watch.getOrElse(1){"-"}} Conc")

        var com0: Pair<Double, Double>? = null
        fun report(t: Int, s: SimState) {
            val cellMap = s.components.getTable<CytoCellComponent>().asMap()
            val cells = cellMap.values.toList()
            val transforms = s.components.getTable<TransformComponent>().asMap()
            val xs = cellMap.keys.mapNotNull { transforms[it]?.let { tr -> CytoUnits.toLogical(tr.pos.x).toDouble() } }
            val ys = cellMap.keys.mapNotNull { transforms[it]?.let { tr -> CytoUnits.toLogical(tr.pos.y).toDouble() } }
            val shape = if (xs.isEmpty()) "-" else "${(xs.max() - xs.min()).toInt()}x${(ys.max() - ys.min()).toInt()}"
            val com = if (xs.isEmpty()) 0.0 to 0.0 else xs.average() to ys.average()
            if (com0 == null) com0 = com
            val drift = kotlin.math.hypot(com.first - com0!!.first, com.second - com0!!.second)
            // organizer offset: distance of the highest-watch[0] (determinant) cell from the body COM,
            // normalised by the body half-extent (0 = central → radial gradient; ~1 = edge → lateral gradient).
            val orgId = cellMap.entries.maxByOrNull { it.value.cytoplasm[watch[0]] ?: 0 }?.key
            val orgPos = orgId?.let { transforms[it]?.let { tr -> CytoUnits.toLogical(tr.pos.x).toDouble() to CytoUnits.toLogical(tr.pos.y).toDouble() } }
            val halfExtent = (if (xs.isEmpty()) 1.0 else maxOf(xs.max() - xs.min(), ys.max() - ys.min())).coerceAtLeast(1.0) / 2
            val orgOff = if (orgPos == null) 0.0 else kotlin.math.hypot(orgPos.first - com.first, orgPos.second - com.second) / halfExtent
            fun conc(sp: String) = spread(cells.map { c -> val bio = totalBiomass(c.biomass); if (bio <= 0) 0 else (c.cytoplasm[sp] ?: 0) * 1000 / bio })
            sb.appendLine("$t\t${cells.size}\t(${com.first.toInt()},${com.second.toInt()})\t${(drift * 100).toInt() / 100.0}\t$shape\t${(orgOff * 100).toInt() / 100.0}\t${conc(watch[0])}\t${if (watch.size > 1) conc(watch[1]) else "-"}")
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
}
