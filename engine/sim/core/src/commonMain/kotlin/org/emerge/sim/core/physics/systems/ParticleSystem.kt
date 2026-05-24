package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.physics.model.PhysicsTuning
import org.emerge.sim.core.SimInput
import kotlin.collections.set

object ParticleSystem : EcsSystem<PhysicsTuning, SimState, SimInput> {
    override fun update(
        cfg: PhysicsTuning,
        builder: SimBuilder,
        inputs: Map<PlayerId, SimInput>,
    ) {
        val particles = LinkedHashMap(builder.entries<ParticleComponent>())
        for ((entityId, particle) in builder.entries<ParticleComponent>()) {
            val newLife = particle.life-1
            if (newLife > 0) {
                particles[entityId] = particle.copy(life = newLife)
            } else {
                particles.remove(entityId)
                builder.removeEntity(entityId)
            }
        }
        builder.setTable<ParticleComponent>(particles)
    }
}
