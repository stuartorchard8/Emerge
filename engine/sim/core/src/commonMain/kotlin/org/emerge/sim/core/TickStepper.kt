package org.emerge.sim.core

import org.emerge.sim.core.ecs.PipelineProfiler
import kotlin.time.TimeSource

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
        // `TimeSource.Monotonic` rather than `System.nanoTime()`: this is commonMain, and the JS
        // target has no `System`. Same idiom as `timePhase` in `Pipeline`/`SoaPipeline`, including
        // the null fast path, so a production build with no profiler never reads the clock.
        val prof = profiler
        if (prof == null) {
            state = reducer.reduce(cfg, state, inputs, null)
        } else {
            val start = TimeSource.Monotonic.markNow()
            state = reducer.reduce(cfg, state, inputs, prof)
            prof.recordTick(start.elapsedNow().inWholeNanoseconds)
        }
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
