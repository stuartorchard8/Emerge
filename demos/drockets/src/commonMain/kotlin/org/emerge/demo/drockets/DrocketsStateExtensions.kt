package org.emerge.demo.drockets

import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.PlanetComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsState

/**
 * Typed-getter extensions used by Drockets's demo-side rendering and pick code. Engine
 * [PhysicsState] dropped these convenience getters in Move 5; demos define their own
 * over whatever component types they actually read.
 */
internal val PhysicsState.transforms get() = components.getTable<TransformComponent>()
internal val PhysicsState.colliders get() = components.getTable<ColliderComponent>()
internal val PhysicsState.renderShapes get() = components.getTable<RenderShapeComponent>()
internal val PhysicsState.particles get() = components.getTable<ParticleComponent>()
internal val PhysicsState.teams get() = components.getTable<TeamComponent>()
internal val PhysicsState.planets get() = components.getTable<PlanetComponent>()
internal val PhysicsState.forceFields get() = components.getTable<ForceFieldComponent>()
