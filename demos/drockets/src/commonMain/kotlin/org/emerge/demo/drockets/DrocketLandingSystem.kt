package org.emerge.demo.drockets

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput

object DrocketLandingSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val drocketStates = state.raw.components.getTable<DrocketStateComponent>().entries()
        val landings = LinkedHashMap(state.raw.landings.asMap())
        for ((entityId, ds) in drocketStates) {
            if (ds.phase != DrocketPhase.FLYING) continue
            val motion = state.raw.motions[entityId] ?: continue
            val contacts = state.raw.contacts.filter { it.aId == entityId || it.bId == entityId }
            for (contact in contacts) {
                val otherId = if (entityId == contact.aId) contact.bId else contact.aId
                val otherMaterial = state.raw.materials[otherId] ?: continue
                val otherMass = otherMaterial.mass
                if (otherMass < PLANET_MASS) continue
                val otherMotion = state.raw.motions[otherId] ?: continue

                // Must be colliding with a planet - now are we slow enough to land?
                val relativeVelocity = motion.vel - otherMotion.vel
                if (relativeVelocity < Frac(1, 1024*8)) {
                    val landingNormal = if (entityId == contact.aId) contact.normal else -contact.normal
                    val globalAngle = landingNormal.asAngle
                    val otherTransform = state.raw.transforms[otherId] ?: continue
                    val otherCollider = state.raw.colliders[otherId] ?: continue
                    val collider = state.raw.colliders[entityId] ?: continue
                    val minDist = collider.radius + otherCollider.radius
                    landings[entityId] = LandingAttachmentComponent(
                        parentEntityId = otherId,
                        relativePos = (landingNormal * minDist).rotateByAngle(Coord(-otherTransform.ang.raw)),
                        relativeAng = globalAngle - otherTransform.ang,
                    )
                    break
                }
            }
        }
        state.setLandings(ComponentTable.fromMap(landings))
    }
}