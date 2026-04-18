package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.set

object ParticleSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
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
