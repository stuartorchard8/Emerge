package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.*
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.setImpulses

import org.emerge.sim.core.physics.systems.*
import org.emerge.sim.core.ecs.PipelineProfiler

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
 *                         `ImpulseComponent`, no scratch or lifecycle access; runs
 *                         [isolated][org.emerge.sim.core.ecs.isolated] so each system
 *                         sees only the phase's starting state and their write-logs are
 *                         replayed in registration order at the phase barrier.
 *  - `contactDetect`    – broadphase + narrowphase contacts.
 *  - `contactResponse`  – landing detection and bounce impulses, both reading contacts,
 *                         also isolated.
 *  - `attachment`       – rigid surface attachment snap.
 *  - `effects`          – particle lifetime ticks + new exhaust spawns, isolated.
 *                         `ParticleSystem` is registered first so its authoritative
 *                         `setTable<ParticleComponent>` replays before
 *                         `DrocketParticleSystem`'s per-entity new-particle updates;
 *                         otherwise the setTable would wipe the new spawns. PRNG
 *                         draws go through [sharedScratch] so both forks share a
 *                         single linear seed sequence.
 *  - `integrate`        – Euler integration of position and velocity.
 *
 * `aiAndMotion` stays sequential: `DrocketAISystem` authoritatively rewrites the
 * `LandingAttachmentComponent` table each tick, and `WalkSystem` then does per-entity
 * `update<LandingAttachmentComponent>` calls — under isolation the fork-replayed
 * updates would re-attach drockets that DrocketAISystem just detached for a launch.
 *
 * Pass an [executor] to dispatch isolated phases' forks across worker threads via
 * [runParallel]; omit it (default `null`) to run the whole pipeline on the calling
 * thread via [runSequential]. Pass a [profiler] to collect per-phase wall-time
 * samples every tick.
 */
class DrocketsReducer(
    private val executor: ParallelExecutor? = null,
    private val profiler: PipelineProfiler? = null,
) : SimReducer<DrocketsConfig, SimState, DrocketsInput> {
    private val pipeline: Pipeline<DrocketsConfig, SimState, DrocketsInput> = listOf(
        Phase("reset", ImpulseResetSystem),
        Phase("aiAndMotion", DrocketAISystem, DrocketWalkSystem, KnightAISystem, KnightWalkSystem, SpriteAnimationSystem),
        Phase("forceGather", GravitySystem(executor), AtmosphereDragSystem).isolated(),
        Phase("contactDetect", ContactSystem(executor)),
        Phase("contactResponse", DrocketLandingSystem, ReproductionSystem, CrashSystem, BounceSystem, RollingResistanceSystem).isolated(),
        Phase("lifecycle", DrocketAdaptiveDamageSystem),
        Phase("attachment", AttachmentSystem),
        Phase("effects", ParticleSystem, DrocketParticleSystem).isolated(),
        Phase("integrate", IntegrationSystem),
    )

    override fun reduce(
        cfg: DrocketsConfig,
        state: SimState,
        inputs: Map<PlayerId, DrocketsInput>,
        profiler: PipelineProfiler?,
    ): SimState {
        val builder = SimBuilder(state)
        if (executor != null) {
            runParallel(cfg, builder, inputs, pipeline, executor, profiler)
        } else {
            runSequential(cfg, builder, inputs, pipeline, profiler)
        }
        // Advance the deterministic sim clock (the lockstep-safe replacement for wall-clock).
        return builder.build().copy(tick = state.tick + 1)
    }

    override fun patchState(state: SimState, delta: SimState): SimState =
        state.setImpulses(delta.components.getTable<ImpulseComponent>())
}
