package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phase-0 gate for the cold-system compat shim: gather→apply→scatter + raw iteration. */
class SoaCompatTest {

    private fun transform(x: Int) = TransformComponent(Coord2(Coord(x), Coord(x)), Coord(0))

    private fun world(): SoaWorld = SoaWorld().apply {
        register(TransformColumnStore())
        register(MotionColumnStore())
    }

    @Test
    fun getUpdateRemoveRoundTrips() {
        val w = world()
        val shim = SoaCompat(w)
        val a = w.createEntity()
        shim.update<TransformComponent>(a) { transform(5) }
        assertEquals(transform(5), shim.getComponent<TransformComponent>(a))

        // in-place gather→apply→scatter
        shim.update<TransformComponent>(a) { cur -> transform((cur!!.pos.x.raw) + 1) }
        assertEquals(transform(6), shim.getComponent<TransformComponent>(a))

        shim.remove<TransformComponent>(a)
        assertNull(shim.getComponent<TransformComponent>(a))
    }

    @Test
    fun entriesAndForEachSlotFollowInsertionOrderAndSkipTombstones() {
        val w = world()
        val shim = SoaCompat(w)
        val a = w.createEntity(); val b = w.createEntity(); val c = w.createEntity()
        shim.update<TransformComponent>(a) { transform(1) }
        shim.update<TransformComponent>(b) { transform(2) }
        shim.update<TransformComponent>(c) { transform(3) }

        shim.remove<TransformComponent>(b)

        // entries: ascending id, tombstone skipped
        assertEquals(listOf(a, c), shim.entries<TransformComponent>().keys.toList())

        val visited = ArrayList<EntityId>()
        shim.forEachSlot<TransformComponent> { _, id -> visited.add(id) }
        assertEquals(listOf(a, c), visited)
    }

    @Test
    fun appendOfFreshLargestIdSucceeds() {
        val w = world()
        val shim = SoaCompat(w)
        val a = w.createEntity()
        shim.update<TransformComponent>(a) { transform(1) }
        val b = w.createEntity() // larger id -> appends fine
        shim.update<TransformComponent>(b) { transform(2) }
        assertEquals(listOf(a, b), shim.entries<TransformComponent>().keys.toList())
    }

    @Test
    fun addingComponentToPreExistingNonMaximalEntitySucceeds() {
        // The former Phase-2 gap: an entity that already exists (carries Transform) gains a NEW
        // component type mid-life when its id is NOT the largest in that column. This used to
        // throw; now it appends in insertion order, matching the AoS ComponentTable.
        val w = world()
        val shim = SoaCompat(w)
        val a = w.createEntity(); val b = w.createEntity(); val c = w.createEntity()
        shim.update<TransformComponent>(a) { transform(0) }
        shim.update<TransformComponent>(b) { transform(1) }
        shim.update<TransformComponent>(c) { transform(2) }

        // Motion added to the larger ids first, then to the smaller pre-existing `a`.
        shim.update<MotionComponent>(b) { MotionComponent(Coord2(Coord(1), Coord(1)), Coord(0)) }
        shim.update<MotionComponent>(c) { MotionComponent(Coord2(Coord(2), Coord(2)), Coord(0)) }
        shim.update<MotionComponent>(a) { MotionComponent(Coord2(Coord(9), Coord(9)), Coord(0)) }

        // Insertion order (b, c, a) — what the AoS LinkedHashMap-backed table yields, not id order.
        assertEquals(listOf(b, c, a), shim.entries<MotionComponent>().keys.toList())
        assertEquals(MotionComponent(Coord2(Coord(9), Coord(9)), Coord(0)), shim.getComponent<MotionComponent>(a))
        // The Transform column is untouched, in its own insertion order.
        assertEquals(listOf(a, b, c), shim.entries<TransformComponent>().keys.toList())
    }
}
