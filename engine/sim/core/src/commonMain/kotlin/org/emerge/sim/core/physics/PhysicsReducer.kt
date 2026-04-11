package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.AttachmentSystem
import org.emerge.sim.core.physics.systems.BounceSystem
import org.emerge.sim.core.physics.systems.ContactSystem
import org.emerge.sim.core.physics.systems.CrashSystem
import org.emerge.sim.core.physics.systems.DamageSystem
import org.emerge.sim.core.physics.systems.ForceFieldSystem
import org.emerge.sim.core.physics.systems.GravitySystem
import org.emerge.sim.core.physics.systems.ShipThrustSystem
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.LandingSystem
import org.emerge.sim.core.physics.systems.ParticleSystem
import org.emerge.sim.core.physics.systems.RespawnSystem
import org.emerge.sim.core.physics.systems.ShipThrustParticleSystem

class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>> = listOf(
        ShipThrustSystem,
        GravitySystem,
        ForceFieldSystem,
        ContactSystem,
        BounceSystem,
        CrashSystem,
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
