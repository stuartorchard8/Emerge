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
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test

/**
 * Tests the hypothesis: do DEGENERATE welds (a weld A–C with a common welded neighbour B collinear BETWEEN
 * them — the "weld through the middle cell") actually reach a high over-stretch ratio, or do they sit at low
 * stretch because the compressed core squashes B so A and C stay close? Mirrors the reducer's own stretch
 * math (CytoSoaReducer:419-436): rest = rA+rC (current radii), stretch = dist - rest, breakDist = 2.0·rest,
 * ratio = stretch/breakDist (cubic over-stretch damage → break-in-one-tick at ratio ≥ 1).
 *
 * Reports, sampled over the run, the ratio distribution for degenerate welds vs all other welds — and the
 * MAX legit ratio (how stretched honest welds get under contraction). If degenerates sit LOW and overlap the
 * legit band, stretch is useless as a signal and proposals 1 & 2 (both stretch-based) are dead.
 */
class DegenerateWeldProbe {
    private val pulser = GeneCodec.parse(
        """
        Break gg : Biomass < 4000 : Convert gg @15
        Light : gg < 8000 & gb < 1000 : FormBond g g
        Break gb : gg < gb & gg < 1000 : FormBond g r @6
        Break gr : gb < gr & gb < 1000 : FormBond g g @6
        Break gg : gr < gg & gr < 1000 : FormBond g b @6
        Break gg : gr < gg & gr < 1000 & gg > 6000 : Contract @15
        Break gg : gr < gg & gr < 1000 & gg > 21500 : Mitosis across r @15
        Break gg : gr < gg & gr < 1000 : Repair @15
        """.trimIndent()
    )

    private val COS_BETWEEN = -0.5   // angle A–B–C > 120° ⇒ B is ~between A and C (collinear, degenerate)

