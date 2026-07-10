package org.emerge.demo.cyto.sim.soa

import com.sun.management.ThreadMXBean
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.sim.core.ecs.PipelineProfiler
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Throwaway perf probe (NOT a gate) — grow a realistic colony, then profile each tick phase
 * for time and per-thread allocation bytes. Run with:
 *   ./gradlew :apps:cyto:jvmTest --tests "*CytoBench*" -i
 */
class CytoBench {

    @Test
    fun reproFreezeColony() {
        if (System.getProperty("cytorepro") == null) return
        // A dense colony of normal cells + ONE giant (huge-biomass) cell among them — does the giant's radius
        // coarsen the broadphase so contacts goes O(n²)?
        fun build(giant: Boolean): CytoWorld {
            val b = org.emerge.sim.core.sim.SimBuilder(org.emerge.sim.core.sim.SimState(randomSeed = 1))
            b.update<org.emerge.demo.cyto.sim.CytoMatterGridComponent>(org.emerge.demo.cyto.sim.GRID_SINGLETON) {
                org.emerge.demo.cyto.sim.CytoMatterGridComponent(org.emerge.demo.cyto.sim.CytoMatterField.empty())
            }
            val side = 30
            for (gy in 0 until side) for (gx in 0 until side) {
                val big = giant && gx == side / 2 && gy == side / 2
                b.spawnCell(
                    org.emerge.demo.cyto.sim.CytoUnits.coord2(-7.5f + gx * 0.5f, -7.5f + gy * 0.5f),
                    org.emerge.sim.core.physics.primitives.Coord2.zero, org.emerge.demo.cyto.cells.CellType.Collector,
                    biomass = mapOf("rg" to if (big) 30_000_000 else 4000),
                    logicalRadius = if (big) org.emerge.sim.core.physics.primitives.Frac(40, 1) else org.emerge.demo.cyto.sim.MIN_RADIUS,
                )
            }
            return CytoWorld.fromSimState(b.build())
        }
        val sb = StringBuilder()
        for (giant in listOf(false, true)) {
            val prof = PipelineProfiler()
            val soa = CytoSoaReducer(CytoConfig(mutationRateDenom = 0), profiler = prof)
            var w = build(giant)
            repeat(5) { w = soa.tick(w, CytoInput.EMPTY) }   // warm
            prof.reset()
            repeat(20) { w = soa.tick(w, CytoInput.EMPTY) }
            val r = prof.report()
            sb.appendLine("giant=$giant cells=${w.count} tickAvg=%.1f ms".format(r.tickAvgNanos / 1e6))
            for (p in r.phases.sortedByDescending { it.sharePercent }) sb.appendLine("  %-12s %8.1f us".format(p.name, p.avgNanos / 1e3))
        }
        java.io.File("/tmp/cytorepro.txt").writeText(sb.toString())
    }

