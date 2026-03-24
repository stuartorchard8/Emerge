package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.PhysicsConfig
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
        val transforms = LinkedHashMap(state.raw.transforms.asMap())
        val motions = LinkedHashMap(state.raw.motions.asMap())
        val landings = LinkedHashMap(state.raw.landings.asMap())
        for ((entityId, landing) in state.raw.landings.entries()) {
            val parentTransform = transforms[landing.parentEntityId]
            val parentMotion = motions[landing.parentEntityId]
            val transform = transforms[entityId]
            if (parentTransform == null || parentMotion == null || transform == null) {
                landings.remove(entityId)
                continue
            }
            transforms[entityId] = transform.copy(
                pos = parentTransform.pos + landing.relativePos.rotateByAngle(parentTransform.ang),
                ang = parentTransform.ang + landing.relativeAng,
            )
            motions[entityId] = parentMotion
        }
        state.raw = state.raw.copy(
            transforms = ComponentTable.fromMap(transforms),
            motions = ComponentTable.fromMap(motions),
            landings = ComponentTable.fromMap(landings),
        )
    }
}