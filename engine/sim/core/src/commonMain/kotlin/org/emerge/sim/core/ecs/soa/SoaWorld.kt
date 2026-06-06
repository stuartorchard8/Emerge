package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import kotlin.reflect.KClass

/**
 * The struct-of-arrays analogue of `SimState` + `ComponentStore` + `EcsWorld`: a persistent,
 * mutated-in-place world holding one [ComponentColumns] per registered component type, an
 * entity registry, and the deterministic PRNG seed. Unlike the immutable-snapshot ECS there
 * is **no per-tick rebuild** — systems mutate the columns directly and a full `SimState` is
 * reconstructed (via [ColumnStore.gather]) only on the rare occasions that need it
 * (serialization, equivalence checks, the cold-system compat shim).
 *
 * **Entity ids.** [createEntity]/[removeEntity] reproduce the engine `EcsWorld` allocator
 * byte-for-byte (monotonic [lastEntityValue], a live-id set, id reuse only when the most
 * recent id is removed) so a reducer ported onto this world allocates the *same* id sequence
 * as its array-of-structs original — which the cross-tick bit-identity gate depends on.
 *
 * **Ordering.** Each [ComponentColumns] keeps its dense slots in insertion order (mirroring
 * the AoS `ComponentTable`); spawns append (a fresh id is the largest, so this is also
 * ascending) and removals tombstone, so slot indices are stable within a tick and only shift at
 * a [compact] barrier. A component added mid-tick to a pre-existing, non-maximal entity is
 * appended too (still matching the AoS table). Slot-referencing side-tables (CSR adjacency)
 * rebuild at the barrier using the returned remap.
 */
class SoaWorld(
    var randomSeed: Long = 0L,
    /** Deterministic monotonic tick clock, mirroring [org.emerge.sim.core.sim.SimState.tick]. */
    var tick: Long = 0L,
) {
    private val columnsByType = LinkedHashMap<KClass<*>, ComponentColumns<*>>()
    private val liveEntities = HashSet<Int>()

    /** Mirrors `EcsWorld.lastEntityValue`: the cursor the next [createEntity] scans from. */
    var lastEntityValue: Int = 0
        private set

    /** Number of live entities (across all component types — an entity may carry several). */
    val entityCount: Int get() = liveEntities.size

    /** Read-only view of the live entity-id values (for rebuilding an EcsWorld on export). */
    val liveIds: Set<Int> get() = liveEntities

    // --- registration -------------------------------------------------------

    /** Registers (once) a column store for [type], returning its [ComponentColumns]. */
    fun <T : Any> register(type: KClass<T>, store: ColumnStore<T>): ComponentColumns<T> {
        require(type !in columnsByType) { "columns already registered for $type" }
        val cols = ComponentColumns(store)
        columnsByType[type] = cols
        return cols
    }

    inline fun <reified T : Any> register(store: ColumnStore<T>): ComponentColumns<T> =
        register(T::class, store)

    /** The columns for [type]. Throws if [type] was never [register]ed. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> columns(type: KClass<T>): ComponentColumns<T> =
        (columnsByType[type] as? ComponentColumns<T>) ?: error("no columns registered for $type")

    inline fun <reified T : Any> columns(): ComponentColumns<T> = columns(T::class)

    fun has(type: KClass<*>): Boolean = type in columnsByType

    /** Registered component types, in registration order. */
    val registeredTypes: Set<KClass<*>> get() = columnsByType.keys

    // --- entity lifecycle ---------------------------------------------------

    /**
     * Allocates a fresh [EntityId], reproducing `EcsWorld.createEntity`: scan forward from
     * [lastEntityValue] to the first free id, claim it, return it. (Does not advance past the
     * claimed id — the next call re-scans, so a freed most-recent id is reused exactly as the
     * engine does.)
     */
    fun createEntity(): EntityId {
        while (liveEntities.contains(lastEntityValue)) lastEntityValue++
        liveEntities += lastEntityValue
        return EntityId(lastEntityValue)
    }

    /** Marks [id] live without allocating (loader path); never rewinds [lastEntityValue]. */
    fun ensureEntity(id: EntityId) {
        liveEntities += id.value
    }

    fun isLive(id: EntityId): Boolean = liveEntities.contains(id.value)

    /**
     * Seeds the allocator cursor when importing an existing snapshot, matching the source
     * `EcsWorld.lastEntityValue` so subsequent [createEntity] calls continue its sequence.
     */
    fun seedLastEntityValue(value: Int) {
        if (value > lastEntityValue) lastEntityValue = value
    }

    /**
     * Adds [value] for [id] in [type]'s columns (and marks [id] live). A new id is appended in
     * insertion order (mirroring the AoS `ComponentTable`); overwrites in place if [id] is
     * already present. Adding a component to a pre-existing, non-maximal entity is supported.
     */
    fun <T : Any> add(id: EntityId, type: KClass<T>, value: T) {
        columns(type).put(id, value)
        liveEntities += id.value
    }

    inline fun <reified T : Any> add(id: EntityId, value: T) = add(id, T::class, value)

    fun <T : Any> get(id: EntityId, type: KClass<T>): T? = columns(type).gather(id)

    inline fun <reified T : Any> get(id: EntityId): T? = get(id, T::class)

    /** Removes a single component (tombstone, reclaimed at [compact]); entity stays live. */
    fun <T : Any> removeComponent(id: EntityId, type: KClass<T>) {
        columns(type).remove(id)
    }

    inline fun <reified T : Any> removeComponent(id: EntityId) = removeComponent(id, T::class)

    /**
     * Removes [id] from the world: tombstones it in every column and drops it from the live
     * set. Mirrors `EcsWorld.removeEntity` (does not rewind [lastEntityValue]). Slots are
     * reclaimed at the next [compact].
     */
    fun removeEntity(id: EntityId) {
        for (cols in columnsByType.values) cols.remove(id)
        liveEntities -= id.value
    }

    /** True if any registered column currently holds a tombstone. */
    fun needsCompaction(): Boolean = columnsByType.values.any { it.needsCompaction() }

    /**
     * Compacts every column with tombstones, returning the per-type slot remap
     * (`oldSlot -> newSlot`, -1 for removed; null entry = that column was unchanged). The
     * caller rebuilds any slot-referencing side-table (CSR) from the relevant remap.
     */
    fun compact(): Map<KClass<*>, IntArray?> {
        val remaps = LinkedHashMap<KClass<*>, IntArray?>(columnsByType.size)
        for ((type, cols) in columnsByType) remaps[type] = cols.compact()
        return remaps
    }
}
