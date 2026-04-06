package org.emerge.sim.core.physics.systems

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.EcsSystem
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput


object ContactSystem : EcsSystem<PhysicsConfig, PhysicsState, PhysicsInput> {
    override fun update(
        cfg: PhysicsConfig,
        state: PhysicsState,
        inputs: Map<PlayerId, PhysicsInput>,
    ) {
        val contacts = state.raw.contacts.toMutableList()
        val ids = state.raw.materials.keys().toList()
        for (i in 0 until ids.size) {
            for (j in i + 1 until ids.size) {
                val aId = ids[i]
                val bId = ids[j]
                val aTransform = state.raw.transforms[aId] ?: continue
                val bTransform = state.raw.transforms[bId] ?: continue
                val aCollider = state.raw.colliders[aId] ?: continue
                val bCollider = state.raw.colliders[bId] ?: continue

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

        state.setContacts(contacts)
    }
}
