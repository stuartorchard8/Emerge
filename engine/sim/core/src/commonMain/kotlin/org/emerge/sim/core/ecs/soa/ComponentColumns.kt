package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId

/**
 * Sparse-set membership for one component type over a [ColumnStore]. Holds a dense prefix
 * `0 until count` of slots kept **ascending by EntityId** — the same order the engine's
 * LinkedHashMap-backed `ComponentTable` iterates (ids are monotonic and never reused), which
 * is the invariant the SoA bit-identity / cross-peer determinism depends on.
 *
 * Membership changes are deferred-friendly: [append] adds at the end (a freshly spawned
 * entity always has the largest id, so ascending order is preserved automatically), [remove]
 * tombstones (no reorder), and [compact] stably partitions live slots to the front. Slot
 * indices are therefore stable within a tick and only shift at a [compact] barrier — which is
 * when slot-referencing side-tables (CSR adjacency) rebuild.
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
    fun has(id: EntityId): Boolean = slotByEntity.containsKey(id.value)
    fun entityAt(slot: Int): EntityId = EntityId(denseEntityId[slot])
    fun isAlive(slot: Int): Boolean = alive[slot]

    fun gather(id: EntityId): T? = slotOf(id).let { if (it < 0) null else store.gather(it) }
    fun gatherAt(slot: Int): T = store.gather(slot)

    /**
     * Adds [value] for [id]. [id] must be larger than every existing id (true for freshly
     * spawned entities — monotonic ids) so the dense order stays ascending. If [id] already
     * present, overwrites in place.
     */
    fun put(id: EntityId, value: T) {
        val existing = slotByEntity[id.value]
        if (existing != null) { store.scatter(existing, value); alive[existing] = true; return }
        require(count == 0 || id.value > denseEntityId[count - 1]) {
            "ComponentColumns.put requires ascending ids (got ${id.value} after ${denseEntityId[count - 1]})"
        }
        ensureCapacity(count + 1)
        val slot = count
        denseEntityId[slot] = id.value
        alive[slot] = true
        slotByEntity[id.value] = slot
        store.scatter(slot, value)
        count++
    }

    /** Tombstones [id] (no reorder). A later [compact] reclaims the slot. */
    fun remove(id: EntityId) {
        val slot = slotByEntity.remove(id.value) ?: return
        if (alive[slot]) { alive[slot] = false; tombstones++ }
    }

    fun needsCompaction(): Boolean = tombstones > 0

    /**
     * Stable-partitions live slots to `0 until count`, preserving ascending order, and rebuilds
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
