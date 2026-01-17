package org.emerge.sim.core

/**
 * Deterministic pure reducer: given the current [state] and inputs for one tick, returns the next state.
 *
 * Keep this side-effect-free so it can be reused across platforms and supports rollback/replay later.
 */
fun interface SimReducer<S, I> {
    fun reduce(state: S, inputs: Map<PlayerId, I>): S
}

