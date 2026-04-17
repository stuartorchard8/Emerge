package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.ComponentTable
import kotlin.reflect.KClass

class PhysicsBuilder(val initial: PhysicsState) {
    // Stores the "New" data for the current frame
    // Map<ComponentClass, Map<EntityId, ComponentInstance>>
    val workingData = mutableMapOf<KClass<*>, MutableMap<EntityId, Any>>()

    /**
     * The Workhorse: Gets the latest version (from this frame)
     * or falls back to the initial state.
     */
    inline fun <reified T : Any> getComponent(id: EntityId): T? {
        val frameWork = workingData[T::class]?.get(id) as? T
        if (frameWork != null) return frameWork
        return initial.raw.components.getTable<T>()[id]
    }

    /**
     * Stacks or updates a component safely.
     * Usage: builder.update<ImpulseComponent>(id) { it + thrust }
     */
    inline fun <reified T : Any> update(id: EntityId, crossinline block: (T?) -> T) {
        val table = workingData.getOrPut(T::class) { mutableMapOf() }
        val current = getComponent<T>(id)

        table[id] = block(current)
    }

    /**
     * Removes a component safely.
     * Usage: builder.remove<ImpulseComponent>(id)
     */
    inline fun <reified T : Any> remove(id: EntityId) {
        val table = workingData.getOrPut(T::class) { mutableMapOf() }
        table.remove(id)
    }

    /**
     * Overwrites a whole table (good for the ContactSystem).
     */
    inline fun <reified T : Any> setTable(table: MutableMap<EntityId, T>) {
        @Suppress("UNCHECKED_CAST")
        workingData[T::class] = table as MutableMap<EntityId, Any>
    }

    /**
     * Freezes the scratchpad back into an immutable Snapshot.
     */
    fun build(): PhysicsState {
        val finalTables = initial.raw.components.tables.toMutableMap()

        workingData.forEach { (type, mutableMap) ->
            finalTables[type] = ComponentTable.fromMap( mutableMap.toMap())
        }

        return initial.copy(
            raw = initial.raw.copy(
                components = ComponentStore(finalTables.toMap())
            )
        )
    }
}