    @Test
    fun profile() {
        // Gated off by default (grows 22k ticks, ~40s) so a normal `jvmTest` run skips it. Enable with
        // `-Dcytobench=1`; results land in /tmp/cytobench_out.txt.
        if (System.getProperty("cytobench") == null) return
        val cfg = CytoConfig()   // live config (mutationRateDenom = 100_000)
        val soa = CytoSoaReducer(cfg)
        // -Dcytocells=N seeds N distributed founders directly (to profile the parallel crossover at a
        // population the moving-light carrying capacity won't reach by growth); otherwise grow one founder.
        // -Dcytosave=PATH loads a saved colony (e.g. a welded multicellular scenario) instead of
        // seeding/growing — the realistic way to profile welds/springs/connections.
        val savePath = System.getProperty("cytosave")
        val seedN = System.getProperty("cytocells")?.toIntOrNull()
        var w = when {
            savePath != null -> CytoWorld.fromSimState(
                org.emerge.demo.cyto.CytoSaveCodec.decode(java.io.File(savePath).readBytes()))
            seedN != null -> CytoWorld.fromSimState(seededColony(seedN))
            else -> CytoWorld.fromSimState(createCytoInitialState())
        }
        val grow = if (seedN != null) 200 else if (savePath != null) 400 else 22000   // save: brief settle
        repeat(grow) {
            w = soa.tick(w, CytoInput.EMPTY)
            if (it % 2000 == 0) java.io.File("/tmp/cytobench_grow.txt").appendText("tick=$it cells=${w.count}\n")
        }

        val executor = org.emerge.sim.core.ecs.ParallelExecutor()
        val sb = StringBuilder()
        sb.appendLine("grewTicks=$grow cells=${w.count} cores=${executor.parallelism}")
        // Each variant runs from a FRESH deep copy of the same snapshot, so they see identical states (SEQ
        // and PAR are bit-identical, so their warmup leaves both copies in the same state) — no confounding
        // from the world evolving between the two runs.
        val snap = w.toSimState()
        // -Dcytovariant=seq|par runs only one variant (separate fresh JVM per variant) to rule out
        // cross-variant contamination (JIT/GC/CPU-clock state); default runs both back-to-back.
        val variant = System.getProperty("cytovariant")
        if (variant == null || variant == "seq") profileVariant("SEQ ", cfg, null, CytoWorld.fromSimState(snap), sb)
        if (variant == null || variant == "par") profileVariant("PAR ", cfg, executor, CytoWorld.fromSimState(snap), sb)
        executor.close()
        java.io.File("/tmp/cytobench_out.txt").writeText(sb.toString())
    }

    /** A SimState with [count] autotroph founders spread on a grid across the logical torus, so they land
     *  in many distinct grid cells (with a few co-located) — a realistic high-N spatial distribution for the
     *  broadphase + biology grid-cell grouping, reached directly instead of via slow growth. */
    private fun seededColony(count: Int): org.emerge.sim.core.sim.SimState {
        val builder = org.emerge.sim.core.sim.SimBuilder(
            org.emerge.sim.core.sim.SimState(randomSeed = 0x9E3779B97F4A7C15uL.toLong()))
        val side = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        // -Dcytospread=1 tiles the founders evenly across the full torus (spacing = torus/side) instead of
        // the fixed 18-unit spacing, which at high N wraps many times over the small (CELLS_PER_AXIS=32)
        // torus and clumps cells — starving the grid-cell parallel partitioner. Even tiling is the
        // best-case spread for measuring parallel-partition potential.
        val spacing = if (System.getProperty("cytospread") != null)
            (org.emerge.demo.cyto.sim.CytoUnits.CELLS_PER_AXIS.toFloat() / side) else 18f
        val origin = -side * spacing / 2f
        var made = 0
        outer@ for (gy in 0 until side) for (gx in 0 until side) {
            if (made >= count) break@outer
            builder.spawnCell(
                pos = org.emerge.demo.cyto.sim.CytoUnits.coord2(origin + gx * spacing, origin + gy * spacing),
                vel = org.emerge.sim.core.physics.primitives.Coord2.zero,
                type = org.emerge.demo.cyto.cells.CellType.Collector,
                biomass = org.emerge.demo.cyto.sim.CytoSeed.STARTER_BIOMASS,
                logicalRadius = org.emerge.demo.cyto.sim.MIN_RADIUS,
            )
            made++
        }
        builder.update<org.emerge.demo.cyto.sim.CytoMatterGridComponent>(org.emerge.demo.cyto.sim.GRID_SINGLETON) {
            org.emerge.demo.cyto.sim.CytoMatterGridComponent(org.emerge.demo.cyto.sim.CytoMatterField.seededUniform(org.emerge.demo.cyto.sim.CytoSeed.MATTER_UNIFORM_LEVEL))
        }
        return builder.build()
    }

