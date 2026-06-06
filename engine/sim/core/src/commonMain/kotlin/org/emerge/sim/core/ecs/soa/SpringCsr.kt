package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.SpringConstraint

/**
 * Compressed-sparse-row adjacency for the engine's [SpringConstraint] springs over a dense
 * slot ordering — the SoA replacement for per-entity `SpringConstraintComponent` lists. For
 * each owner slot `i`, its directed spring ends occupy `[offset[i], offset[i+1])`; every end
 * carries the neighbour's dense slot ([otherSlot]) and EntityId value ([otherId], for the
 * lower-id solve order) plus the spring's raw `Frac` parameters. [edgeAux] is one optional
 * game-specific float per directed end (cyto stores connection-damage here) — generic so the
 * engine stays domain-agnostic.
 *
 * The arrays are public and mutated directly by hot systems (rest/stiffness/damping/aux are
 * refreshed in place each tick). Structural changes (welds, breaks, division, death) rebuild
 * the flat arrays at a lifecycle barrier via [rebuildFrom]; in-place per-tick field updates
 * never reallocate.
 *
 * The lower-id pair-solve keys off [otherId] (the real EntityId), and the impulse accumulation
 * is additive, so bit-identity does not depend on the dense slots being ascending-by-EntityId —
 * only on [slotOf] resolving each neighbour to its current dense slot. (In practice the cyto
 * cells that use this are all spawned monotonically, so their slots are ascending anyway.)
 */
class SpringCsr private constructor(
    var count: Int,
    var offset: IntArray,
    var otherSlot: IntArray,
    var otherId: IntArray,
    var restRaw: LongArray,
    var stiffRaw: LongArray,
    var dampRaw: LongArray,
    var edgeAux: FloatArray,
) {
    /** Total directed spring ends across the world. */
    val ends: Int get() = offset[count]

    fun beginOf(slot: Int): Int = offset[slot]
    fun endOf(slot: Int): Int = offset[slot + 1]
    fun degreeOf(slot: Int): Int = offset[slot + 1] - offset[slot]

    /**
     * Rebuilds the flat arrays in place (growing only when needed) from a (possibly changed)
     * reference ordering. Call at the lifecycle barrier after spawns/removals/structural spring
     * edits and (if the slots moved) a [ComponentColumns.compact].
     *
     * @param count number of owner slots in the reference ordering.
     * @param entityIdAt owner slot -> its EntityId.value.
     * @param slotOf neighbour EntityId.value -> its dense slot in the same reference ordering.
     * @param springsAt owner slot -> its spring list, in the order they should be stored.
     * @param edgeAuxAt owner slot + neighbour -> the per-edge aux float (default 0).
     */
    fun rebuildFrom(
        count: Int,
        entityIdAt: (slot: Int) -> Int,
        slotOf: (idValue: Int) -> Int,
        springsAt: (slot: Int) -> List<SpringConstraint>,
        edgeAuxAt: (slot: Int, other: EntityId) -> Float = { _, _ -> 0f },
    ) {
        this.count = count
        if (offset.size < count + 1) offset = IntArray(count + 1) else offset.fill(0, 0, count + 1)

        var totalEnds = 0
        for (slot in 0 until count) totalEnds += springsAt(slot).size
        if (otherSlot.size < totalEnds) {
            otherSlot = IntArray(totalEnds); otherId = IntArray(totalEnds)
            restRaw = LongArray(totalEnds); stiffRaw = LongArray(totalEnds)
            dampRaw = LongArray(totalEnds); edgeAux = FloatArray(totalEnds)
        }

        var cursor = 0
        for (slot in 0 until count) {
            offset[slot] = cursor
            for (s in springsAt(slot)) {
                otherSlot[cursor] = slotOf(s.other.value)
                otherId[cursor] = s.other.value
                restRaw[cursor] = s.restLength.raw
                stiffRaw[cursor] = s.stiffness.raw
                dampRaw[cursor] = s.damping.raw
                edgeAux[cursor] = edgeAuxAt(slot, s.other)
                cursor++
            }
        }
        offset[count] = cursor
    }

    companion object {
        /** Builds a fresh CSR sized exactly to the given adjacency. */
        fun build(
            count: Int,
            entityIdAt: (slot: Int) -> Int,
            slotOf: (idValue: Int) -> Int,
            springsAt: (slot: Int) -> List<SpringConstraint>,
            edgeAuxAt: (slot: Int, other: EntityId) -> Float = { _, _ -> 0f },
        ): SpringCsr {
            val csr = SpringCsr(
                count = 0,
                offset = IntArray(count + 1),
                otherSlot = IntArray(0), otherId = IntArray(0),
                restRaw = LongArray(0), stiffRaw = LongArray(0),
                dampRaw = LongArray(0), edgeAux = FloatArray(0),
            )
            csr.rebuildFrom(count, entityIdAt, slotOf, springsAt, edgeAuxAt)
            return csr
        }
    }
}
