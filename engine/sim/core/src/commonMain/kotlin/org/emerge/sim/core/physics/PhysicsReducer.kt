package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.AttachmentSystem
import org.emerge.sim.core.physics.systems.CollisionSystem
import org.emerge.sim.core.physics.systems.ForceFieldSystem
import org.emerge.sim.core.physics.systems.GravitySystem
import org.emerge.sim.core.physics.systems.InputSystem
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.LiftOffSystem

class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>> = listOf(
        InputSystem,
        LiftOffSystem,
        GravitySystem,
        IntegrationSystem,
        ForceFieldSystem,
        CollisionSystem,
        AttachmentSystem,
    )

    override fun reduce(cfg: PhysicsConfig, state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>): PhysicsState {
        return EcsSystems.runAll(cfg, state, inputs, systems)
    }
}
