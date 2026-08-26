package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.runSequential
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.sim.SimBuilder

import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.addDamages
import org.emerge.sim.core.sim.setImpulses

import org.emerge.sim.core.physics.systems.IntegrationSystem
import org.emerge.sim.core.physics.systems.ParticleSystem
import org.emerge.sim.core.ecs.PipelineProfiler

/**
 * Reducer used by thin clients that receive impulses over the wire rather than computing
 * them locally. The force-gather, contact and attachment phases are skipped entirely;
 * only lifecycle, effects, and integration run locally.
 */
class ScavengersNoImpulseReducer : SimReducer<ScavengersConfig, ScavengersState, ScavengersInput> {
    private val pipeline: Pipeline<ScavengersConfig, SimState, ScavengersInput> = listOf(
        Phase("lifecycle", RespawnSystem, DamageSystem),
        Phase("effects", ShipThrustParticleSystem, ParticleSystem),
        Phase("integrate", IntegrationSystem),
    )

    override fun reduce(
        cfg: ScavengersConfig,
        state: ScavengersState,
        inputs: Map<PlayerId, ScavengersInput>,
        profiler: PipelineProfiler?,
    ): ScavengersState {
        val builder = SimBuilder(state.core)
        val scavengersScratch = builder.seedScavengersScratch(
            initialPendingRespawns = state.pendingRespawns,
            playerEntities = state.playerEntities,
        )
        runSequential(cfg, builder, inputs, pipeline)
        val nextCore = builder.build()
        return ScavengersState(
            core = nextCore,
            playerEntities = nextCore.computePlayerEntities(),
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
