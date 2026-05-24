package org.emerge.render.torus

import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState

/**
 * Renderer-internal typed accessors for the engine component tables that
 * [ScreenRenderer] consumes each frame. `internal` to the render module so the engine
 * `SimState` doesn't have to expose game-domain convenience getters.
 */
internal val SimState.transforms get() = components.getTable<TransformComponent>()
internal val SimState.colliders get() = components.getTable<ColliderComponent>()
internal val SimState.renderShapes get() = components.getTable<RenderShapeComponent>()
internal val SimState.particles get() = components.getTable<ParticleComponent>()
internal val SimState.forceFields get() = components.getTable<ForceFieldComponent>()
