package org.emerge.sim.core.ecs

import org.emerge.sim.core.EntityId
import kotlin.reflect.KClass

data class ComponentTable<T : Any>(
    val type: KClass<T>,
    private val values: Map<EntityId, T> = emptyMap(),
) {
    operator fun get(entityId: EntityId): T? = values[entityId]

    fun contains(entityId: EntityId): Boolean = values.containsKey(entityId)

    fun put(entityId: EntityId, value: T): ComponentTable<T> {
        val next = LinkedHashMap(values)
        next[entityId] = value
        return copy(values=next)
    }

    fun putAll(entries: Iterable<Pair<EntityId, T>>): ComponentTable<T> {
        val next = LinkedHashMap(values)
        for ((entityId, value) in entries) {
            next[entityId] = value
        }
        return copy(values=next)
    }

    fun remove(entityId: EntityId): ComponentTable<T> {
        if (!values.containsKey(entityId)) {
            return this
        }
        val next = LinkedHashMap(values)
        next.remove(entityId)
        return copy(values=next)
    }

    fun removeAll(entityIds: Iterable<EntityId>): ComponentTable<T> {
        val removals = entityIds.toSet()
        if (removals.isEmpty()) {
            return this
        }
        val next = LinkedHashMap(values)
        for (entityId in removals) {
            next.remove(entityId)
        }
        return copy(values=next)
    }

    fun asMap(): Map<EntityId, T> = values

    fun entries(): Set<Map.Entry<EntityId, T>> = values.entries
    fun keys(): Set<EntityId> = values.keys

    fun isEmpty(): Boolean = values.isEmpty()

    companion object {
        inline fun <reified T : Any> empty(): ComponentTable<T> = ComponentTable(T::class)

        /**
         * Wrap a map that is already fully built, avoiding repeated copy-on-write churn while decoding
         * or batch-building tables. Callers must not mutate the map after handing it over.
         */
        inline fun <reified T : Any> fromMap(values: Map<EntityId, T>): ComponentTable<T> = ComponentTable(T::class, values)
    }
}
