package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.addDamages
import org.emerge.sim.core.physics.model.setImpulses
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.*

class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsInput>> = listOf(
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

    override fun reduce(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val builder = PhysicsBuilder(state)
        EcsSystems.runAll(cfg, builder, inputs, systems)
        return builder.build()
    }

    override fun patchState(state: PhysicsState, delta: PhysicsState): PhysicsState =
        // Intentionally ignoring everything but impulses + damages.
        // That's all ThinLockstepClient acquires.
        state
            .setImpulses(delta.impulses)
            .addDamages(delta.damages.asMap().mapValues { (_, component) -> component.next })
}