    @Test
    fun run() {
        val ticks = 5000; val n = 37
        val cfg = CytoConfig(mutationRateDenom = 0)
        val pts = mutableListOf(0f to 0f); var r = 1
        while (pts.size < n) { val c = 6 * r; for (k in 0 until c) { if (pts.size >= n) break
            val a = 2.0 * Math.PI * k / c; pts.add((r * 0.5 * Math.cos(a)).toFloat() to (r * 0.5 * Math.sin(a)).toFloat()) }; r++ }
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            for ((x, y) in pts) b.spawnCell(pos = CytoUnits.coord2(x, y), vel = Coord2.zero, type = CellType.Collector,
                cytoplasm = mapOf("g" to 2000, "gg" to 20000), biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = pulser)
            b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(2000)) }
            b.build()
        }
        val soa = CytoSoaReducer(cfg); var w = CytoWorld.fromSimState(initial)
        // bucket weld over-stretch ratio by how collinear its most-collinear common neighbour is (minCos):
        // <-0.7 (very collinear, "through cell"), -0.7..-0.4, -0.4..0 (open triangle), >=0 / none.
        val b1 = ArrayList<Double>(); val b2 = ArrayList<Double>(); val b3 = ArrayList<Double>(); val b4 = ArrayList<Double>()
        var worstCos = 1.0; var worst: String? = null
        for (t in 1..ticks) {
            w = soa.tick(w, CytoInput.EMPTY)
            if (t < 1000 || t % 50 != 0) continue
            val s = w.toSimState()
            val tr = s.components.getTable<TransformComponent>().asMap()
            val col = s.components.getTable<ColliderComponent>().asMap()
            val spr = s.components.getTable<SpringConstraintComponent>().asMap()
            fun px(id: EntityId) = CytoUnits.toLogical(tr.getValue(id).pos.x).toDouble()
            fun py(id: EntityId) = CytoUnits.toLogical(tr.getValue(id).pos.y).toDouble()
            fun rad(id: EntityId) = CytoUnits.toLogical(col.getValue(id).radius).toDouble()
            val adj = spr.mapValues { e -> e.value.springs.map { it.other }.toSet() }
            for ((a, comp) in spr) for (sp in comp.springs) {
                val c = sp.other; if (a.value >= c.value) continue
                if (!tr.containsKey(a) || !tr.containsKey(c) || !col.containsKey(a) || !col.containsKey(c)) continue
                val dist = kotlin.math.hypot(px(a) - px(c), py(a) - py(c))
                val rest = rad(a) + rad(c); val ratio = if (rest > 0) (dist - rest) / (2.0 * rest) else 0.0
                var minCos = 1.0
                for (bC in (adj[a] ?: emptySet()).intersect(adj[c] ?: emptySet())) { if (!tr.containsKey(bC)) continue
                    val bax = px(a) - px(bC); val bay = py(a) - py(bC); val bcx = px(c) - px(bC); val bcy = py(c) - py(bC)
                    val la = kotlin.math.hypot(bax, bay); val lc = kotlin.math.hypot(bcx, bcy)
                    if (la > 0 && lc > 0) minCos = minOf(minCos, (bax * bcx + bay * bcy) / (la * lc)) }
                when { minCos < -0.7 -> b1; minCos < -0.4 -> b2; minCos < 0.0 -> b3; else -> b4 }.add(ratio)
                if (minCos < worstCos) { worstCos = minCos; worst = "cos=${"%.2f".format(minCos)} (angle ${"%.0f".format(Math.toDegrees(Math.acos(minCos.coerceIn(-1.0,1.0))))}°) ratio=${"%.2f".format(ratio)} dist=${"%.3f".format(dist)} rest=${"%.3f".format(rest)}" }
            }
        }
        fun stat(xs: List<Double>): String { if (xs.isEmpty()) return "none"; val z = xs.sorted()
            return "n=${z.size} min=${"%.2f".format(z.first())} med=${"%.2f".format(z[z.size/2])} p90=${"%.2f".format(z[(z.size*9/10).coerceAtMost(z.size-1)])} max=${"%.2f".format(z.last())}" }
        val out = StringBuilder()
        out.appendLine("=== degenerate-weld stretch (pulser, n=$n, ${ticks} ticks; ratio=stretch/breakDist, break at ratio≥1) ===")
        out.appendLine("weld over-stretch ratio bucketed by collinearity of most-collinear common neighbour:")
        out.appendLine("  cos<-0.7 (>134°, THROUGH-CELL): ${stat(b1)}")
        out.appendLine("  -0.7..-0.4 (114-134°)         : ${stat(b2)}")
        out.appendLine("  -0.4..0   (90-114°, open tri) : ${stat(b3)}")
        out.appendLine("  >=0 / no common nbr           : ${stat(b4)}")
        out.appendLine("most-collinear weld seen anywhere: ${worst ?: "none"}")
        java.io.File("/tmp/cytodegen.txt").writeText(out.toString())
        println(out)
    }

    /** Construct the exact degeneracy: A–B–C collinear, ALL THREE welded (the A–C chord passes through B).
     *  Symmetry keeps B between A and C. Repair on. Sweeps the compression-stiffness multiple m and reads the
     *  SETTLED chord geometry — confirming |AC| (and the over-stretch ratio) rises with m as predicted.
     *  (No perturbation: a symmetric triad stays collinear.) */
    @Test
    fun constructedThroughCellWeld() {
        val repair = GeneCodec.parse("Break gg : gg > 0 : Repair @15")   // steady repair energy from stored gg
        val out = StringBuilder()
        out.appendLine("=== constructed A–B–C collinear, chord A–C through B; settled state at tick 3000 ===")
        out.appendLine("m (compStiffMul)\tpredicted ratio m/(2(m+2))\tmeasured ratio\t|AC|\trestAC\tangleB°\talive")
        for (m in listOf(1, 3, 5)) {
            val cfg = CytoConfig(mutationRateDenom = 0, weldCompressionStiffnessMultiple = m)
            var idA = EntityId(-1); var idB = EntityId(-1); var idC = EntityId(-1)
            val initial = run {
                val b = SimBuilder(SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
                fun cell(x: Float) = b.spawnCell(pos = CytoUnits.coord2(x, 0f), vel = Coord2.zero, type = CellType.Collector,
                    cytoplasm = mapOf("gg" to 100000), biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = repair)
                idA = cell(-0.5f); idB = cell(0f); idC = cell(0.5f)
                addSpring(b, idA, idB, cfg); addSpring(b, idB, idC, cfg); addSpring(b, idA, idC, cfg)   // chord A–C through B
                b.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterField.seededUniform(2000)) }
                b.build()
            }
            val soa = CytoSoaReducer(cfg); var w = CytoWorld.fromSimState(initial)
            repeat(3000) { w = soa.tick(w, CytoInput.EMPTY) }
            val s = w.toSimState()
            val tr = s.components.getTable<TransformComponent>().asMap()
            val col = s.components.getTable<ColliderComponent>().asMap()
            val spr = s.components.getTable<SpringConstraintComponent>().asMap()
            fun px(id: EntityId) = CytoUnits.toLogical(tr.getValue(id).pos.x).toDouble()
            fun py(id: EntityId) = CytoUnits.toLogical(tr.getValue(id).pos.y).toDouble()
            fun rad(id: EntityId) = CytoUnits.toLogical(col.getValue(id).radius).toDouble()
            val alive = spr[idA]?.springs?.any { it.other == idC } == true
            val predicted = m.toDouble() / (2.0 * (m + 2))
            if (!tr.containsKey(idA) || !tr.containsKey(idC) || !tr.containsKey(idB)) { out.appendLine("$m\t${"%.2f".format(predicted)}\tcell gone"); continue }
            val dist = kotlin.math.hypot(px(idA) - px(idC), py(idA) - py(idC))
            val rest = rad(idA) + rad(idC); val ratio = (dist - rest) / (2.0 * rest)
            val bax = px(idA) - px(idB); val bay = py(idA) - py(idB); val bcx = px(idC) - px(idB); val bcy = py(idC) - py(idB)
            val cos = (bax * bcx + bay * bcy) / (kotlin.math.hypot(bax, bay) * kotlin.math.hypot(bcx, bcy))
            out.appendLine("$m\t${"%.2f".format(predicted)}\t\t${"%.2f".format(ratio)}\t${"%.3f".format(dist)}\t${"%.3f".format(rest)}\t${"%.0f".format(Math.toDegrees(Math.acos(cos.coerceIn(-1.0,1.0))))}\t$alive")
        }
        java.io.File("/tmp/cytodegen2.txt").writeText(out.toString())
        println(out)
    }
}
