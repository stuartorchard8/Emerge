package org.emerge.demo.drockets

import org.emerge.demo.drockets.DrocketPopulationSafetyNetSystem.POPULATION_FLOOR
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Drockets-only safeguard: when population drops below [POPULATION_FLOOR], clamp incoming damage
 * so drockets cannot cross the destruction threshold this tick.
 *
 * This keeps shared engine damage semantics unchanged while preventing extinction spirals in the
 * demo's autonomous population loop.
 */
object DrocketPopulationSafetyNetSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    private const val POPULATION_FLOOR = 400

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val drocketStates = LinkedHashMap(builder.entries<DrocketStateComponent>())
        if (drocketStates.size <= POPULATION_FLOOR) {
            // Void all damage
            builder.setTable<DamageComponent>(mutableMapOf())
        }
    }
}
