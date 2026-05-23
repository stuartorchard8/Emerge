package org.emerge.render.torus

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.PlanetComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Coord2

/**
 * Typed-getter extensions for the engine's torus renderer. These are temporary
 * conveniences: the renderer is still coupled to [PhysicsState], and decoupling it
 * (Move 8) will let demos pass component tables / focus positions directly. Until
 * then, keep these in the render module so the engine's [PhysicsState] stays
 * game-agnostic.
 */
internal val PhysicsState.transforms get() = components.getTable<TransformComponent>()
internal val PhysicsState.colliders get() = components.getTable<ColliderComponent>()
internal val PhysicsState.renderShapes get() = components.getTable<RenderShapeComponent>()
internal val PhysicsState.particles get() = components.getTable<ParticleComponent>()
internal val PhysicsState.teams get() = components.getTable<TeamComponent>()
internal val PhysicsState.planets get() = components.getTable<PlanetComponent>()
internal val PhysicsState.forceFields get() = components.getTable<ForceFieldComponent>()

/**
 * View focus for the renderer. Returns the player's current world position if known,
 * otherwise the world origin. Demos that need a different focus override the renderer's
 * `viewFocusOverride` field directly.
 */
internal fun PhysicsState.rendererViewFocus(playerId: PlayerId): Coord2 {
    val entityId = playerEntities[playerId] ?: return Coord2.zero
    return transforms[entityId]?.pos ?: Coord2.zero
}