    private fun profileVariant(
        tag: String, cfg: CytoConfig, executor: org.emerge.sim.core.ecs.ParallelExecutor?,
        start: CytoWorld, sb: StringBuilder,
    ) {
        val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
        tmx.isThreadAllocatedMemoryEnabled = true
        val tid = Thread.currentThread().id
        val profiler = PipelineProfiler()
        // Force the parallel path on at this N for the PAR run (threshold 2); SEQ has no executor.
        // -Dcytospringthresh=N sets the spring-solver parallel threshold (default 2048 = springs sequential,
        // to isolate the biology effect). Set it low (e.g. 2) to also parallelise the spring solve and size it.
        val springThresh = System.getProperty("cytospringthresh")?.toIntOrNull() ?: 2048
        val bioProf = org.emerge.demo.cyto.sim.BioProfile()
        val r0 = CytoSoaReducer(cfg, executor = executor, profiler = profiler,
            springParallelThreshold = springThresh,
            bioParallelThreshold = if (executor != null) 2 else Int.MAX_VALUE,
            bioProfile = bioProf)
        var w = start
        repeat(200) { w = r0.tick(w, CytoInput.EMPTY) }   // warmup
        profiler.reset()
        bioProf.reset()
        val measure = 600
        val allocStart = tmx.getThreadAllocatedBytes(tid)
        repeat(measure) {
            val t0 = System.nanoTime()
            w = r0.tick(w, CytoInput.EMPTY)
            profiler.recordTick(System.nanoTime() - t0)
        }
        val allocPerTick = (tmx.getThreadAllocatedBytes(tid) - allocStart) / measure
        val r = profiler.report()
        sb.appendLine("$tag tick avg=%.2f ms p50=%.2f p95=%.2f  per-cell=%.3f us  alloc(main)=%.2f MB".format(
            r.tickAvgNanos / 1e6, r.tickP50Nanos / 1e6, r.tickP95Nanos / 1e6, r.tickAvgNanos / 1e3 / w.count, allocPerTick / 1e6))
        val byName = r.phases.associateBy { it.name }
        fun ph(p: String) = (byName[p]?.avgNanos ?: 0) / 1e3
        sb.appendLine("$tag phases us: biology=%.0f contacts=%.0f forces=%.0f connections=%.0f interact=%.0f lifecycle=%.0f integrate=%.0f".format(
            ph("biology"), ph("contacts"), ph("forces"), ph("connections"), ph("interact"), ph("lifecycle"), ph("integrate")))
        sb.appendLine("$tag bio-sub us: build=%.0f internalTouching=%.0f quanta=%.0f genes=%.0f exchange=%.0f diffuse=%.0f finish=%.0f writeback=%.0f".format(
            ph("bio:build"), ph("bio:internalTouching"), ph("bio:quanta"), ph("bio:genes"),
            ph("bio:exchange"), ph("bio:diffuse"), ph("bio:finish"), ph("bio:writeback")))
        sb.appendLine("$tag lifecycle-sub us: toSim=%.0f update=%.0f fromSim=%.0f".format(
            ph("lc:toSim"), ph("lc:update"), ph("lc:fromSim")))
        // Post-bio systems are profiled per-class as `force:<SystemName>`; the last (integrate) as
        // `forces+integrate`. Springs only exist in a WELDED colony (grown from a founder, not seeded).
        sb.appendLine("$tag postbio us: connections=%.0f grab=%.0f drag=%.0f springSolve=%.0f forces+integrate=%.0f".format(
            ph("force:ConnectionsSystem"), ph("force:GrabSystem"), ph("force:DragSystem"),
            ph("force:SpringSolveSystem"), ph("forces+integrate")))
        val bt = bioProf.ticks.coerceAtLeast(1)
        sb.appendLine("$tag exch-pass us: pass0(serial)=%.0f pass1(par)=%.0f pass2(par)=%.0f".format(
            bioProf.exchPass0Nanos / 1e3 / bt, bioProf.exchPass1Nanos / 1e3 / bt, bioProf.exchPass2Nanos / 1e3 / bt))
    }
}
