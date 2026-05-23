package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.model.PhysicsBuilder

import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.contacts
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput

object DrocketLandingSystem : EcsSystem<DrocketsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: DrocketsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val drocketStates = builder.entries<DrocketStateComponent>()
        for ((entityId, ds) in drocketStates) {
            if (ds.phase != DrocketPhase.FLYING) continue
            val motion = builder.getComponent<MotionComponent>(entityId) ?: continue
            val contacts = builder.contacts.filter { it.aId == entityId || it.bId == entityId }
            for (contact in contacts) {
                val otherId = if (entityId == contact.aId) contact.bId else contact.aId
                val otherMaterial = builder.getComponent<MaterialComponent>(otherId) ?: continue
                val otherMass = otherMaterial.mass
                if (otherMass < PLANET_MASS) continue
                val otherMotion = builder.getComponent<MotionComponent>(otherId) ?: continue

                // Must be colliding with a planet - now are we slow enough to land?
                val surfaceVelocity = otherMotion.surfaceVelocityAtOffset(-contact.normal, contact.minDist)
                val relativeVelocity = motion.vel - surfaceVelocity
                if (relativeVelocity < Frac(1, 1 shl 15)) {
                    val landingNormal = if (entityId == contact.aId) contact.normal else -contact.normal
                    val globalAngle = landingNormal.asAngle
                    val otherTransform = builder.getComponent<TransformComponent>(otherId) ?: continue
                    val otherCollider = builder.getComponent<ColliderComponent>(otherId) ?: continue
                    val collider = builder.getComponent<ColliderComponent>(entityId) ?: continue
                    val minDist = collider.radius + otherCollider.radius
                    val newLanding = LandingAttachmentComponent(
                        parentEntityId = otherId,
                        relativePos = (landingNormal * minDist).rotateByAngle(Coord(-otherTransform.ang.raw)),
                        relativeAng = globalAngle - otherTransform.ang,
                    )
                    builder.update<LandingAttachmentComponent>(entityId) { newLanding }
                    break
                }
            }
        }
    }
}
