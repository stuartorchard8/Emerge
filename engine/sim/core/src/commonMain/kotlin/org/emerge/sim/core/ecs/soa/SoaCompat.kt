package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import kotlin.reflect.KClass

/**
 * Compatibility gather/scatter shim over a [SoaWorld] for **cold systems** — the ~25 engine
 * systems whose per-tick cost is negligible and aren't worth rewriting to raw column indexing.
 * It re-creates the small slice of the array-of-structs builder API those systems use
 * ([getComponent]/[update]/[remove]/[entries]) by gathering an object on read and scattering it
 * back on write. This re-allocates (losing the SoA win) but costs nothing on cold paths and
 * lets the hot/cold split land incrementally rather than rewriting every system at once. Hot
 * systems bypass this entirely and index the [ColumnStore] field arrays directly.
 *
 * [forEachSlot] is the allocation-free iteration path: it hands the system a raw slot index so
 * it can read columns directly instead of materialising a `Map<EntityId, T>` via [entries].
 *
 * **Ordering note.** Writes mirror the AoS `ComponentTable` iteration order: an existing
 * component is overwritten in place, and a new entity is appended at the end. Adding a component
 * type to a *pre-existing* non-maximal entity mid-tick — which the array-of-structs builder
 * appends at the end of its iteration order — is appended here too (via [ComponentColumns.put]),
 * so it stays bit-identical to the builder rather than reordering by id.
 */
class SoaCompat(val world: SoaWorld) {

    fun <T : Any> getComponent(id: EntityId, type: KClass<T>): T? = world.columns(type).gather(id)

    inline fun <reified T : Any> getComponent(id: EntityId): T? = getComponent(id, T::class)

    /** Gather→apply→scatter. Overwrites in place if present; appends if [id] is the largest. */
    fun <T : Any> update(id: EntityId, type: KClass<T>, block: (T?) -> T) {
        val cols = world.columns(type)
        cols.put(id, block(cols.gather(id)))
    }

    inline fun <reified T : Any> update(id: EntityId, crossinline block: (T?) -> T) =
        update(id, T::class) { block(it) }

    fun <T : Any> remove(id: EntityId, type: KClass<T>) = world.columns(type).remove(id)

    inline fun <reified T : Any> remove(id: EntityId) = remove(id, T::class)

    /**
     * Gathers all live entries for [type] into a fresh insertion-ordered map — the compat
     * equivalent of the builder's `entries<T>()` (same iteration order as the AoS table). Prefer
     * [forEachSlot] where the per-entry object isn't actually needed.
     */
    fun <T : Any> entries(type: KClass<T>): Map<EntityId, T> {
        val cols = world.columns(type)
        val out = LinkedHashMap<EntityId, T>(cols.count)
        cols.forEachAliveSlot { slot, id -> out[id] = cols.gatherAt(slot) }
        return out
    }

    inline fun <reified T : Any> entries(): Map<EntityId, T> = entries(T::class)

    /** Allocation-free iteration: visits each live slot of [type] in insertion order (AoS-table order). */
    fun <T : Any> forEachSlot(type: KClass<T>, action: (slot: Int, id: EntityId) -> Unit) =
        world.columns(type).forEachAliveSlot(action)

    inline fun <reified T : Any> forEachSlot(noinline action: (slot: Int, id: EntityId) -> Unit) =
        forEachSlot(T::class, action)
}
