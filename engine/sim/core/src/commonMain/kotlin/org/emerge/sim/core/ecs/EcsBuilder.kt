package org.emerge.sim.core.ecs

import org.emerge.sim.core.EntityId
import kotlin.reflect.KClass

/**
 * Domain-agnostic scratchpad for building a new snapshot of type [S] from an [initial] one.
 *
 * The builder owns two kinds of mutable frame state:
 *
 *  1. **Component tables.** Reads overlay [workingData] / [tombstones] on top of the component
 *     store of [initial]; writes go exclusively to the scratchpad. [build] merges them back
 *     into a final [ComponentStore] and hands it to [applyComponents] to produce the new [S].
 *
 *  2. **Typed scratches.** Callers register domain-specific side-bags of type `T` via [scratch].
 *     Each scratch provides a finalize lambda that runs in [build] to fold its accumulated
 *     data into the final state. This is the agnostic pathway physics (or any other domain)
 *     uses to carry frame-scoped collections such as contacts and audio events without
 *     coupling the builder itself to those types.
 *
 * The builder knows nothing about specific component classes or domain state shapes; all
 * coupling flows through the two constructor lenses and the caller-supplied scratch
 * finalizers.
 */
class EcsBuilder<S>(
    val initial: S,
    @PublishedApi internal val getComponents: (S) -> ComponentStore,
    @PublishedApi internal val applyComponents: (S, ComponentStore) -> S,
) {
    // --- Component scratchpad -------------------------------------------

    /**
     * Per-type overlay of entries written via [update] or [setTable].
     * Merged on top of [initial]'s store in [build], unless the type is in
     * [authoritativeTypes] in which case this IS the final table.
     */
    val workingData = mutableMapOf<KClass<*>, MutableMap<EntityId, Any>>()

    /**
     * Per-type set of entity ids whose component should be absent from the final frame,
     * masking any value present in [initial]. Populated by [remove], cleared by [update]
     * and by [setTable] (authoritative replacement supersedes tombstones).
     */
    val tombstones = mutableMapOf<KClass<*>, MutableSet<EntityId>>()

    /**
     * Types for which [setTable] was called this frame. For these, [workingData] IS the
     * authoritative full table — anything in [initial] that isn't in the scratchpad is gone.
     */
    val authoritativeTypes = mutableSetOf<KClass<*>>()

    // --- Typed scratches -----------------------------------------------

    @PublishedApi internal val scratches = mutableMapOf<KClass<*>, Any>()
    @PublishedApi internal val finalizers = mutableListOf<(S) -> S>()

    /**
     * Returns (creating lazily on first access) a scratch object of type [T].
     *
     * The first call constructs the scratch via [factory] and registers [finalize]
     * to run in [build], giving the scratch a chance to fold its accumulated data
     * into the final [S]. Later calls return the same instance without re-registering.
     *
     * Use this to carry domain-specific frame-scoped state (e.g. contact lists,
     * audio events) without the builder needing to know about those types.
     */
    inline fun <reified T : Any> scratch(
        noinline factory: () -> T,
        noinline finalize: S.(T) -> S,
    ): T = registerScratch(T::class, factory, finalize)

    @PublishedApi
    internal fun <T : Any> registerScratch(
        type: KClass<T>,
        factory: () -> T,
        finalize: S.(T) -> S,
    ): T {
        scratches[type]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as T
        }
        val created = factory()
        scratches[type] = created
        finalizers.add { state -> state.finalize(created) }
        return created
    }

    // --- Component read/write API ---------------------------------------

    /**
     * Gets the latest version of a component for an entity this frame:
     * tombstoned entries return null; scratchpad entries win over initial;
     * authoritative types disable the initial fallback.
     */
    inline fun <reified T : Any> getComponent(id: EntityId): T? {
        if (tombstones[T::class]?.contains(id) == true) return null
        val frameWork = workingData[T::class]?.get(id) as? T
        if (frameWork != null) return frameWork
        if (T::class in authoritativeTypes) return null
        return getComponents(initial).getTable<T>()[id]
    }

    /**
     * Returns the merged initial + scratchpad view for [T], with tombstones and authoritative
     * replacements applied. This is the **only** iteration path systems should use — it
     * always reflects writes made by earlier systems in the current frame.
     *
     * The returned map is a fresh copy; mutating it is safe and does not affect the builder.
     */
    inline fun <reified T : Any> entries(): Map<EntityId, T> {
        val initialEntries: Map<EntityId, T> =
            if (T::class in authoritativeTypes) emptyMap()
            else getComponents(initial).getTable<T>().asMap()
        val work = workingData[T::class]
        val tombs = tombstones[T::class]
        if (work.isNullOrEmpty() && tombs.isNullOrEmpty()) return initialEntries

        val merged = LinkedHashMap<EntityId, T>(initialEntries.size + (work?.size ?: 0))
        merged.putAll(initialEntries)
        if (work != null) {
            @Suppress("UNCHECKED_CAST")
            merged.putAll(work as Map<EntityId, T>)
        }
        tombs?.forEach { merged.remove(it) }
        return merged
    }

    /**
     * Stacks or updates a component safely.
     * Usage: builder.update<ImpulseComponent>(id) { it + thrust }
     */
    inline fun <reified T : Any> update(id: EntityId, crossinline block: (T?) -> T) {
        val current = getComponent<T>(id)
        val table = workingData.getOrPut(T::class) { mutableMapOf() }
        table[id] = block(current)
        tombstones[T::class]?.remove(id)
    }

    /**
     * Removes a component safely. After this call, [getComponent] returns null for the
     * entity regardless of whether the value lived in the initial snapshot.
     */
    inline fun <reified T : Any> remove(id: EntityId) {
        workingData[T::class]?.remove(id)
        tombstones.getOrPut(T::class) { mutableSetOf() }.add(id)
    }

    /**
     * Overwrites a whole table authoritatively. Initial entries not present in [table]
     * are dropped from the final frame; subsequent [update] calls add to this table,
     * [remove] calls tombstone from it.
     */
    inline fun <reified T : Any> setTable(table: MutableMap<EntityId, T>) {
        @Suppress("UNCHECKED_CAST")
        workingData[T::class] = table as MutableMap<EntityId, Any>
        authoritativeTypes.add(T::class)
        tombstones.remove(T::class)
    }

    // --- Finalize -------------------------------------------------------

    /**
     * Freezes the scratchpad into a new [S]: merges component tables, applies them via
     * [applyComponents], then runs registered scratch finalizers in registration order.
     */
    fun build(): S {
        val initialComponents = getComponents(initial)
        val finalTables = initialComponents.tables.toMutableMap()

        val touchedTypes = workingData.keys + tombstones.keys + authoritativeTypes
        for (type in touchedTypes) {
            finalTables[type] = if (type in authoritativeTypes) {
                buildTable(type, workingData[type]?.toMap() ?: emptyMap())
            } else {
                val initialEntries = finalTables[type]?.asMap() ?: emptyMap()
                val merged = LinkedHashMap<EntityId, Any>(initialEntries.size)
                @Suppress("UNCHECKED_CAST")
                merged.putAll(initialEntries as Map<EntityId, Any>)
                workingData[type]?.let { merged.putAll(it) }
                tombstones[type]?.forEach { merged.remove(it) }
                buildTable(type, merged)
            }
        }

        var state = applyComponents(initial, ComponentStore(finalTables.toMap()))
        for (finalizer in finalizers) state = finalizer(state)
        return state
    }

    @PublishedApi
    internal fun buildTable(type: KClass<*>, values: Map<EntityId, Any>): ComponentTable<*> {
        @Suppress("UNCHECKED_CAST")
        return ComponentTable(type as KClass<Any>, values)
    }
}
