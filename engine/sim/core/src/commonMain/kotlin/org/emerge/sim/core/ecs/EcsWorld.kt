package org.emerge.sim.core.ecs

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId

data class EcsWorld(
    private val entities: MutableSet<Int> = mutableSetOf(),
    private var lastEntityValue: Int = 0,
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

    companion object {
        val EMPTY = EcsWorld()
    }
}

fun interface EcsSystem<C, S, I> {
    fun update(cfg: C, state: S, inputs: Map<PlayerId, I>)
}

object EcsSystems {
    fun <C, S, I> runAll(
        cfg: C,
        state: S,
        inputs: Map<PlayerId, I>,
        systems: List<EcsSystem<C, S, I>>,
    ) {
        for (system in systems) {
            system.update(cfg, state, inputs)
        }
    }
}
