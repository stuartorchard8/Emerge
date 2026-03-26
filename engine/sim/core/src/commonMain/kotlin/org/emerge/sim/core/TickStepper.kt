package org.emerge.sim.core

class TickStepper<C, S, I>(
    private val cfg: C,
    initialState: S,
    private val reducer: SimReducer<C, S, I>,
    initialTick: Tick = Tick(0),
) {
    var tick: Tick = initialTick
        private set

    var state: S = initialState
        private set

    fun step(inputs: Map<PlayerId, I>): S {
        reducer.reduce(cfg, state, inputs)
        tick = Tick(tick.value + 1)
        return state
    }

    fun reset(newState: S, newTick: Tick) {
        state = newState
        tick = newTick
    }
}

