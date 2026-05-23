package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.addDamages
import org.emerge.sim.core.physics.model.setImpulses
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.ParticleSystem
import org.emerge.sim.core.physics.systems.ShipThrustParticleSystem

/**
 * Reducer used by thin clients that receive impulses over the wire rather than computing
 * them locally. The force-gather, contact and attachment phases are skipped entirely;
 * only lifecycle, effects, and integration run locally.
 */
class ScavengersNoImpulseReducer : SimReducer<PhysicsConfig, ScavengersState, PhysicsInput> {
    private val pipeline: Pipeline<PhysicsConfig, PhysicsState, PhysicsInput> = listOf(
        Phase("lifecycle", RespawnSystem, DamageSystem),
        Phase("effects", ShipThrustParticleSystem, ParticleSystem),
        Phase("integrate", IntegrationSystem),
    )

    override fun reduce(
        cfg: PhysicsConfig,
        state: ScavengersState,
        inputs: Map<PlayerId, PhysicsInput>,
    ): ScavengersState {
        val builder = PhysicsBuilder(state.core)
        val scavengersScratch = builder.seedScavengersScratch(state.pendingRespawns)
        runSequential(cfg, builder, inputs, pipeline)
        val nextCore = builder.build()
        return ScavengersState(
            core = nextCore,
            pendingRespawns = scavengersScratch.pendingRespawns.toMap(),
            crashImpactAudioEvents = scavengersScratch.crashImpactAudioEvents.toList(),
        )
    }

    override fun patchState(state: ScavengersState, delta: ScavengersState): ScavengersState {
        val patchedCore = state.core
            .setImpulses(delta.core.components.getTable<ImpulseComponent>())
            .addDamages(
                delta.core.components.getTable<DamageComponent>()
                    .asMap().mapValues { (_, component) -> component.next },
            )
        return state.copy(core = patchedCore)
    }
}
