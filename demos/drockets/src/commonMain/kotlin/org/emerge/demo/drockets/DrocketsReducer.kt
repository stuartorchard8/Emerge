package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.isolated
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.setImpulses
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.*

/**
 * Reducer for the Drockets demo. Composes engine physics systems with drocket-specific
 * AI and walking behaviour.
 *
 * Pipeline layout:
 *
 *  - `reset`            – zero out per-tick impulse accumulators.
 *  - `aiAndMotion`      – drocket state machine transitions + surface walking. Applies
 *                         thrust via [DrocketAISystem].
 *  - `forceGather`      – gravity + atmospheric drag. All additive writes to
 *                         `ImpulseComponent`.
 *  - `contactDetect`    – broadphase + narrowphase contacts.
 *  - `contactResponse`  – landing detection and bounce impulses, both reading contacts.
 *  - `attachment`       – rigid surface attachment snap.
 *  - `effects`          – exhaust particle spawns + particle lifetime ticks.
 *  - `integrate`        – Euler integration of position and velocity.
 */
class DrocketsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val pipeline: Pipeline<PhysicsConfig, PhysicsState, PhysicsInput> = listOf(
        Phase("reset", ImpulseResetSystem),
        Phase("aiAndMotion", DrocketAISystem, WalkSystem),
        Phase("forceGather", GravitySystem, AtmosphereDragSystem),
        Phase("contactDetect", ContactSystem),
        Phase("contactResponse", DrocketLandingSystem, BounceSystem).isolated(),
        Phase("attachment", AttachmentSystem),
        Phase("effects", DrocketParticleSystem, ParticleSystem),
        Phase("integrate", IntegrationSystem),
    )

    override fun reduce(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): PhysicsState {
        val builder = PhysicsBuilder(state)
        runSequential(cfg, builder, inputs, pipeline)
        return builder.build()
    }

    override fun patchState(state: PhysicsState, delta: PhysicsState): PhysicsState =
        state.setImpulses(delta.impulses)
}
