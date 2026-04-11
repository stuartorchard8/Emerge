package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.*

class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>> = listOf(
        ImpulseResetSystem,
        ShipThrustSystem,
        GravitySystem,
        ForceFieldSystem,
        ContactSystem,
        CrashSystem,
        BounceSystem,
        LandingSystem,
        AttachmentSystem,
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
        state.setImpulses(delta.raw.impulses)
        state.addDamages(delta.raw.damages.asMap().mapValues { (_, component) -> component.next })
    }
}
