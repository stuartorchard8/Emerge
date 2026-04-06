package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Moves walking drockets along the planet surface by rotating their
 * [LandingAttachmentComponent.relativePos] and [relativeAng] each tick.
 *
 * Godot reference: bearing += delta * walkDirection * maxSpeed / planet.radius
 * At 60 tps with maxSpeed=300, planet.radius=10000: ≈ 0.0005 rad/tick
 * In Emerge Coord space: 0.0005/π * Int.MAX_VALUE ≈ 341,782
 */
object WalkSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val drocketStates = DrocketsRegistry.drocketStates
        if (drocketStates.isEmpty()) return

        val landings = LinkedHashMap(state.raw.landings.asMap())
        var changed = false

        for ((entityId, ds) in drocketStates) {
            if (ds.phase != DrocketPhase.WALKING) continue
            val landing = landings[entityId] ?: continue
            val parentCollider = state.raw.colliders[landing.parentEntityId] ?: continue

            // Scale walk speed inversely with planet radius for consistent visual speed
            val walkStep = walkStepForRadius(parentCollider.radius)
            val angularDelta = Frac(ds.walkDirection.toLong() * walkStep)

            val rotatedPos = landing.relativePos.rotateByAngle(Coord(angularDelta.raw.toInt()))
            val newRelativeAng = landing.relativeAng + angularDelta

            landings[entityId] = landing.copy(
                relativePos = rotatedPos,
                relativeAng = newRelativeAng,
            )
            changed = true
        }

        if (changed) {
            state.setLandings(ComponentTable.fromMap(landings))
        }
    }

    /**
     * Godot: bearing += delta * walkDirection * 300 / planet.radius
     * At 60 tps: step = 300 / (60 * radius_in_godot_units)
     * We scale the angular step inversely with the planet's collision radius.
     */
    private fun walkStepForRadius(planetRadius: Frac): Long {
        // Base step for the default planet radius (~Frac(100,4000) = Frac(1,40))
        // Walk speed is tuned so drockets visibly walk along the surface
        val baseStep = 400_000L
        val defaultRadiusRaw = Frac(1, 40).raw
        if (planetRadius.raw <= 0) return baseStep
        return baseStep * defaultRadiusRaw / planetRadius.raw
    }
}
