package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.components.PlayerOwnedComponent
import org.emerge.sim.core.physics.primitives.Contact

/**
 * Engine-side simulation snapshot. Domain-agnostic: contains only the ECS world, component
 * tables, the per-tick contact list, and the deterministic PRNG seed. Game-specific frame
 * state (respawn queues, audio events, etc.) lives in demo-side wrapper states.
 */
data class PhysicsState(
    val world: EcsWorld = EcsWorld.EMPTY,
    val playerEntities: Map<PlayerId, EntityId> = emptyMap(),
    val components: ComponentStore = ComponentStore(),
    val contacts: List<Contact> = emptyList(),
    /**
     * Deterministic PRNG state carried across ticks.
     * Must be kept in sync across all lockstep peers — never seed from platform Random.
     * Serialized alongside the snapshot for Welcome/Resync.
     */
    val randomSeed: Long = 0,
) {
    fun rebuildIndexes(): PhysicsState {
        val playerOwnedTable = components.getTable<PlayerOwnedComponent>()

        // Use the power of the map to build the reverse index in one pass
        val newPlayerEntities = playerOwnedTable.entries().associate { (id, comp) ->
            comp.playerId to id
        }

        return copy(playerEntities = newPlayerEntities)
    }
}
