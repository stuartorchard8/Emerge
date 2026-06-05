package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.SpringConstraint
import org.emerge.sim.core.physics.primitives.Frac
import kotlin.test.Test
import kotlin.test.assertEquals

/** Phase-0 gate for [SpringCsr]: adjacency ordering, per-edge fields, and in-place rebuild. */
class SpringCsrTest {

    private fun spring(other: Int, rest: Long, stiff: Long, damp: Long) =
        SpringConstraint(EntityId(other), Frac(rest), Frac(stiff), Frac(damp))

    @Test
    fun buildPreservesAdjacencyOrderAndFields() {
        // 3 cells, ids 10/20/30 at slots 0/1/2. Edges: 10-20, 10-30, 20-30 (symmetric).
        val ids = intArrayOf(10, 20, 30)
        val slotOf = mapOf(10 to 0, 20 to 1, 30 to 2)
        val springs = listOf(
            listOf(spring(20, 1, 100, 5), spring(30, 2, 100, 5)), // slot 0
            listOf(spring(10, 1, 100, 5), spring(30, 3, 100, 5)), // slot 1
            listOf(spring(10, 2, 100, 5), spring(20, 3, 100, 5)), // slot 2
        )
        val damage = mapOf((0 to 20) to 0.5f, (1 to 30) to 1.5f)

        val csr = SpringCsr.build(
            count = 3,
            entityIdAt = { ids[it] },
            slotOf = { slotOf.getValue(it) },
            springsAt = { springs[it] },
            edgeAuxAt = { slot, other -> damage[slot to other.value] ?: 0f },
        )

        assertEquals(listOf(0, 2, 4, 6), csr.offset.toList())
        assertEquals(6, csr.ends)
        // slot 0's two ends point at slots 1 and 2, ids 20 and 30.
        assertEquals(listOf(1, 2), (0 until 2).map { csr.otherSlot[it] })
        assertEquals(listOf(20, 30), (0 until 2).map { csr.otherId[it] })
        assertEquals(2, csr.degreeOf(1))
        // per-edge aux is keyed to the owner-slot+neighbour order.
        assertEquals(0.5f, csr.edgeAux[0])
        assertEquals(0f, csr.edgeAux[1])
        assertEquals(1.5f, csr.edgeAux[3]) // slot 1's second end (10,30) -> the 30 edge
        // raw spring fields survive.
        assertEquals(3L, csr.restRaw[3]); assertEquals(100L, csr.stiffRaw[3]); assertEquals(5L, csr.dampRaw[3])
    }

    @Test
    fun rebuildAfterRemovalCompactsInPlace() {
        // Start with 3 cells, then "remove" slot 1 (id 20): survivors 10,30 -> slots 0,1.
        val csr = SpringCsr.build(
            count = 3,
            entityIdAt = { intArrayOf(10, 20, 30)[it] },
            slotOf = { mapOf(10 to 0, 20 to 1, 30 to 2).getValue(it) },
            springsAt = {
                listOf(
                    listOf(spring(20, 1, 100, 5), spring(30, 2, 100, 5)),
                    listOf(spring(10, 1, 100, 5), spring(30, 3, 100, 5)),
                    listOf(spring(10, 2, 100, 5), spring(20, 3, 100, 5)),
                )[it]
            },
        )
        val priorOtherArray = csr.otherSlot

        // After removing 20: 10 keeps only its 30 edge; 30 keeps only its 10 edge.
        val ids2 = intArrayOf(10, 30)
        val slotOf2 = mapOf(10 to 0, 30 to 1)
        val springs2 = listOf(
            listOf(spring(30, 2, 100, 5)),
            listOf(spring(10, 2, 100, 5)),
        )
        csr.rebuildFrom(
            count = 2,
            entityIdAt = { ids2[it] },
            slotOf = { slotOf2.getValue(it) },
            springsAt = { springs2[it] },
        )

        assertEquals(2, csr.count)
        assertEquals(listOf(0, 1, 2), csr.offset.take(3))
        assertEquals(2, csr.ends)
        assertEquals(listOf(1, 0), (0 until 2).map { csr.otherSlot[it] })
        assertEquals(listOf(30, 10), (0 until 2).map { csr.otherId[it] })
        // 6 ends -> 2 ends fits in the old backing arrays, so they were reused (no realloc).
        assertEquals(priorOtherArray === csr.otherSlot, true)
    }
}
