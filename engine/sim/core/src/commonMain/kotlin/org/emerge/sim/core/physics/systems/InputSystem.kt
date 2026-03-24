package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.components.ControlIntentComponent
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.collections.set


object InputSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val controls = LinkedHashMap(state.raw.controls.asMap())
        for ((playerId, entityId) in state.raw.playerEntities) {
            val input = inputs[playerId] ?: PhysicsInput.ZERO
            controls[entityId] =
                ControlIntentComponent(
                    thrust = input.thrust,
                    turn = input.turn,
                )
        }
        state.raw = state.raw.copy(controls = ComponentTable.fromMap(controls))
    }
}
