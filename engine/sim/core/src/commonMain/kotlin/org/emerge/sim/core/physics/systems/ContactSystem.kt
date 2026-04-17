package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.PhysicsInput


object ContactSystem : EcsSystem<PhysicsConfig, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val ids = builder.initial.raw.materials.keys().toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                val aTransform = builder.getComponent<TransformComponent>(aId) ?: continue
                val bTransform = builder.getComponent<TransformComponent>(bId) ?: continue
                val aCollider = builder.getComponent<ColliderComponent>(aId) ?: continue
                val bCollider = builder.getComponent<ColliderComponent>(bId) ?: continue

                val contact = Contact.compute(
                    aId = aId,
                    bId = bId,
                    aTransform = aTransform,
                    bTransform = bTransform,
                    aRadius = aCollider.radius,
                    bRadius = bCollider.radius,
                )
                if (contact != null) {
                    builder.addContact(contact)
                }
            }
        }
    }
}
