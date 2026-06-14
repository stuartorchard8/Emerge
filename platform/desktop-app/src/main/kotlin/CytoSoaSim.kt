package org.emerge.desktop

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.sim.SimState

/**
 * Thin desktop harness for driving the **live [CytoSoaReducer]** from the headless probes/tools, which
 * predate the SoA landing and were written against the AoS `CytoReducer`'s `reduce(cfg,state,input)`
 * shape. Holds a persistent [CytoWorld] (the SoA tick mutates/returns a world, not a SimState) and
 * materialises a [SimState] on demand so the probes' existing component-reading helpers keep working.
 *
 * [step] materialises every tick (convenient for the diagnostics, which read state at checkpoints);
 * [stepWorld] ticks without materialising, for perf probes that time the tick itself.
 */
class CytoSoaSim(
    cfg: CytoConfig,
    initial: SimState,
    executor: ParallelExecutor? = null,
    profiler: PipelineProfiler? = null,
) {
    private val reducer = CytoSoaReducer(cfg, executor = executor, profiler = profiler)
    private var world = CytoWorld.fromSimState(initial)

    val count: Int get() = world.count

    /** Tick and return the materialised state (use in diagnostics that read state, not tick time). */
    fun step(input: CytoInput = CytoInput.EMPTY): SimState { world = reducer.tick(world, input); return world.toSimState() }

    /** Tick without materialising — for timed loops where toSimState would pollute the measurement. */
    fun stepWorld(input: CytoInput = CytoInput.EMPTY) { world = reducer.tick(world, input) }

    fun state(): SimState = world.toSimState()
}
