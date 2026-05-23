package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.PipelineProfiler
import org.emerge.sim.core.ecs.isolated
import org.emerge.sim.core.ecs.runParallel
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.addDamages
import org.emerge.sim.core.physics.model.setImpulses
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.AttachmentSystem
import org.emerge.sim.core.physics.systems.BounceSystem
import org.emerge.sim.core.physics.systems.ContactSystem
import org.emerge.sim.core.physics.systems.CrashSystem
import org.emerge.sim.core.physics.systems.GravitySystem
import org.emerge.sim.core.physics.systems.ImpulseResetSystem
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.ParticleSystem
import org.emerge.sim.core.physics.systems.RollingResistanceSystem
import org.emerge.sim.core.physics.systems.ShipThrustParticleSystem

/**
 * Reducer for the Scavengers demo.
 *
 * Operates on [ScavengersState], which wraps the engine [PhysicsState] with the
 * Scavengers-only respawn queue and crash audio event list. Each tick:
 *
 *   1. Build a [PhysicsBuilder] from `state.core`.
 *   2. Seed a [ScavengersFrameScratch] on the builder from `state.pendingRespawns`.
 *   3. Run the pipeline. Engine systems read/write only the engine state via the
 *      builder; Scavengers systems also read/write the Scavengers scratch via the
 *      extension API in `ScavengersBuilder.kt`.
 *   4. Build the new [PhysicsState] from the builder and combine with the final
 *      scratch contents to produce the new [ScavengersState].
 *
 * The pipeline is declared as an ordered list of named phases. Phase boundaries document
 * where state produced by one group of systems is consumed by the next (e.g. contacts are
 * produced in `contactDetect` and consumed in `contactResponse`).
 *
 * Four phases are marked [isolated][org.emerge.sim.core.ecs.isolated]:
 *
 *  - `forceGather` — `ShipThrustSystem`, `GravitySystem`, and `ForceFieldSystem` only
 *    add to `ImpulseComponent` (commutative) and read disjoint mixes of transforms,
 *    motion, masses, colliders, and field/team components.
 *  - `contactResponse` — each system reads the committed `contacts` list produced by
 *    `contactDetect` and writes its own disjoint subset of components.
 *  - `lifecycle` — `RespawnSystem` drains `pendingRespawns` and `DamageSystem` enqueues
 *    into it; both go through shared-scratch delegation so fork writes land on the
 *    root builder's scratch.
 *  - `effects` — `ParticleSystem` is registered FIRST so its authoritative
 *    `setTable<ParticleComponent>` replays before `ShipThrustParticleSystem`'s new-
 *    particle updates.
 *
 * Pass an [executor] to dispatch isolated phases' forks across worker threads via
 * [runParallel]; omit it (default `null`) to run the whole pipeline on the calling
 * thread via [runSequential]. Both dispatch modes produce identical state modulo
 * the PRNG-ordering note on `PhysicsBuilder.nextRandomInt`.
 *
 * Pass a [profiler] to collect per-phase wall-time samples every tick.
 */
class ScavengersReducer(
    private val executor: ParallelExecutor? = null,
    private val profiler: PipelineProfiler? = null,
) : SimReducer<PhysicsConfig, ScavengersState, PhysicsInput> {
    private val pipeline: Pipeline<PhysicsConfig, PhysicsState, PhysicsInput> = listOf(
        Phase("reset", ImpulseResetSystem),
        Phase("forceGather", ShipThrustSystem, GravitySystem(executor), ForceFieldSystem).isolated(),
        Phase("contactDetect", ContactSystem(executor)),
        Phase("contactResponse", CrashSystem, BounceSystem, RollingResistanceSystem, LandingSystem).isolated(),
        Phase("attachment", AttachmentSystem),
        Phase("lifecycle", RespawnSystem, DamageSystem).isolated(),
        Phase("effects", ParticleSystem, ShipThrustParticleSystem).isolated(),
        Phase("integrate", IntegrationSystem),
    )

    override fun reduce(
        cfg: PhysicsConfig,
        state: ScavengersState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): ScavengersState {
        val builder = PhysicsBuilder(state.core)
        val scavengersScratch = builder.seedScavengersScratch(state.pendingRespawns)
        if (executor != null) {
            runParallel(cfg, builder, inputs, pipeline, executor, profiler)
        } else {
            runSequential(cfg, builder, inputs, pipeline, profiler)
        }
        val nextCore = builder.build()
        return ScavengersState(
            core = nextCore,
            pendingRespawns = scavengersScratch.pendingRespawns.toMap(),
            crashImpactAudioEvents = scavengersScratch.crashImpactAudioEvents.toList(),
        )
    }

    override fun patchState(state: ScavengersState, delta: ScavengersState): ScavengersState {
        // Intentionally ignoring everything but impulses + damages.
        // That's all ThinLockstepClient acquires.
        val patchedCore = state.core
            .setImpulses(delta.core.components.getTable<ImpulseComponent>())
            .addDamages(
                delta.core.components.getTable<DamageComponent>()
                    .asMap().mapValues { (_, component) -> component.next },
            )
        return state.copy(core = patchedCore)
    }
}
