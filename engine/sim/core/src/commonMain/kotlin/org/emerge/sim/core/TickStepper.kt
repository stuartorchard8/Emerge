package org.emerge.sim.core

import org.emerge.sim.core.ecs.PipelineProfiler

class TickStepper<C, S, I>(
    private val cfg: C,
    initialState: S,
    private val reducer: SimReducer<C, S, I>,
    initialTick: Tick = Tick(0),
) {
    var profiler: PipelineProfiler? = null

    var tick: Tick = initialTick
        private set

    var state: S = initialState
        private set

    fun step(inputs: Map<PlayerId, I>): S {
        val start = System.nanoTime()
        state = reducer.reduce(cfg, state, inputs, profiler)
        profiler?.recordTick(System.nanoTime() - start)
        tick = Tick(tick.value + 1)
        return state
    }

    fun reset(newState: S, newTick: Tick) {
        state = newState
        tick = newTick
    }

    /**
     * Replaces the current state without advancing the tick. Used by the host when a policy
     * (join/leave) produces a new snapshot mid-session.
     */
    fun replaceState(newState: S) {
        state = newState
    }

    fun patch(delta: S) {
        state = reducer.patchState(state, delta)
    }
}
