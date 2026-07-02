package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import kotlin.reflect.KClass

/**
 * Hot-path API over a [SoaWorld]. Wraps [SoaCompat] to provide the same methods but with the
 * intent clear: this is the **allocation-free path** for hot systems that iterate raw field arrays.
 *
 * Hot systems run sequentially on the shared mutable world — no fork/merge. The [SoaCompat]
 * gather/scatter methods are available for cases where a system needs to read or modify a
 * single component (e.g. a "get component, check a flag" query), but the primary path is
 * direct column indexing via [SoaCompat.forEachSlot] which hands the system a raw slot index.
 *
 * Note: reified overlines are intentionally omitted — they would require `inline` which
 * cannot cross module visibility boundaries (inline public functions can't access internal
 * members). Callers pass `MyType::class` explicitly.
 *
 * @see SoaCompat
 */
class SoaBuilder(val world: SoaWorld) {
    private val compat = SoaCompat(world)

    /** Gather a single component from columns — allocation on read, but the object is short-lived. */
    fun <T : Any> getComponent(id: EntityId, type: KClass<T>): T? = compat.getComponent(id, type)

    /** Gather→apply→scatter. Overwrites in place if present; appends if [id] is the largest. */
    fun <T : Any> update(id: EntityId, type: KClass<T>, block: (T?) -> T) =
        compat.update(id, type, block)

    /** Tombstone a component across all columns. Entity stays live until [SoaWorld.compact]. */
    fun remove(id: EntityId) = world.removeEntity(id)

    /** Allocation-free iteration: visits each live slot of [type] in insertion order. */
    fun <T : Any> forEachSlot(type: KClass<T>, action: (slot: Int, id: EntityId) -> Unit) =
        compat.forEachSlot(type, action)

    /** Number of live entities (across all columns). Hot loops use this as their iteration bound. */
    val entityCount: Int get() = world.entityCount

    /** Number of live entities for a specific type. Hot loops use this when iterating a single column. */
    fun <T : Any> count(type: KClass<T>): Int = world.columns(type).count
}
