package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.math.abs
import kotlin.test.Test

/**
 * Throwaway repro for the rapid-division velocity explosion. Loads a save (default cyto-save.bin), runs the
 * SoA reducer (mutation OFF), and each tick reports cell count, the max |velocity| and max |position| across
 * all cells. The bomb cell's genome divides hard once lit, so over a light orbit the colony bursts — we watch
 * for the tick velocities blow up. Gated: run with
 *   ./gradlew :demos:cyto:jvmTest --tests "*DivisionBugProbe*" -Ddivbug=1 [-Ddivticks=5000] [-Dsavefile=...]
 */
class DivisionBugProbe {
    @Test
    fun run() {
        if (System.getProperty("divbug") == null) return
        val path = System.getProperty("savefile") ?: "/home/stu/emerge/platform/desktop-app/cyto-save.bin"
        val ticks = System.getProperty("divticks")?.toIntOrNull() ?: 5000
        val state = CytoSaveCodec.decode(java.io.File(path).readBytes())
        val soa = CytoSoaReducer(CytoConfig(mutationRateDenom = 0))
        var w = CytoWorld.fromSimState(state)

        val sb = StringBuilder("=== division-bug probe ($path, $ticks ticks) ===\ntick\tcells\tmaxVelRaw\tmaxPosRaw\n")
        var prevCells = state.components.getTable<CytoCellComponent>().asMap().size
        var bursting = false
        for (t in 1..ticks) {
            w = soa.tick(w, CytoInput.EMPTY)
            val s = w.toSimState()
            val cells = s.components.getTable<CytoCellComponent>().asMap()
            val motions = s.components.getTable<MotionComponent>().asMap()
            val transforms = s.components.getTable<TransformComponent>().asMap()
            var maxVel = 0L; var maxPos = 0L
            for (id in cells.keys) {
                motions[id]?.vel?.let { maxVel = maxOf(maxVel, abs(it.x.raw.toLong()), abs(it.y.raw.toLong())) }
                transforms[id]?.pos?.let { maxPos = maxOf(maxPos, abs(it.x.raw.toLong()), abs(it.y.raw.toLong())) }
            }
            var maxDeg = 0
            for (i in 0 until w.count) maxDeg = maxOf(maxDeg, w.csr.degreeOf(i))
            val n = cells.size
            // Report on division bursts, big velocities, or every 100 ticks for baseline.
            val velExplode = maxVel > 10_000_000L   // tune after seeing the baseline; raw vel is Int-bounded
            if (t % 100 == 0 || n != prevCells || (velExplode && !bursting)) {
                sb.appendLine("$t\t$n\t$maxVel\t$maxPos\t$maxDeg")
            }
            if (velExplode && !bursting) { sb.appendLine(">>> VELOCITY EXPLOSION first seen at tick $t (cells=$n)"); bursting = true }
            if (maxVel > 1_500_000_000L) { sb.appendLine(">>> runaway at tick $t (maxVelRaw=$maxVel) — stopping"); break }
            prevCells = n
        }
        java.io.File("/tmp/divbug.txt").writeText(sb.toString())
        println(sb)
    }
}
