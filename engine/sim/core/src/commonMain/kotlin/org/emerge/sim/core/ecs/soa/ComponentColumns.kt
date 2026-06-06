package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId

/**
 * Sparse-set membership for one component type over a [ColumnStore]. Holds a dense prefix
 * `0 until count` of slots kept in **insertion order** — exactly mirroring the engine's
 * LinkedHashMap-backed `ComponentTable` (a new id appends at the end; an existing id keeps its
 * slot; a removed-then-reinserted id moves to the end). That ordering parity is the invariant
 * the SoA bit-identity / cross-peer determinism depends on. When ids are only ever added
 * monotonically — the spawn-time common case, where a fresh id is always the largest —
 * insertion order is also ascending-by-EntityId.
 *
 * Membership changes are deferred-friendly: [put] appends a new id at the end (and so supports
 * adding a component to a *pre-existing, non-maximal* entity mid-tick — it still appends, which
 * is what the AoS table does), [remove] tombstones (no reorder), and [compact] stably partitions
 * live slots to the front (preserving order). Slot indices are therefore stable within a tick
 * and only shift at a [compact] barrier — which is when slot-referencing side-tables (CSR
 * adjacency) rebuild.
 */
class ComponentColumns<T : Any>(val store: ColumnStore<T>) {
    var count: Int = 0
        private set
    private var denseEntityId = IntArray(0)
    private var alive = BooleanArray(0)
    private val slotByEntity = HashMap<Int, Int>()
    private var tombstones = 0

    /** Dense slot of [id], or -1 if this component isn't present on [id]. */
    fun slotOf(id: EntityId): Int = slotByEntity[id.value] ?: -1
    fun slotOfValue(idValue: Int): Int = slotByEntity[idValue] ?: -1
    fun has(id: EntityId): Boolean = slotByEntity.containsKey(id.value)
    fun entityAt(slot: Int): EntityId = EntityId(denseEntityId[slot])
    fun isAlive(slot: Int): Boolean = alive[slot]

    /**
     * The raw dense-EntityId backing array (valid for slots `0 until count`). Exposed for hot
     * loops that need the integer id per slot without allocating an [EntityId] each call; do not
     * mutate it.
     */
    fun denseIds(): IntArray = denseEntityId

    fun gather(id: EntityId): T? = slotOf(id).let { if (it < 0) null else store.gather(it) }
    fun gatherAt(slot: Int): T = store.gather(slot)

    /**
     * Visits every live (non-tombstoned) slot in insertion order (the [ComponentTable] order) —
     * the raw-index path the cold-system compat shim uses for iteration without gathering an
     * object per slot.
     */
    fun forEachAliveSlot(action: (slot: Int, id: EntityId) -> Unit) {
        for (s in 0 until count) if (alive[s]) action(s, EntityId(denseEntityId[s]))
    }

    /**
     * Adds [value] for [id], or overwrites in place if [id] is already present. A new id is
     * appended at the end of the dense prefix — **insertion order**, exactly mirroring the AoS
     * `ComponentTable` (new key appended, existing key keeps its slot). When ids arrive
     * monotonically (the spawn-time common case) this is also ascending; adding a component to a
     * pre-existing, non-maximal entity mid-tick is supported and stays bit-identical to the AoS
     * table's iteration order.
     */
    fun put(id: EntityId, value: T) {
        val existing = slotByEntity[id.value]
        if (existing != null) { store.scatter(existing, value); alive[existing] = true; return }
        ensureCapacity(count + 1)
        val slot = count
        denseEntityId[slot] = id.value
        alive[slot] = true
        slotByEntity[id.value] = slot
        store.scatter(slot, value)
        count++
    }

    /** Drops every entry (count → 0). Field arrays keep their capacity for reuse. */
    fun clear() {
        slotByEntity.clear()
        count = 0
        tombstones = 0
    }

    /** Tombstones [id] (no reorder). A later [compact] reclaims the slot. */
    fun remove(id: EntityId) {
        val slot = slotByEntity.remove(id.value) ?: return
        if (alive[slot]) { alive[slot] = false; tombstones++ }
    }

    fun needsCompaction(): Boolean = tombstones > 0

    /**
     * Stable-partitions live slots to `0 until count`, preserving insertion order, and rebuilds
     * the sparse map. Returns a remap `oldSlot -> newSlot` (newSlot = -1 for removed) so
     * slot-referencing side-tables (CSR) can be rebuilt by the caller. No-op (returns null) if
     * there are no tombstones.
     */
    fun compact(): IntArray? {
        if (tombstones == 0) return null
        val remap = IntArray(count) { -1 }
        var write = 0
        for (read in 0 until count) {
            if (!alive[read]) continue
            if (write != read) {
                store.moveSlot(write, read)
                denseEntityId[write] = denseEntityId[read]
                alive[write] = true
            }
            remap[read] = write
            write++
        }
        // rebuild sparse for the live prefix
        slotByEntity.clear()
        for (s in 0 until write) slotByEntity[denseEntityId[s]] = s
        count = write
        tombstones = 0
        return remap
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= denseEntityId.size) return
        var newCap = if (denseEntityId.isEmpty()) INITIAL_CAPACITY else denseEntityId.size
        while (newCap < capacity) newCap *= 2
        denseEntityId = denseEntityId.copyOf(newCap)
        alive = alive.copyOf(newCap)
        store.ensureCapacity(newCap)
    }

    companion object {
        private const val INITIAL_CAPACITY = 16
    }
}
