package org.emerge.sim.core.demo

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer

object DemoReducer : SimReducer<DemoState, DemoInput> {
    override fun reduce(state: DemoState, inputs: Map<PlayerId, DemoInput>): DemoState {
        if (inputs.isEmpty()) return state

        val next = state.positions.toMutableMap()
        for ((playerId, input) in inputs) {
            val cur = next[playerId] ?: Vec2i(0, 0)
            next[playerId] = cur + input.move
        }
        return state.copy(positions = next)
    }
}

