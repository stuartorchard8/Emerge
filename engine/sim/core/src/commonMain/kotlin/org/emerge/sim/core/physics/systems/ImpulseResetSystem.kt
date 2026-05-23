package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.SimInput

object ImpulseResetSystem : EcsSystem<PhysicsTuning, PhysicsState, SimInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        builder.setTable<ImpulseComponent>(mutableMapOf())
    }
}
