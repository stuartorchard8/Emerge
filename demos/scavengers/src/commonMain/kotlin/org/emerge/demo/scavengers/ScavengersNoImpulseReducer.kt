package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.ecs.Phase
import org.emerge.sim.core.ecs.Pipeline
import org.emerge.sim.core.ecs.runSequential
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
class ScavengersNoImpulseReducer : SimReducer<PhysicsConfig, PhysicsState, PhysicsInput> {
    private val pipeline: Pipeline<PhysicsConfig, PhysicsState, PhysicsInput> = listOf(
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
        state
            .setImpulses(delta.components.getTable<ImpulseComponent>())
            .addDamages(delta.damages.asMap().mapValues { (_, component) -> component.next })
}
