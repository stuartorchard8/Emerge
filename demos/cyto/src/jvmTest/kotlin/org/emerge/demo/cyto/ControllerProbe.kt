package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
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
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.hypot
import kotlin.test.Test

/**
 * Locomotion-controller bench. Grow a genome from a cc-seeded founder (abundant matter, so the size cap is
 * the GENOME's, not resources), then ask the directional-swimmer questions:
 *   - does the body cap its size? (population curve plateau)
 *   - does it contract? (mean-radius temporal amplitude)
 *   - is the organizer off-centre? (lateralised gradient → a bend, not a symmetric breath)
 *   - does it swim, and how STRAIGHT? (net COM displacement + straightness = net/path over the late window)
 *
 *   ./gradlew :demos:cyto:jvmTest --tests "*ControllerProbe*" -Dctrl=1 \
 *      -Dctrlgenome=/abs/path.gene [-Dctrlseed=cc:200,a:500] [-Dctrlticks=40000]
 *   -> /tmp/ctrlprobe.txt
 */
class ControllerProbe {
    @Test
    fun run() {
        if (System.getProperty("ctrl") == null) return
        val ticks = System.getProperty("ctrlticks")?.toIntOrNull() ?: 40000
        val genome = GeneCodec.parse(java.io.File(System.getProperty("ctrlgenome")!!).readText())
        val seed = (System.getProperty("ctrlseed") ?: "cc:200,a:500").split(",")
            .associate { it.substringBefore(":") to it.substringAfter(":").toInt() }

        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            b.spawnCell(pos = CytoUnits.coord2(0f, 0f), vel = Coord2.zero, type = CellType.Collector,
                cytoplasm = seed, biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = genome)
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(4000)) }
            b.build()
        }

        val soa = CytoSoaReducer(CytoConfig(mutationRateDenom = 0))
        var w = CytoWorld.fromSimState(initial)

        val n = ticks + 1
        val pop = IntArray(n); val comX = DoubleArray(n); val comY = DoubleArray(n)
        val meanR = DoubleArray(n); val orgOff = DoubleArray(n)

        fun sample(t: Int) {
            val c = w.count
            pop[t] = c
            if (c == 0) return
            var mTot = 0.0; var cx = 0.0; var cy = 0.0; var rSum = 0.0
            for (i in 0 until c) {
                val m = w.mass[i].toDouble()
                cx += m * CytoUnits.toLogical(Coord(w.posX[i])).toDouble()
                cy += m * CytoUnits.toLogical(Coord(w.posY[i])).toDouble()
                mTot += m; rSum += Frac(w.cell.logicalRadius[i]).toFloat().toDouble()
            }
            cx /= mTot; cy /= mTot; comX[t] = cx; comY[t] = cy; meanR[t] = rSum / c
            // organizer offset: distance of the highest-cc cell from COM / body half-extent (0 central, ~1 edge)
            var orgI = 0; var maxCc = -1
            var minx = Double.MAX_VALUE; var maxx = -Double.MAX_VALUE; var miny = Double.MAX_VALUE; var maxy = -Double.MAX_VALUE
            val ccId = SpeciesRegistry.id("bb")
            for (i in 0 until c) {
                val cc = w.cell.cytoplasm[i]?.count(ccId) ?: 0
                if (cc > maxCc) { maxCc = cc; orgI = i }
                val x = CytoUnits.toLogical(Coord(w.posX[i])).toDouble(); val y = CytoUnits.toLogical(Coord(w.posY[i])).toDouble()
                if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y
            }
            val half = (maxOf(maxx - minx, maxy - miny) / 2).coerceAtLeast(1.0)
            val ox = CytoUnits.toLogical(Coord(w.posX[orgI])).toDouble(); val oy = CytoUnits.toLogical(Coord(w.posY[orgI])).toDouble()
            orgOff[t] = hypot(ox - cx, oy - cy) / half
        }

        sample(0)
        for (t in 1..ticks) { w = soa.tick(w, CytoInput.EMPTY); sample(t); if (pop[t] == 0) break }

        val sb = StringBuilder()
        sb.appendLine("=== controller probe (${System.getProperty("ctrlgenome")}, seed=$seed, $ticks ticks) ===")
        sb.appendLine(GeneCodec.serialize(genome))
        sb.appendLine()
        sb.appendLine("tick\tpop\tCOM(x,y)\tmeanR\torgOff")
        val every = (ticks / 20).coerceAtLeast(1)
        for (t in 0..ticks step every) sb.appendLine("$t\t${pop[t]}\t(${f(comX[t])},${f(comY[t])})\t${f(meanR[t])}\t${f(orgOff[t])}")

        // late window = last portion (after growth has plateaued, so drift = locomotion not growth-shift)
        val w0 = (ticks * 6) / 10
        var rlo = Double.MAX_VALUE; var rhi = -Double.MAX_VALUE
        for (t in w0..ticks) { if (meanR[t] < rlo) rlo = meanR[t]; if (meanR[t] > rhi) rhi = meanR[t] }
        var path = 0.0; for (t in w0 + 1..ticks) path += hypot(comX[t] - comX[t - 1], comY[t] - comY[t - 1])
        val net = hypot(comX[ticks] - comX[w0], comY[ticks] - comY[w0])
        val straight = if (path > 0) net / path else 0.0
        val capped = pop[ticks] > 0 && pop[ticks] == pop[w0]
        var orgMean = 0.0; for (t in w0..ticks) orgMean += orgOff[t]; orgMean /= (ticks - w0 + 1)

        sb.appendLine()
        sb.appendLine("=== VERDICT (late window ticks $w0..$ticks) ===")
        sb.appendLine("final population: ${pop[ticks]}   size-capped over window: $capped (pop $w0=${pop[w0]} → $ticks=${pop[ticks]})")
        sb.appendLine("contraction mean-radius amplitude: ${f4(rhi - rlo)}")
        sb.appendLine("organizer offset (mean): ${f(orgMean)}   (0=central/symmetric breath, ~1=lateral/bend)")
        sb.appendLine("NET COM drift over window: ${f(net)} cell-diam   path ${f(path)}   STRAIGHTNESS ${f4(straight)} (1=straight line)")
        java.io.File("/tmp/ctrlprobe.txt").writeText(sb.toString())
        println(sb)
    }

    private fun f(x: Double) = (kotlin.math.round(x * 100) / 100.0).toString()
    private fun f4(x: Double) = (kotlin.math.round(x * 10000) / 10000.0).toString()
}
