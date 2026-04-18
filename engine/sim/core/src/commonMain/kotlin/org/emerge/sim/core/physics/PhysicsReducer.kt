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
 * produced in `contactDetect` and consumed in `contactResponse`).
 *
 * Four phases are marked [isolated][org.emerge.sim.core.ecs.isolated]:
 *
 *  - `forceGather` — `ShipThrustSystem`, `GravitySystem`, and `ForceFieldSystem` only
 *    add to `ImpulseComponent` (commutative) and read disjoint mixes of transforms,
 *    motion, masses, colliders, and field/team components. Thrust additionally removes
 *    `LandingAttachmentComponent` for lift-off; gravity/forcefield read landing
 *    attachments from the frozen fork view and therefore skip a ship on the tick it
 *    takes off, only applying external force from the next tick onward. This one-tick
 *    delay is intentional: the old intra-phase sequential order let external force
 *    apply immediately on detach, which we consider a quirk of that order rather than
 *    required behaviour, and locking that quirk in would block parallel execution.
 *  - `contactResponse` — each system reads the committed `contacts` list produced by
 *    `contactDetect` and writes its own disjoint subset of components.
 *  - `lifecycle` — `RespawnSystem` drains `pendingRespawns` and `DamageSystem` enqueues
 *    into it; both go through shared-scratch delegation so fork writes land on the
 *    root builder's scratch. Under sequential fork execution the reads/writes
 *    interleave exactly like the old phase. Entity creates/removes share the world
 *    via the same delegation pattern we already use for the entity id counter.
 *  - `effects` — `ParticleSystem` is registered FIRST so its authoritative
 *    `setTable<ParticleComponent>` replays before `ShipThrustParticleSystem`'s new-
 *    particle updates, which would otherwise be wiped by the setTable. Under this
 *    ordering newly spawned particles skip their first lifetime tick (gain +1 frame
 *    of life) — same kind of sub-tick artefact as the forceGather trade-off.
 *
 * Today every phase still runs on a single thread via [runSequential]; the isolated
 * phases just execute their forks sequentially. Moving to a worker-pool dispatcher
 * later should be a drop-in replacement.
 */
class PhysicsReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val pipeline: Pipeline<PhysicsConfig, PhysicsState, PhysicsInput> = listOf(
        Phase("reset", ImpulseResetSystem),
        Phase("forceGather", ShipThrustSystem, GravitySystem, ForceFieldSystem).isolated(),
        Phase("contactDetect", ContactSystem),
        Phase("contactResponse", CrashSystem, BounceSystem, LandingSystem).isolated(),
        Phase("attachment", AttachmentSystem),
        Phase("lifecycle", RespawnSystem, DamageSystem).isolated(),
        Phase("effects", ParticleSystem, ShipThrustParticleSystem).isolated(),
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
