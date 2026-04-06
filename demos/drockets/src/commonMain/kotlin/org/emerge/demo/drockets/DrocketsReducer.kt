package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.ecs.EcsSystems
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.AttachmentSystem
import org.emerge.sim.core.physics.systems.CollisionSystem
import org.emerge.sim.core.physics.systems.DamageSystem
import org.emerge.sim.core.physics.systems.GravitySystem
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.ParticleSystem

/**
 * Reducer for the Drockets demo. Composes engine physics systems with
 * drocket-specific AI and walking behaviour.
 *
 * System ordering:
 * 1. DrocketAISystem  – state machine transitions, detach on launch, apply thrust
 * 2. WalkSystem       – move walking drockets along planet surface
 * 3. GravitySystem    – inverse-square gravity
 * 4. AtmosphereDragSystem – velocity-squared drag in atmosphere
 * 5. CollisionSystem  – collision response + landing detection
 * 6. AttachmentSystem – rigid surface attachment
 * 7. DrocketParticleSystem – exhaust particles while thrusting
 * 8. ParticleSystem   – tick particle lifetimes
 * 9. IntegrationSystem – Euler integration
 */
class DrocketsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val systems: List<EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput>> = listOf(
        DrocketAISystem,
        WalkSystem,
        GravitySystem,
        AtmosphereDragSystem,
        CollisionSystem,
        AttachmentSystem,
        DrocketParticleSystem,
        ParticleSystem,
        IntegrationSystem,
    )

    override fun reduce(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        EcsSystems.runAll(cfg, state, inputs, systems)
    }

    override fun patchState(state: PhysicsState, delta: PhysicsState) {
        state.setImpulses(delta.raw.impulses)
    }
}
