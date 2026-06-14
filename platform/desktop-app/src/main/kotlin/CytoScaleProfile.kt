package org.emerge.desktop

import org.emerge.demo.cyto.CytoSaveCodec
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoLightField
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import java.io.File

/**
 * Population-scaling profiler for the Cyto reducer: answers "which phase dominates, and how does
 * per-tick cost + allocation grow, as the population climbs toward the large-N end goal" — the data
 * needed to choose between an SoA re-port, a chemistry-representation rework, and parallelism.
 *
 * A real save plateaus at its carrying capacity (~100 cells), so to reach N≈500/2k/5k we synthesise
 * dense worlds by REPLICATING the save's actual evolved cells (genomes, cytoplasm, biomass, and
 * connectivity) across the torus with fresh entity ids, and scale the matter reservoir's counts by the
 * same factor so the colonies stay fed through the measurement window. The per-cell biology work is
 * therefore representative (real genomes); positions are spread so contact/spring density per region
 * matches the original. It is a performance probe, not a biology-faithful run — population drifts as
 * cells divide/die, so start/end counts are reported alongside the timings.
 *
 * `--args="<savePath> <factorsCsv> [warmup] [measure]"`, e.g. `... "1,6,22,56 100 400"`.
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: "platform/desktop-app/cyto-save.bin"
    val factors = (args.getOrNull(1) ?: "1,6,22,56").split(",").map { it.trim().toInt() }
    val warmup = args.getOrNull(2)?.toIntOrNull() ?: 100
    val measure = args.getOrNull(3)?.toIntOrNull() ?: 400

    val base = CytoSaveCodec.decode(File(path).readBytes())
    val baseCells = base.components.getTable<CytoCellComponent>().asMap().size
    println("loaded $path: $baseCells cells; replicating ×${factors.joinToString(",")} (warmup=$warmup measure=$measure)\n")

    val cfg = CytoConfig()
    val input = mapOf(PlayerId(0) to CytoInput.EMPTY)

    println("%6s %8s %10s %10s %10s   %s".format("Nstart", "Nend", "tick us", "KB/tick", "GCpause ms", "top phases (avg us / share / KB)"))
    println("-".repeat(120))
    for (f in factors) {
        val state0 = if (f <= 1) base else replicate(base, f)
        profileAt(state0, cfg, input, warmup, measure)
    }
}

private fun profileAt(state0: SimState, cfg: CytoConfig, input: Map<PlayerId, CytoInput>, warmup: Int, measure: Int) {
    val profiler = PipelineProfiler()
    profiler.allocReader = { allocatedBytes() }
    val reducer = CytoReducer(profiler = profiler)

    var state = state0
    val nStart = state.components.getTable<CytoCellComponent>().asMap().size
    for (t in 0 until warmup) state = reducer.reduce(cfg, state, input)
    profiler.reset()

    val gc0 = gcPauseMs()
    for (t in 0 until measure) {
        val s = System.nanoTime()
        state = reducer.reduce(cfg, state, input)
        profiler.recordTick(System.nanoTime() - s)
    }
    val gcMs = gcPauseMs() - gc0
    val nEnd = state.components.getTable<CytoCellComponent>().asMap().size

    val r = profiler.report()
    val top = r.phases.sortedByDescending { it.avgNanos }.take(3).joinToString("  ") {
        "%s %d/%.0f%%/%dKB".format(it.name, it.avgNanos / 1000, it.sharePercent, it.avgBytes / 1024)
    }
    val totalKb = r.phases.sumOf { it.avgBytes } / 1024
    println("%6d %8d %10d %10d %10d   %s".format(nStart, nEnd, r.tickAvgNanos / 1000, totalKb, gcMs, top))
}

/** Build a world with [copies]× the save's cells: replicate every cell (and its springs/connection
 *  damage, with entity refs remapped) at a torus offset near one of the light sources, and scale the
 *  matter reservoir counts by [copies] so the extra colonies have matter to live on. */
private fun replicate(base: SimState, copies: Int): SimState {
    val builder = SimBuilder(base)
    val cells = base.components.getTable<CytoCellComponent>().asMap()
    val transforms = base.components.getTable<TransformComponent>().asMap()
    val motions = base.components.getTable<MotionComponent>().asMap()
    val colliders = base.components.getTable<ColliderComponent>().asMap()
    val materials = base.components.getTable<MaterialComponent>().asMap()
    val springs = base.components.getTable<SpringConstraintComponent>().asMap()
    val connStates = base.components.getTable<ConnectionStateComponent>().asMap()
    val sources = CytoLightField.SOURCES
    val origin = sources.first()

    for (r in 1 until copies) {
        // Offset this copy near source[r % nSources], plus a small deterministic in-clump jitter so
        // copies sharing a source don't stack exactly on each other.
        val target = sources[r % sources.size]
        val ring = r / sources.size
        val offXLogical = (target.first - origin.first) + ((r * 17) % 41 - 20) + ring * 3f
        val offYLogical = (target.second - origin.second) + ((r * 29) % 41 - 20) - ring * 3f
        val offX = CytoUnits.coord(offXLogical).raw
        val offY = CytoUnits.coord(offYLogical).raw

        val idMap = HashMap<EntityId, EntityId>(cells.size)
        for (oid in cells.keys) idMap[oid] = builder.createEntity()

        for ((oid, cell) in cells) {
            val nid = idMap.getValue(oid)
            transforms[oid]?.let { t ->
                builder.update<TransformComponent>(nid) { t.copy(pos = Coord2(Coord(t.pos.x.raw + offX), Coord(t.pos.y.raw + offY))) }
            }
            motions[oid]?.let { m -> builder.update<MotionComponent>(nid) { m } }
            colliders[oid]?.let { c -> builder.update<ColliderComponent>(nid) { c } }
            materials[oid]?.let { mat -> builder.update<MaterialComponent>(nid) { mat } }
            builder.update<CytoCellComponent>(nid) { cell }  // shares immutable genome/cytoplasm/biomass refs
            springs[oid]?.let { sc ->
                val remapped = sc.springs.mapNotNull { sp -> idMap[sp.other]?.let { sp.copy(other = it) } }
                if (remapped.isNotEmpty()) builder.update<SpringConstraintComponent>(nid) { SpringConstraintComponent(remapped) }
            }
            connStates[oid]?.let { cs ->
                val remapped = cs.damage.entries.mapNotNull { e -> idMap[e.key]?.let { it to e.value } }.toMap()
                if (remapped.isNotEmpty()) builder.update<ConnectionStateComponent>(nid) { ConnectionStateComponent(remapped) }
            }
        }
    }

    // Scale matter so per-colony availability tracks the original (else the extra colonies starve).
    val grid = builder.getComponent<CytoMatterGridComponent>(GRID_SINGLETON)?.grid
    if (grid != null) {
        val n = CytoMatterGrid.RES * CytoMatterGrid.RES
        val scaled = Array(n) { idx -> HashMap(grid.cellAt(idx).mapValues { it.value * copies }) }
        builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(CytoMatterGrid.fromCells(scaled)) }
    }
    return builder.build()
}

private fun allocatedBytes(): Long {
    val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    return bean.getThreadAllocatedBytes(Thread.currentThread().id)
}

private fun gcPauseMs(): Long =
    java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().sumOf { if (it.collectionTime >= 0) it.collectionTime else 0L }
