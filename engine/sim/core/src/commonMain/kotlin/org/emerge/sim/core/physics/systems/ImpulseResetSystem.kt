package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.SimInput

object ImpulseResetSystem : EcsSystem<PhysicsTuning, SimState, SimInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        builder.setTable<ImpulseComponent>(mutableMapOf())
    }
}
