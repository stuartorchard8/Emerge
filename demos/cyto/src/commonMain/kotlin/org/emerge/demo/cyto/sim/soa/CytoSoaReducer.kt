package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoReducer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.PipelineProfiler

/**
 * Struct-of-arrays cyto tick on the persistent [CytoWorld].
 *
 * **Landing stage — full bridge.** Every phase is currently *bridged* through the array-of-structs
 * [CytoReducer]: the tick materializes the world to a `SimState`, runs the unmodified AoS pipeline,
 * and reloads the result into columns. That makes the SoA tick **equivalent to AoS by construction**
 * — the only thing under test at this stage is that [CytoWorld.toSimState]/[CytoWorld.fromSimState]
 * round-trip losslessly (`CytoSoaEquivalenceTest`). It is intentionally *not yet* a performance win:
 * the slices that follow move the hot phases (forces / connections / contacts, then biology /
 * lifecycle) onto in-place column reads, deleting them from the bridge until nothing is bridged and
 * the per-tick `SimState` rebuild is gone.
 */
class CytoSoaReducer(
    private val cfg: CytoConfig,
    executor: ParallelExecutor? = null,
    private val profiler: PipelineProfiler? = null,
) {
    private val aos = CytoReducer(profiler = profiler, executor = executor)

    fun tick(w: CytoWorld, input: CytoInput = CytoInput.EMPTY): CytoWorld {
        val next = aos.reduce(cfg, w.toSimState(), mapOf(PlayerId(0) to input))
        return CytoWorld.fromSimState(next)
    }
}
