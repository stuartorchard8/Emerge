package org.emerge.demo.drockets

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Applies velocity-squared atmospheric drag to non-landed triangle entities
 * when inside the atmosphere of a nearby planet.
 *
 * Godot reference:
 *   depth = 1 - elevation / atmosphereHeight
 *   drag = vel² * sign(vel) * depth² * -0.00001
 *
 * The atmosphere extends [ATMOSPHERE_FACTOR] beyond the planet collider radius.
 */
object AtmosphereDragSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {

    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()

        val planetIds = builder.entries<PlanetComponent>().keys.toList()
        if (planetIds.isEmpty()) return

        for ((entityId, renderShape) in builder.entries<RenderShapeComponent>()) {
            if (renderShape.shape != BodyShape.TRIANGLE) continue
            if (builder.getComponent<LandingAttachmentComponent>(entityId) != null) continue
            val transform = builder.getComponent<TransformComponent>(entityId) ?: continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue

            for (planetId in planetIds) {
                val planetTransform = builder.getComponent<TransformComponent>(planetId) ?: continue
                val planetCollider = builder.getComponent<ColliderComponent>(planetId) ?: continue
                val planetMotion = builder.getComponent<MotionComponent>(planetId) ?: continue

                val delta = transform.pos - planetTransform.pos
                val dist = delta.len
                val surfaceRadius = planetCollider.radius
                val atmosphereRadius = surfaceRadius + ATMOSPHERE_DEPTH
                if (dist.raw <= surfaceRadius.raw || dist.raw >= atmosphereRadius.raw) continue

                val elevation = dist - surfaceRadius
                val depthFrac = Frac(1, 1) - elevation / ATMOSPHERE_DEPTH
                if (depthFrac.raw <= 0) continue

                val airspeed = motion.vel - planetMotion.surfaceVelocityAtOffset(delta.norm, dist)

                // drag = -vel * |vel| * depth² * DRAG_COEFFICIENT
                val depth2 = depthFrac * depthFrac
                val dragX = airspeed.x * Frac.abs(airspeed.x) * depth2 * DRAG_COEFFICIENT
                val dragY = airspeed.y * Frac.abs(airspeed.y) * depth2 * DRAG_COEFFICIENT
                val drag = ImpulseComponent(vel = Frac2(-dragX, -dragY))
                impulses[entityId] = impulses[entityId]?.plus(drag) ?: drag
            }
        }

        for ((entityId, impulse) in impulses) {
            builder.update<ImpulseComponent>(entityId) { impulse + it }
        }
    }

    // Atmosphere extends this far above the planet surface (in Frac units)
    private val ATMOSPHERE_DEPTH = Frac(1, 64)
    // Drag coefficient -- tuned so orbiting drockets slow noticeably in atmosphere
    private val DRAG_COEFFICIENT = Frac(128, 1)
}
