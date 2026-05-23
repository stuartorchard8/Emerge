package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac


/**
 * Moves walking drockets along the planet surface by rotating their
 * [LandingAttachmentComponent.relativePos] and [relativeAng] each tick.
 *
 * Godot reference: bearing += delta * walkDirection * maxSpeed / planet.radius
 * At 60 tps with maxSpeed=300, planet.radius=10000: ≈ 0.0005 rad/tick
 * In Emerge Coord space: 0.0005/π * Int.MAX_VALUE ≈ 341,782
 */
object DrocketWalkSystem : EcsSystem<DrocketsConfig, PhysicsState, DrocketsInput> {

    override fun update(
        cfg: DrocketsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, DrocketsInput>,
    ) {
        val drocketStates = builder.entries<DrocketStateComponent>()
        if (drocketStates.isEmpty()) return

        for ((entityId, ds) in drocketStates) {
            if (ds.phase != DrocketPhase.WALKING) continue
            val landing = builder.getComponent<LandingAttachmentComponent>(entityId) ?: continue
            val parentCollider = builder.getComponent<ColliderComponent>(landing.parentEntityId) ?: continue

            // Scale walk speed inversely with planet radius for consistent visual speed
            val walkStep = walkStepForRadius(parentCollider.radius)
            val angularDelta = walkStep * ds.walkDirection

            val rotatedPos = landing.relativePos.rotateByAngle(Coord(angularDelta.raw.toInt()))
            val newRelativeAng = landing.relativeAng + angularDelta

            val updated = landing.copy(
                relativePos = rotatedPos,
                relativeAng = newRelativeAng,
            )
            builder.update<LandingAttachmentComponent>(entityId) { updated }
        }
    }

    /**
     * Godot: bearing += delta * walkDirection * 300 / planet.radius
     * At 60 tps: step = 300 / (60 * radius_in_godot_units)
     * We scale the angular step inversely with the planet's collision radius.
     */
    private fun walkStepForRadius(planetRadius: Frac): Frac {
        // Base step for the default planet radius (~Frac(100,4000) = Frac(1,40))
        // Walk speed is tuned so drockets visibly walk along the surface
        val baseStep = DROCKET_RADIUS/16
        return baseStep / planetRadius.toCircumference()
    }
}
