package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.MIN_RADIUS
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test

/**
 * Does an organism that moves its body with SPRINGS impart velocity to something it hits, or does the
 * position/velocity decoupling break collisions too?
 *
 * Two scenarios, same reducer:
 *   A) SPRING STRIKER — load the real save, find cell N's welded organism (it breathes via the position
 *      channel: velX~0 but the surface really moves), drop a FREE inert target cell just touching a surface
 *      cell, and watch whether the target gains velocity / drifts away.
 *   B) VELOCITY CONTROL — two free inert cells, the striker given a REAL velocity-channel velocity aimed at
 *      a stationary target; confirms the contact solver transfers momentum normally when the motion is in
 *      the velocity channel.
 *
 * The contrast isolates the decoupling: if B transfers velocity cleanly but A barely moves the target (and
 * what little it does is penetration-driven jitter, not sustained momentum), spring-driven striking is
 * broken by the same root cause as drag.
 *
 *   ./gradlew :apps:cyto:jvmTest --tests "*CollisionChannelProbe*" -Dcollprobe=1 [-Dswimcell=100] [-Dsavefile=...]
 *   -> /tmp/collprobe.txt
 */
class CollisionChannelProbe {
    @Test
    fun run() {
        if (System.getProperty("collprobe") == null) return
        val sb = StringBuilder()
        scenarioA(sb)
        sb.appendLine()
        scenarioB(sb)
        java.io.File("/tmp/collprobe.txt").writeText(sb.toString())
        println(sb)
    }

    // ── A: spring-driven organism vs a free target ──────────────────────────────
    private fun scenarioA(sb: StringBuilder) {
        val path = System.getProperty("savefile") ?: "/home/stu/emerge/platform/desktop-app/cyto-save.bin"
        val seedId = System.getProperty("swimcell")?.toIntOrNull() ?: 100
        val ticks = System.getProperty("collticks")?.toIntOrNull() ?: 10000
        val state = CytoSaveCodec.decode(java.io.File(path).readBytes())
        val cfg = CytoConfig(mutationRateDenom = 0)

        // probe the loaded world to find the rightmost surface cell of cell `seedId`'s cluster
        val w0 = CytoWorld.fromSimState(state)
        fun cluster(start: Int): IntArray {
            val seen = HashSet<Int>(); val st = ArrayDeque<Int>(); st.addLast(start); seen.add(start)
            while (st.isNotEmpty()) { val s = st.removeLast()
                for (k in w0.csr.offset[s] until w0.csr.offset[s + 1]) { val nb = w0.csr.otherSlot[k]; if (nb >= 0 && seen.add(nb)) st.addLast(nb) } }
            return seen.toIntArray()
        }
        val seedSlot = w0.slotOf(seedId)
        val clu = if (seedSlot >= 0) cluster(seedSlot) else { var best = IntArray(0); val vis = BooleanArray(w0.count)
            for (s in 0 until w0.count) { if (vis[s]) continue; val c = cluster(s); for (x in c) vis[x] = true; if (c.size > best.size) best = c }; best }
        // rightmost cell (max x) as the striking face
        var face = clu[0]; for (s in clu) if (w0.posX[s] > w0.posX[face]) face = s
        val faceX = CytoUnits.toLogical(Coord(w0.posX[face])).toDouble()
        val faceY = CytoUnits.toLogical(Coord(w0.posY[face])).toDouble()
        val faceR = CytoUnits.toLogical(Coord(w0.radiusRaw[face].toInt())).toDouble()
        val tgtR = CytoUnits.toLogical(Coord(org.emerge.sim.core.physics.primitives.Frac(MIN_RADIUS.raw).let { CytoUnits.len(it.toFloat()).raw.toInt() })).toDouble()
        // place a FREE inert target just touching the face along +x
        val tgtX = faceX + faceR + tgtR + 0.02
        val tgtY = faceY

        val b = SimBuilder(state)
        val targetId = b.spawnCell(
            pos = CytoUnits.coord2(tgtX.toFloat(), tgtY.toFloat()), vel = Coord2.zero, type = CellType.Collector,
            cytoplasm = emptyMap(), biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = emptyList(),
        )
        val built = b.build()

        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(built)
        val tx0 = run { val s = w.slotOf(targetId.value); CytoUnits.toLogical(Coord(w.posX[s])).toDouble() }
        val ty0 = run { val s = w.slotOf(targetId.value); CytoUnits.toLogical(Coord(w.posY[s])).toDouble() }
        var peakTgtVel = 0.0; var maxDisp = 0.0
        for (t in 1..ticks) {
            w = soa.tick(w, CytoInput.EMPTY)
            val s = w.slotOf(targetId.value); if (s < 0) { sb.appendLine("target died at $t"); break }
            val vx = CytoUnits.toLogical(Coord(w.velX[s])).toDouble(); val vy = CytoUnits.toLogical(Coord(w.velY[s])).toDouble()
            val sp = hypot(vx, vy); if (sp > peakTgtVel) peakTgtVel = sp
            val dx = CytoUnits.toLogical(Coord(w.posX[s])).toDouble() - tx0; val dy = CytoUnits.toLogical(Coord(w.posY[s])).toDouble() - ty0
            val d = hypot(dx, dy); if (d > maxDisp) maxDisp = d
        }
        val sEnd = w.slotOf(targetId.value)
        val netDisp = if (sEnd < 0) Double.NaN else hypot(
            CytoUnits.toLogical(Coord(w.posX[sEnd])).toDouble() - tx0, CytoUnits.toLogical(Coord(w.posY[sEnd])).toDouble() - ty0)

        sb.appendLine("=== A: SPRING STRIKER (save=$path, organism of cell $seedId = ${clu.size} cells, $ticks ticks) ===")
        sb.appendLine("free target dropped at face +x, touching (gap 0.02). The organism breathes via the position channel.")
        sb.appendLine("target peak velocity (velX/Y) : ${f4(peakTgtVel)} cell-diam/tick")
        sb.appendLine("target peak displacement      : ${f4(maxDisp)} cell-diam")
        sb.appendLine("target NET displacement       : ${f4(netDisp)} cell-diam  (sustained push if >> peak jitter)")
    }

