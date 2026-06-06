package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase-0 gate for the SoA framework core: sparse-set ordering + gather∘scatter round-trip. */
class SoaColumnsTest {

    private fun transform(x: Int, y: Int, a: Int) =
        TransformComponent(Coord2(Coord(x), Coord(y)), Coord(a))

    @Test
    fun spawnPreservesAscendingOrderAndRoundTrips() {
        val cols = ComponentColumns(TransformColumnStore())
        val t1 = transform(10, 20, 1)
        val t2 = transform(-30, 40, 2)
        val t3 = transform(50, -60, 3)
        cols.put(EntityId(1), t1)
        cols.put(EntityId(2), t2)
        cols.put(EntityId(3), t3)

        assertEquals(3, cols.count)
        assertEquals(EntityId(1), cols.entityAt(0))
        assertEquals(EntityId(2), cols.entityAt(1))
        assertEquals(EntityId(3), cols.entityAt(2))
        // gather∘scatter is bit-identical (data-class equality over the raw fields)
        assertEquals(t1, cols.gather(EntityId(1)))
        assertEquals(t2, cols.gather(EntityId(2)))
        assertEquals(t3, cols.gather(EntityId(3)))
    }

    @Test
    fun removeTombstonesThenCompactPreservesOrderAndRemaps() {
        val cols = ComponentColumns(TransformColumnStore())
        val t1 = transform(10, 20, 1)
        val t2 = transform(-30, 40, 2)
        val t3 = transform(50, -60, 3)
        cols.put(EntityId(1), t1)
        cols.put(EntityId(2), t2)
        cols.put(EntityId(3), t3)

        cols.remove(EntityId(2))
        assertFalse(cols.has(EntityId(2)))
        assertNull(cols.gather(EntityId(2)))
        assertEquals(3, cols.count) // tombstoned, not yet compacted
        assertTrue(cols.needsCompaction())

        val remap = cols.compact()!!
        assertEquals(intArrayOf(0, -1, 1).toList(), remap.toList())
        assertEquals(2, cols.count)
        assertEquals(EntityId(1), cols.entityAt(0))
        assertEquals(EntityId(3), cols.entityAt(1))
        // surviving cells keep their values and ascending order after the move
        assertEquals(t1, cols.gather(EntityId(1)))
        assertEquals(t3, cols.gather(EntityId(3)))
        assertFalse(cols.needsCompaction())
    }

    @Test
    fun putWithExistingIdOverwritesInPlace() {
        val cols = ComponentColumns(TransformColumnStore())
        cols.put(EntityId(1), transform(1, 1, 1))
        cols.put(EntityId(1), transform(9, 9, 9))
        assertEquals(1, cols.count)
        assertEquals(transform(9, 9, 9), cols.gather(EntityId(1)))
    }

    @Test
    fun putAppendsOutOfOrderIdInInsertionOrder() {
        // Adding a component for an id smaller than the current max appends at the end (insertion
        // order) — the former gap, which used to throw. Enables mid-life component addition.
        val cols = ComponentColumns(TransformColumnStore())
        cols.put(EntityId(5), transform(5, 5, 5))
        cols.put(EntityId(7), transform(7, 7, 7))
        cols.put(EntityId(3), transform(3, 3, 3)) // smaller than 7 -> appends, does not throw

        assertEquals(3, cols.count)
        assertEquals(EntityId(5), cols.entityAt(0))
        assertEquals(EntityId(7), cols.entityAt(1))
        assertEquals(EntityId(3), cols.entityAt(2))
        assertEquals(2, cols.slotOfValue(3))
        assertEquals(transform(3, 3, 3), cols.gather(EntityId(3)))
    }

    @Test
    fun iterationOrderMatchesComponentTableOverMixedOps() {
        // The bit-identity invariant: ComponentColumns must iterate exactly like the engine's
        // LinkedHashMap-backed ComponentTable. Mirror an identical op sequence on both — including
        // a mid-middle tombstone, a re-add of a removed id, and an out-of-order add — and assert
        // the live dense order equals the LinkedHashMap key order tick-for-tick.
        val cols = ComponentColumns(TransformColumnStore())
        val ref = LinkedHashMap<Int, TransformComponent>()

        fun put(id: Int) {
            val t = transform(id, id, id)
            cols.put(EntityId(id), t); ref[id] = t
        }
        fun remove(id: Int) { cols.remove(EntityId(id)); ref.remove(id) }

        put(1); put(2); put(3); put(4)
        remove(2)        // tombstone in the middle
        put(10)          // monotonic append
        put(2)           // re-add a removed id -> moves to the END (LinkedHashMap semantics)
        put(0)           // out-of-order add of the smallest id -> END
        cols.compact()   // reclaim the tombstone; stable order preserved

        val dense = ArrayList<Int>()
        cols.forEachAliveSlot { _, id -> dense.add(id.value) }
        assertEquals(ref.keys.toList(), dense)
        for (id in ref.keys) assertEquals(ref.getValue(id), cols.gather(EntityId(id)))
    }
}
