package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.model.setContacts
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.PhysicsInput

/**
 * Producer of the `contactDetect` phase. Scans all pairs of material-bearing entities,
 * computes contacts, and publishes the full list as a typed phase output via
 * [setContacts]. Downstream phases ([BounceSystem], [CrashSystem], [LandingSystem],
 * [DrocketLandingSystem][org.emerge.demo.drockets.DrocketLandingSystem]) read it as
 * an immutable `List<Contact>` and never mutate it.
 */
object ContactSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        builder: PhysicsBuilder,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val ids = builder.entries<MaterialComponent>().keys.toList()
        val contacts = mutableListOf<Contact>()
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
                    contacts.add(contact)
                }
            }
        }
        builder.setContacts(contacts)
    }
}
