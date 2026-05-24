package org.emerge.sim.core.physics.model

import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.primitives.Contact

/**
 * Engine-side simulation snapshot. Domain-agnostic: contains only the ECS world, component
 * tables, the per-tick contact list, and the deterministic PRNG seed. Game-specific frame
 * state (respawn queues, audio events, player-entity indexes, etc.) lives in demo-side
 * wrapper states.
 */
data class PhysicsState(
    val world: EcsWorld = EcsWorld.EMPTY,
    val components: ComponentStore = ComponentStore(),
    val contacts: List<Contact> = emptyList(),
    /**
     * Deterministic PRNG state carried across ticks.
     * Must be kept in sync across all lockstep peers — never seed from platform Random.
     * Serialized alongside the snapshot for Welcome/Resync.
     */
    val randomSeed: Long = 0,
)
