package org.emerge.sim.core

import org.emerge.sim.core.ecs.PipelineProfiler

/**
 * Deterministic pure reducer: given the current [state] and inputs for one tick, returns the next state.
 *
 * Implementations MUST be side-effect-free: the returned state is the sole output, so this
 * contract can support rollback/replay and shares structure across platforms.
 */
interface SimReducer<C, S, I> {
    fun reduce(cfg: C, state: S, inputs: Map<PlayerId, I>, profiler: PipelineProfiler? = null): S
    fun patchState(state: S, delta: S): S
}