    // ── B: real velocity-channel striker vs a free target ───────────────────────
    private fun scenarioB(sb: StringBuilder) {
        val cfg = CytoConfig(mutationRateDenom = 0)
        val approach = 0.08   // cell-diam/tick, a real velocity-channel velocity
        var strikerId = EntityId(-1); var targetId = EntityId(-1)
        val initial = run {
            val b = SimBuilder(org.emerge.sim.core.sim.SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
            strikerId = b.spawnCell(pos = CytoUnits.coord2(0f, 0f), vel = CytoUnits.coord2(approach.toFloat(), 0f),
                type = CellType.Collector, cytoplasm = emptyMap(), biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = emptyList())
            targetId = b.spawnCell(pos = CytoUnits.coord2(1.2f, 0f), vel = Coord2.zero,
                type = CellType.Collector, cytoplasm = emptyMap(), biomass = CytoSeed.STARTER_BIOMASS, logicalRadius = MIN_RADIUS, genome = emptyList())
            b.build()
        }
        val soa = CytoSoaReducer(cfg); var w = CytoWorld.fromSimState(initial)
        val tx0 = run { val s = w.slotOf(targetId.value); CytoUnits.toLogical(Coord(w.posX[s])).toDouble() }
        var peakTgtVel = 0.0; var contacted = -1
        for (t in 1..400) {
            w = soa.tick(w, CytoInput.EMPTY)
            val s = w.slotOf(targetId.value); if (s < 0) break
            val vx = CytoUnits.toLogical(Coord(w.velX[s])).toDouble(); val vy = CytoUnits.toLogical(Coord(w.velY[s])).toDouble()
            val sp = hypot(vx, vy); if (sp > peakTgtVel) peakTgtVel = sp
            if (sp > 0.001 && contacted < 0) contacted = t
        }
        val sEnd = w.slotOf(targetId.value)
        val netDisp = if (sEnd < 0) Double.NaN else abs(CytoUnits.toLogical(Coord(w.posX[sEnd])).toDouble() - tx0)
        sb.appendLine("=== B: VELOCITY-CHANNEL CONTROL (2 free cells, striker vel=${approach}/tick, 400 ticks) ===")
        sb.appendLine("target peak velocity (velX/Y) : ${f4(peakTgtVel)} cell-diam/tick  (first moved at tick $contacted)")
        sb.appendLine("target NET displacement       : ${f4(netDisp)} cell-diam")
        sb.appendLine()
        sb.appendLine("INTERPRETATION: B shows the contact solver DOES transfer velocity when motion is in the velocity channel.")
        sb.appendLine("If A's target barely moves (or only jitters: peak disp >> net disp), spring-driven striking can't")
        sb.appendLine("impart real momentum — the same decoupling that blinds drag also starves collisions of the striker's motion.")
    }

    private fun f4(x: Double) = if (x.isNaN()) "NaN" else (kotlin.math.round(x * 10000) / 10000.0).toString()
}
