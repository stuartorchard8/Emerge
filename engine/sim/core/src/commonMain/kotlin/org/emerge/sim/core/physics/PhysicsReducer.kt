package org.emerge.sim.core.physics

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.isolated
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.addDamages
import org.emerge.sim.core.physics.model.setImpulses
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.*

/**
 * Reducer for the main physics demo.
 *
 * The pipeline is declared as an ordered list of named phases. Phase boundaries document
 * where state produced by one group of systems is consumed by the next (e.g. contacts are
 * produced in `contactDetect` and consumed in `contactResponse`). `contactResponse` is
 * marked [isolated][org.emerge.sim.core.ecs.isolated]: each system sees only the
 * contactDetect-committed state, never other contactResponse systems' intra-phase
 * writes, and their write-logs are replayed on the parent in registration order at the
 * phase barrier — the execution model a parallel dispatcher will use. Today every phase
 * still runs on a single thread via [runSequential].
 */
class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val pipeline: Pipeline<PhysicsConfig, PhysicsState, PhysicsInput> = listOf(
        Phase("reset", ImpulseResetSystem),
        Phase("forceGather", ShipThrustSystem, GravitySystem, ForceFieldSystem),
        Phase("contactDetect", ContactSystem),
        Phase("contactResponse", CrashSystem, BounceSystem, LandingSystem).isolated(),
        Phase("attachment", AttachmentSystem),
        Phase("lifecycle", RespawnSystem, DamageSystem),
        Phase("effects", ShipThrustParticleSystem, ParticleSystem),
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
        // Intentionally ignoring everything but impulses + damages.
        // That's all ThinLockstepClient acquires.
        state
            .setImpulses(delta.impulses)
            .addDamages(delta.damages.asMap().mapValues { (_, component) -> component.next })
}
