package org.emerge.demo.drockets

import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.PlanetComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState

/**
 * Typed-getter extensions used by Drockets's demo-side rendering and pick code. Engine
 * [SimState] dropped these convenience getters in Move 5; demos define their own
 * over whatever component types they actually read.
 */
internal val SimState.transforms get() = components.getTable<TransformComponent>()
internal val SimState.colliders get() = components.getTable<ColliderComponent>()
internal val SimState.renderShapes get() = components.getTable<RenderShapeComponent>()
internal val SimState.particles get() = components.getTable<ParticleComponent>()
internal val SimState.planets get() = components.getTable<PlanetComponent>()
internal val SimState.forceFields get() = components.getTable<ForceFieldComponent>()
