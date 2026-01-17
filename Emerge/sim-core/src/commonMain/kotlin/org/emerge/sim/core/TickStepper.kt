package org.emerge.sim.core

class TickStepper<S, I>(
    initialState: S,
    private val reducer: SimReducer<S, I>,
    initialTick: Tick = Tick(0),
) {
    var tick: Tick = initialTick
        private set

    var state: S = initialState
        private set

    /**
     * Replace the current state without advancing time (used by authoritative join/snapshots).
     */
    fun replaceState(newState: S) {
        state = newState
    }

    fun step(inputs: Map<PlayerId, I>): S {
        state = reducer.reduce(state, inputs)
        tick = Tick(tick.value + 1)
        return state
    }
}

