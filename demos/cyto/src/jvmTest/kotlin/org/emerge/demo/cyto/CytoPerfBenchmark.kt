package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Headless per-phase profiler for the Cyto pipeline at increasing colony sizes.
 *
 * Reproduces the "stem fed by life support" benchmark as a *settled* connected colony:
 * a square mesh of cells, each spring-joined to its right/down neighbour, with ~1-in-20
 * Support cells feeding energy in (so nothing starves during the window). This isolates
 * the steady per-tick cost — chemistry, spring solve, connection maintenance, broadphase,
 * integration — and how each scales with cell count.
 *
 * Caveat: a settled colony under-represents division transients (overlap → contact →
 * repulsion → weld), which spike the contact/lifecycle phases during active growth. The
 * steady costs measured here are what every tick pays regardless.
 *
 * Run: ./gradlew :demos:cyto:jvmTest --tests '*CytoPerfBenchmark*' -i
 */
class CytoPerfBenchmark {

    private val cfg = CytoConfig()

    @Test
    fun profileColonyScaling() {
        // Heavy (~30s); off in normal runs. Enable with CYTO_BENCH=1 ./gradlew :demos:cyto:jvmTest -i
        if (System.getenv("CYTO_BENCH") == null) return
        val sizes = intArrayOf(250, 500, 1000, 2000, 4000)
        println()
        println("Cyto pipeline profile — 60fps budget = 16.667 ms/tick (sequential, single thread)")
        for (n in sizes) profileAt(n)
        println()
    }

    private fun profileAt(targetCells: Int) {
        val profiler = PipelineProfiler()
        val reducer = CytoReducer(profiler)
        var state = buildColony(targetCells)
        val input = mapOf(PlayerId(0) to CytoInput())

        repeat(WARMUP) { state = reducer.reduce(cfg, state, input) }
        profiler.reset()
        repeat(WINDOW) {
            val t0 = System.nanoTime()
            state = reducer.reduce(cfg, state, input)
            profiler.recordTick(System.nanoTime() - t0)
        }

        val cells = state.components.getTable<CytoCellComponent>().asMap().size
        val springEntries = state.components.getTable<SpringConstraintComponent>().asMap()
        val connectionEnds = springEntries.values.sumOf { it.springs.size }
        val report = profiler.report()

        val tickMs = report.tickAvgNanos / 1e6
        val p95Ms = report.tickP95Nanos / 1e6
        val verdict = if (p95Ms <= 16.667) "OK (<60fps budget)" else "OVER BUDGET"
        println()
        println("── cells=$cells  springs=${connectionEnds / 2}  (target $targetCells) ─────────────────")
        println("   tick: avg ${ms(report.tickAvgNanos)}  p95 ${ms(report.tickP95Nanos)}  max ${ms(report.tickMaxNanos)}   → $verdict")
        println("   phase                 avg ms     share")
        for (p in report.phases.sortedByDescending { it.avgNanos }) {
            println("   ${p.name.padEnd(20)} ${ms(p.avgNanos).padStart(8)}   ${pct(p.sharePercent)}")
        }
        // touch tickMs/p95Ms so they read as used even if asserts are added later
        check(tickMs >= 0 && p95Ms >= 0)
    }

    /** Square connected mesh of [targetCells] cells, ~1-in-20 Support, rest Blank. */
    private fun buildColony(targetCells: Int): SimState {
        val builder = SimBuilder(SimState())
        val side = ceil(sqrt(targetCells.toDouble())).toInt()
        val spacing = 2.0f // logical diameter for radius-1 cells: neighbours just touch
        val grid = arrayOfNulls<EntityId>(side * side)
        var placed = 0
        for (row in 0 until side) {
            for (col in 0 until side) {
                if (placed >= targetCells) break
                val x = (col - side / 2) * spacing
                val y = (row - side / 2) * spacing
                val support = placed % 20 == 0
                grid[row * side + col] = builder.spawnCell(
                    pos = CytoUnits.coord2(x, y),
                    vel = Coord2.zero,
                    type = if (support) CellType.Support else CellType.Blank,
                    chemicals = mapOf("energy" to 8f),
                    logicalRadius = 1f,
                )
                placed++
            }
        }
        for (row in 0 until side) {
            for (col in 0 until side) {
                val id = grid[row * side + col] ?: continue
                if (col + 1 < side) grid[row * side + col + 1]?.let { addSpring(builder, id, it, cfg) }
                if (row + 1 < side) grid[(row + 1) * side + col]?.let { addSpring(builder, id, it, cfg) }
            }
        }
        return builder.build()
    }

    private fun ms(nanos: Long): String {
        val v = nanos / 1e6
        val scaled = (v * 1000).toLong()
        return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
    }

    private fun pct(p: Double): String {
        val scaled = (p * 10).toLong()
        return "${scaled / 10}.${scaled % 10}%"
    }

    companion object {
        private const val WARMUP = 250
        private const val WINDOW = 200
    }
}
