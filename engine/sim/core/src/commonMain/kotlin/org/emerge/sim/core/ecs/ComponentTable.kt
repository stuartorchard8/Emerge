package org.emerge.sim.core.ecs

import org.emerge.sim.core.EntityId

data class ComponentTable<T>(
    private val values: Map<EntityId, T> = emptyMap(),
) {
    operator fun get(entityId: EntityId): T? = values[entityId]

    fun contains(entityId: EntityId): Boolean = values.containsKey(entityId)

    fun put(entityId: EntityId, value: T): ComponentTable<T> {
        val next = LinkedHashMap(values)
        next[entityId] = value
        return ComponentTable(next)
    }

    fun putAll(entries: Iterable<Pair<EntityId, T>>): ComponentTable<T> {
        val next = LinkedHashMap(values)
        for ((entityId, value) in entries) {
            next[entityId] = value
        }
        return ComponentTable(next)
    }

    fun remove(entityId: EntityId): ComponentTable<T> {
        if (!values.containsKey(entityId)) {
            return this
        }
        val next = LinkedHashMap(values)
        next.remove(entityId)
        return ComponentTable(next)
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
        return ComponentTable(next)
    }

    fun asMap(): Map<EntityId, T> = values

    fun entries(): Set<Map.Entry<EntityId, T>> = values.entries
    fun keys(): Set<EntityId> = values.keys

    fun isEmpty(): Boolean = values.isEmpty()

    companion object {
        fun <T> empty(): ComponentTable<T> = ComponentTable()

        /**
         * Wrap a map that is already fully built, avoiding repeated copy-on-write churn while decoding
         * or batch-building tables. Callers must not mutate the map after handing it over.
         */
        fun <T> fromMap(values: Map<EntityId, T>): ComponentTable<T> = ComponentTable(values)
    }
}
