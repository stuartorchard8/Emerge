package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.*

class NoImpulsePhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>> = listOf(
        RespawnSystem,
        DamageSystem,
        ShipThrustParticleSystem,
        ParticleSystem,
        IntegrationSystem,
    )

    override fun reduce(cfg: PhysicsConfig, state: PhysicsState, inputs: Map<PlayerId, PhysicsInput>) {
        EcsSystems.runAll(cfg, state, inputs, systems)
    }

    override fun patchState(state: PhysicsState, delta: PhysicsState) {
        // Intentionally ignoring everything but impulses.
        // Impulses is all that ThinLockstepClient acquires.
        state.setImpulses(delta.raw.components.getTable())
        state.addDamages(delta.raw.damages.asMap().mapValues { (_, component) -> component.next })
    }
}
