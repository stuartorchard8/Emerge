package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.components.ImpulseComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.PhysicsInput
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

object AttachmentSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val impulses = LinkedHashMap<EntityId, ImpulseComponent>()
        val landings = LinkedHashMap(state.raw.landings.asMap())
        for ((entityId, landing) in state.raw.landings.entries()) {
            val parentTransform = state.raw.transforms[landing.parentEntityId]
            val parentMotion = state.raw.motions[landing.parentEntityId]
            val transform = state.raw.transforms[entityId]
            val motion = state.raw.motions[entityId]
            if (parentTransform == null || parentMotion == null || transform == null || motion == null) {
                landings.remove(entityId)
                continue
            }
            val outcome = TransformComponent(
                pos = parentTransform.pos + landing.relativePos.rotateByAngle(parentTransform.ang),
                ang = parentTransform.ang + landing.relativeAng,
            )
            val delta = ImpulseComponent(
                pos = outcome.pos - transform.pos,
                ang = outcome.ang - transform.ang,
                vel = parentMotion.vel - motion.vel,
            )
            impulses[entityId] = delta
        }

        state.addImpulses(impulses)
        state.setLandings(ComponentTable.fromMap(landings))
    }
}