package org.emerge.sim.core.ecs

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore.Builder
import kotlin.reflect.KClass

data class ComponentStore(
    val tables: Map<KClass<*>, ComponentTable<*>> = emptyMap()
) {
    inline fun <reified T : Any> getTable(): ComponentTable<T> {
        @Suppress("UNCHECKED_CAST")
        return (tables[T::class] as? ComponentTable<T>) ?: ComponentTable.empty()
    }

    inline fun update(block: Builder.() -> Unit): ComponentStore {
        val builder = Builder(tables.toMutableMap())
        builder.block()
        return copy(tables = builder.build())
    }

    class Builder(val map: MutableMap<KClass<*>, ComponentTable<*>>) {
        inline fun <reified T : Any> set(table: ComponentTable<T>) {
            map[T::class] = table
        }

        fun setRaw(id: EntityId, component: Any) {
            val type = component::class
            @Suppress("UNCHECKED_CAST")
            val existingTable = map[type] as? ComponentTable<Any>
                ?: ComponentTable.empty()

            map[type] = existingTable.put(id, component)
        }

        fun build() = map.toMap()
    }
}
