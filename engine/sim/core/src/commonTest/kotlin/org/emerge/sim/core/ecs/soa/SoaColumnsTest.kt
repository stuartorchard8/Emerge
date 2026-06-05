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
}
