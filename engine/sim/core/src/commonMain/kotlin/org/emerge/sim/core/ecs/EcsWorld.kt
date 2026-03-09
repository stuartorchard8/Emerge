package org.emerge.sim.core.ecs

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId

data class EcsWorld(
    val entities: List<EntityId> = emptyList(),
    val nextEntityValue: Int = 0,
) {
    fun createEntity(): Pair<EcsWorld, EntityId> {
        val entityId = EntityId(nextEntityValue)
        return copy(
            entities = entities + entityId,
            nextEntityValue = nextEntityValue + 1,
        ) to entityId
    }

    fun ensureEntity(entityId: EntityId): EcsWorld {
        if (entities.contains(entityId)) {
            return this
        }
        val nextValue = maxOf(nextEntityValue, entityId.value + 1)
        return copy(
            entities = entities + entityId,
            nextEntityValue = nextValue,
        )
    }

    fun removeEntity(entityId: EntityId): EcsWorld {
        if (!entities.contains(entityId)) {
            return this
        }
        return copy(entities = entities.filterNot { it == entityId })
    }

    companion object {
        val EMPTY = EcsWorld()
    }
}

fun interface EcsSystem<C, S, I> {
    fun update(cfg: C, state: S, inputs: Map<PlayerId, I>): S
}

object EcsSystems {
    fun <C, S, I> runAll(
        cfg: C,
        initialState: S,
        inputs: Map<PlayerId, I>,
        systems: List<EcsSystem<C, S, I>>,
    ): S {
        var state = initialState
        for (system in systems) {
            state = system.update(cfg, state, inputs)
        }
        return state
    }
}
