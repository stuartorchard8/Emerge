package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState

object RespawnSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        state.advanceRespawns()
    }
}
