package org.emerge.demo.cyto.sim.systems

import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.sim.SimBuilder

/**
 * Spring/connection helpers shared by the contact, lifecycle, and interaction systems.
 * A connection is stored symmetrically on both endpoints (as engine
 * [SpringConstraintComponent] entries) plus a [ConnectionStateComponent] damage entry, so
 * either side can read its neighbours and break the link.
 */
fun springExists(builder: SimBuilder, a: EntityId, b: EntityId): Boolean =
    builder.getComponent<SpringConstraintComponent>(a)?.springs?.any { it.other == b } == true

fun addSpring(builder: SimBuilder, a: EntityId, b: EntityId, cfg: CytoConfig, initialDamage: Float = 0f) {
    if (a == b) return
    val ra = builder.getComponent<ColliderComponent>(a)?.radius ?: return
    val rb = builder.getComponent<ColliderComponent>(b)?.radius ?: return
    val rest = ra + rb
    attachSpring(builder, a, b, rest, cfg, initialDamage)
    attachSpring(builder, b, a, rest, cfg, initialDamage)
}

private fun attachSpring(builder: SimBuilder, owner: EntityId, other: EntityId, rest: org.emerge.sim.core.physics.primitives.Frac, cfg: CytoConfig, initialDamage: Float) {
    builder.update<SpringConstraintComponent>(owner) { cur ->
        val list = cur?.springs ?: emptyList()
        if (list.any { it.other == other }) {
            cur ?: SpringConstraintComponent(list)
        } else {
            SpringConstraintComponent(list + SpringConstraint(other, rest, cfg.springStiffness, cfg.springDamping))
        }
    }
    builder.update<ConnectionStateComponent>(owner) { cur ->
        val damage = cur?.damage ?: emptyMap()
        // A brand-new connection is born at [initialDamage] ("0 health" = breakDamage for a Repair-weld);
        // an existing one keeps its current damage. Symmetric on both endpoints.
        ConnectionStateComponent(damage + (other to (damage[other] ?: initialDamage)))
    }
}

fun removeSpringPair(builder: SimBuilder, a: EntityId, b: EntityId) {
    detachSpring(builder, a, b)
    detachSpring(builder, b, a)
}

private fun detachSpring(builder: SimBuilder, owner: EntityId, other: EntityId) {
    builder.update<SpringConstraintComponent>(owner) { cur ->
        SpringConstraintComponent((cur?.springs ?: emptyList()).filter { it.other != other })
    }
    builder.update<ConnectionStateComponent>(owner) { cur ->
        ConnectionStateComponent((cur?.damage ?: emptyMap()).filterKeys { it != other })
    }
}

/** Neighbours of [id] (the other endpoint of each spring). */
fun neighboursOf(builder: SimBuilder, id: EntityId): List<EntityId> =
    builder.getComponent<SpringConstraintComponent>(id)?.springs?.map { it.other } ?: emptyList()
