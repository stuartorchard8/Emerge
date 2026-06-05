package org.emerge.sim.core.ecs.soa

/**
 * Per-component-type column storage: the dense, primitive field arrays for one component
 * type, indexed by a dense slot. Hand-written per component (like the engine's
 * [org.emerge.sim.core... ComponentCodec] is hand-written per component — same field-ordered,
 * reflection-free flavour), because KMP has no zero-cost reflection and we want true
 * primitive packing (FloatArray/IntArray/LongArray per field, no per-component objects on the
 * hot path).
 *
 * [ComponentColumns] owns the sparse-set bookkeeping (which entity is at which slot); a
 * [ColumnStore] owns only the field arrays and how to move/read/write a slot.
 *
 * [scatter]/[gather] are the object↔columns bridge used by the spawn/decode path and the
 * compatibility API (cold systems); the hot systems read/write the field arrays directly and
 * never call these.
 */
interface ColumnStore<T : Any> {
    /** Grow the field arrays so slots `0 until capacity` are addressable. Preserves contents. */
    fun ensureCapacity(capacity: Int)

    /** Write [value]'s fields into slot [slot] (object → columns). */
    fun scatter(slot: Int, value: T)

    /** Read slot [slot]'s fields into a fresh [T] (columns → object). */
    fun gather(slot: Int): T

    /** Copy slot [src]'s fields to slot [dst] (used by stable compaction). */
    fun moveSlot(dst: Int, src: Int)
}
