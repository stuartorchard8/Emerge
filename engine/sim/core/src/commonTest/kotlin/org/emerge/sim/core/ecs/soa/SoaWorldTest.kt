package org.emerge.sim.core.ecs.soa

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase-0 gate for [SoaWorld]: multi-column membership + EcsWorld-identical id allocation. */
class SoaWorldTest {

    private fun world(): SoaWorld = SoaWorld().apply {
        register(TransformColumnStore())
        register(MotionColumnStore())
    }

    private fun transform(x: Int) = TransformComponent(Coord2(Coord(x), Coord(x)), Coord(0))
    private fun motion(x: Int) = MotionComponent(Coord2(Coord(x), Coord(x)), Coord(0))

    @Test
    fun addGetRemoveAcrossColumns() {
        val w = world()
        val a = w.createEntity()
        val b = w.createEntity()
        w.add(a, transform(1)); w.add(a, motion(10))
        w.add(b, transform(2)); w.add(b, motion(20))

        assertEquals(transform(1), w.get<TransformComponent>(a))
        assertEquals(motion(20), w.get<MotionComponent>(b))
        assertEquals(2, w.entityCount)

        w.removeEntity(a)
        assertFalse(w.isLive(a))
        assertNull(w.get<TransformComponent>(a))
        assertNull(w.get<MotionComponent>(a))
        assertTrue(w.needsCompaction())

        val remaps = w.compact()
        // a was slot 0, b slot 1 -> b moves to slot 0 in both columns.
        assertEquals(intArrayOf(-1, 0).toList(), remaps[TransformComponent::class]!!.toList())
        assertEquals(intArrayOf(-1, 0).toList(), remaps[MotionComponent::class]!!.toList())
        assertEquals(transform(2), w.get<TransformComponent>(b))
        assertEquals(EntityId(b.value), w.columns<TransformComponent>().entityAt(0))
        assertFalse(w.needsCompaction())
    }

    /**
     * The growing-colony bit-identity gate needs SoaWorld to allocate the exact id sequence
     * the engine's EcsWorld would — including id reuse when the most-recent id is freed. Drive
     * both with the same spawn/remove script and assert every allocated id matches.
     */
    @Test
    fun idAllocationMatchesEcsWorld() {
        val ecs = EcsWorld()
        val soa = SoaWorld()
        // Spawn 5, remove a middle one, spawn 2 more, remove the most-recent, spawn 1.
        val ecsIds = ArrayList<Int>()
        val soaIds = ArrayList<Int>()
        fun step(spawn: Int, free: List<Int>) {
            repeat(spawn) {
                ecsIds += ecs.createEntity().value
                soaIds += soa.createEntity().value
            }
            for (f in free) { ecs.removeEntity(EntityId(f)); soa.removeEntity(EntityId(f)) }
        }
        step(spawn = 5, free = listOf(2))
        step(spawn = 2, free = emptyList())
        val mostRecent = ecsIds.last()
        step(spawn = 0, free = listOf(mostRecent))
        step(spawn = 1, free = emptyList())
        step(spawn = 3, free = emptyList())

        assertEquals(ecsIds, soaIds, "id sequences diverged: ecs=$ecsIds soa=$soaIds")
        assertEquals(ecs.lastEntityValue, soa.lastEntityValue)
    }

    @Test
    fun seedLastEntityValueContinuesSequence() {
        val w = SoaWorld()
        // Import three pre-existing entities (ids 0,1,2) and seed the cursor.
        w.register(TransformColumnStore())
        for (v in 0..2) { val id = EntityId(v); w.ensureEntity(id); w.add(id, transform(v)) }
        w.seedLastEntityValue(2)
        assertEquals(EntityId(3), w.createEntity())
    }
}
