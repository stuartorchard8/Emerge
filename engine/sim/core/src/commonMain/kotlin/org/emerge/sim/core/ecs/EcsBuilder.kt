@file:OptIn(BypassesStagedView::class)

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
    /**
     * The frozen start-of-frame state. Reading fields off this property bypasses the
     * staged overlay of writes made in the current frame — see [BypassesStagedView].
     *
     * Prefer [entries] / [getComponent] for normal reads. Opt in to this property only
     * when you specifically want the parallel-safe, order-independent, last-frame view.
     */
    @property:BypassesStagedView
    val initial: S,
    @PublishedApi internal val getComponents: (S) -> ComponentStore,
    @PublishedApi internal val getWorld: (S) -> EcsWorld,
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

    // --- Events --------------------------------------------------------

    /**
     * Per-type append-only event streams emitted by systems during the frame. Populated
     * by [emit], read by [events], and folded into the final [S] by whatever domain
     * code wants to publish them (typically from a [scratch] finalizer that pulls the
     * list out at build time).
     *
     * Events are frame-scoped — a fresh builder starts with empty streams. They are
     * the natural fit for "fire and forget" cross-system messages like audio cues,
     * damage notifications, or debug markers where multiple systems may contribute and
     * no reader cares about the relative order of emissions across systems beyond phase
     * barriers.
     */
    @PublishedApi internal val eventLists: MutableMap<KClass<*>, MutableList<Any>> =
        mutableMapOf()

    // --- Write log (fork replay) ---------------------------------------

    /**
     * When non-null, every mutation performed on this builder appends a replay closure
     * to this list. A [fork] sets this to an empty list so the fork's writes can be
     * replayed on the parent during [mergeFork]. Non-null outside fork contexts also
     * works (it just collects the log) but allocates unnecessarily, so default is null.
     */
    @PublishedApi internal var writeLog: MutableList<(EcsBuilder<S>) -> Unit>? = null

    // --- Fork parent pointer -------------------------------------------

    /**
     * Non-null on forks; points at the builder this fork was forked from. Used by
     * domain helpers that want to delegate "shared-resource" scratch operations
     * (e.g. a PRNG counter, a respawn queue) directly at the parent instead of
     * mutating a fork-local copy that would be lost on merge.
     *
     * Delegation is the right pattern for shared mutable *domain* state that doesn't
     * live in the component tables and can't be expressed as additive write-log
     * replay closures. Under sequential fork execution it's bit-identical to the
     * pre-fork behaviour because only one fork runs at a time. Under a future
     * multi-threaded dispatcher, delegated accessors will need synchronisation at
     * the domain layer.
     */
    @PublishedApi internal var parent: EcsBuilder<S>? = null

    /**
     * Returns (creating lazily on first access) a scratch object of type [T].
     *
     * The first call constructs the scratch via [factory] — passing the frozen
     * [initial] state so domain scratches can seed themselves from last-frame
     * persistent data without having to reach for the opt-in [initial] property —
     * and registers [finalize] to run in [build], giving the scratch a chance to
     * fold its accumulated data into the final [S]. Later calls return the same
     * instance without re-registering.
     *
     * Use this to carry domain-specific frame-scoped state (e.g. contact lists,
     * audio events) without the builder needing to know about those types.
     */
    inline fun <reified T : Any> scratch(
        noinline factory: (S) -> T,
        noinline finalize: S.(T) -> S,
    ): T = registerScratch(T::class, factory, finalize)

    @PublishedApi
    internal fun <T : Any> registerScratch(
        type: KClass<T>,
        factory: (S) -> T,
        finalize: S.(T) -> S,
    ): T {
        scratches[type]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as T
        }
        val created = factory(initial)
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
        @Suppress("UNCHECKED_CAST")
        applyUpdateRaw(T::class, id) { current -> block(current as T?) as Any }
    }

    /**
     * Removes a component safely. After this call, [getComponent] returns null for the
     * entity regardless of whether the value lived in the initial snapshot.
     */
    inline fun <reified T : Any> remove(id: EntityId) {
        applyRemoveRaw(T::class, id)
    }

    /**
     * Overwrites a whole table authoritatively. Initial entries not present in [table]
     * are dropped from the final frame; subsequent [update] calls add to this table,
     * [remove] calls tombstone from it.
     */
    inline fun <reified T : Any> setTable(table: MutableMap<EntityId, T>) {
        @Suppress("UNCHECKED_CAST")
        applySetTableRaw(T::class, table as MutableMap<EntityId, Any>)
    }

    // --- Events API ----------------------------------------------------

    /**
     * Appends [event] to the frame's event stream for type [T]. Any system may emit;
     * within a phase, the relative order of emits from a single system is preserved,
     * and across systems events interleave by the phase's execution order.
     *
     * Under isolated phases, each fork collects emits in its own stream and the
     * write-log replays them on the parent at the phase barrier in system-registration
     * order — so the final combined order is deterministic regardless of which fork
     * a thread happened to run on.
     *
     * Events do not participate in the component store. Domain code is responsible for
     * folding them into the final state (e.g. via a [scratch] finalizer that reads
     * [events] at build time).
     */
    inline fun <reified T : Any> emit(event: T) {
        applyEmitRaw(T::class, event)
    }

    /**
     * Returns a read-only view of all [T]-typed events emitted so far this frame.
     * The returned list is owned by the builder; callers must treat it as read-only.
     * Empty if nothing has been emitted for [T] yet.
     */
    inline fun <reified T : Any> events(): List<T> {
        @Suppress("UNCHECKED_CAST")
        return (eventLists[T::class] as List<T>?) ?: emptyList()
    }

    // --- Non-inline write pathway (write-log replay reuses this) ---------

    /**
     * Type-erased update that the inline [update] delegates to. Also used by [writeLog]
     * replay: a fork's recorded block captures the reified type as a [KClass] and the
     * block as `(Any?) -> Any`, and replays via this method.
     */
    @PublishedApi
    internal fun applyUpdateRaw(type: KClass<*>, id: EntityId, block: (Any?) -> Any) {
        val current = rawGetComponent(type, id)
        val table = workingData.getOrPut(type) { mutableMapOf() }
        table[id] = block(current)
        tombstones[type]?.remove(id)
        writeLog?.add { parent -> parent.applyUpdateRaw(type, id, block) }
    }

    @PublishedApi
    internal fun applyRemoveRaw(type: KClass<*>, id: EntityId) {
        workingData[type]?.remove(id)
        tombstones.getOrPut(type) { mutableSetOf() }.add(id)
        writeLog?.add { parent -> parent.applyRemoveRaw(type, id) }
    }

    @PublishedApi
    internal fun applySetTableRaw(type: KClass<*>, table: MutableMap<EntityId, Any>) {
        workingData[type] = table
        authoritativeTypes.add(type)
        tombstones.remove(type)
        writeLog?.add { parent -> parent.applySetTableRaw(type, table) }
    }

    @PublishedApi
    internal fun applyEmitRaw(type: KClass<*>, event: Any) {
        eventLists.getOrPut(type) { mutableListOf() }.add(event)
        writeLog?.add { parent -> parent.applyEmitRaw(type, event) }
    }

    /**
     * Type-erased read that honours tombstones, the working overlay, and authoritative
     * flags — matching the inline [getComponent] but without a reified type parameter.
     * Used by [applyUpdateRaw] so write-log replay sees the same read semantics as a
     * direct `update { it + delta }` call.
     */
    @PublishedApi
    internal fun rawGetComponent(type: KClass<*>, id: EntityId): Any? {
        if (tombstones[type]?.contains(id) == true) return null
        val frameWork = workingData[type]?.get(id)
        if (frameWork != null) return frameWork
        if (type in authoritativeTypes) return null
        return getComponents(initial).tables[type]?.asMap()?.get(id)
    }

    // --- Entity lifecycle ----------------------------------------------

    /**
     * Allocates a fresh [EntityId] from the shared world and returns it. The world is
     * mutated in place; subsequent [createEntity] / [removeEntity] calls on this builder
     * or any fork/parent sharing the world see the updated entity set.
     *
     * Component writes for the new entity should go through [update] / [setTable]; those
     * writes are captured in [writeLog] as normal and replayed on the parent during
     * [mergeFork], so the new entity ends up alive in the world AND carrying its
     * components on the parent.
     *
     * **Not thread-safe.** Multiple forks running concurrently would race on the shared
     * world's id counter. Isolated phases that call [createEntity] must therefore stay
     * on a single thread until a proper fork-local id allocator lands.
     */
    fun createEntity(): EntityId = getWorld(initial).createEntity()

    /**
     * Removes an entity from the world and tombstones all of its components across every
     * type this builder knows about — both types present in [initial] and types touched
     * this frame via [update] / [remove] / [setTable]. After this call, [getComponent]
     * returns null for [id] for every component type.
     *
     * The removal is recorded in [writeLog] so a fork's [removeEntity] is replayed on
     * the parent during [mergeFork], where the parent re-runs the same tombstoning
     * against its own (potentially richer) set of known types.
     *
     * Domain-specific cascades (e.g. dependent entities, non-component indexes) are the
     * caller's responsibility; this method only touches the world and component tables.
     */
    fun removeEntity(id: EntityId) {
        applyRemoveEntityRaw(id)
    }

    @PublishedApi
    internal fun applyRemoveEntityRaw(id: EntityId) {
        getWorld(initial).removeEntity(id)
        val types =
            getComponents(initial).tables.keys + workingData.keys + tombstones.keys + authoritativeTypes
        for (type in types) {
            workingData[type]?.remove(id)
            tombstones.getOrPut(type) { mutableSetOf() }.add(id)
        }
        writeLog?.add { parent -> parent.applyRemoveEntityRaw(id) }
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
