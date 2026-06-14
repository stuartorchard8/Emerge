package org.emerge.sim.core.ecs

import org.emerge.sim.core.EntityId

data class EcsWorld(
    private val entities: MutableSet<Int> = mutableSetOf(),
    var lastEntityValue: Int = 0,
) {
    fun createEntity(): EntityId {
        while (entities.contains(lastEntityValue)) {
            lastEntityValue += 1
        }

        entities += lastEntityValue
        return EntityId(lastEntityValue)
    }

    fun ensureEntity(entityId: EntityId) {
        if (!entities.contains(entityId.value)) {
            entities += entityId.value
        }
    }

    fun removeEntity(entityId: EntityId) {
        if (entities.contains(entityId.value)) {
            entities -= entityId.value
        }
    }
    // No shared `EMPTY` singleton: this type is mutated in place (createEntity/removeEntity), so a shared
    // instance would leak entity-allocation state across independent worlds. SimState.world defaults to a
    // fresh EcsWorld() per construction instead.
}
