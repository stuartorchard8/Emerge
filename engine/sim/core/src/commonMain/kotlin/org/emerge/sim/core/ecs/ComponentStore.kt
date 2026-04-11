package org.emerge.sim.core.ecs

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
        fun build() = map.toMap()
    }
}
