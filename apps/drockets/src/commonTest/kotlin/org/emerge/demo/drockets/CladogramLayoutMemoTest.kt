package org.emerge.demo.drockets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class CladogramLayoutMemoTest {
    @Test
    fun returns_same_instance_on_repeated_calls_with_unchanged_lineage() {
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val memo = CladogramLayoutMemo()
        val first = memo.get(lineage)
        val second = memo.get(lineage)
        assertSame(first, second, "same lineage instance should reuse cached layout")

        // Even an equal-but-different lineage with the same stamp components
        // should hit the cache (the memo keys on stamp, not identity).
        val equivalent = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val third = memo.get(equivalent)
        assertSame(first, third, "equivalent lineage with matching stamp should reuse cache")
    }

    @Test
    fun rebuilds_when_a_birth_changes_the_stamp() {
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
            ),
            living = setOf(2L),
        )
        val memo = CladogramLayoutMemo()
        val first = memo.get(initial)

        val grown = lineageOf(
            nodes = initial.nodes + (3L to node(3L, mother = 1L)),
            living = setOf(2L, 3L),
        )
        val second = memo.get(grown)
        assertNotSame(first, second, "birth must invalidate the memo")
        assertEquals(CladogramLayout.build(grown), second, "rebuilt layout should match fresh build")
    }

    @Test
    fun rebuilds_when_a_death_changes_the_stamp() {
        // Death moves livingLineageIds.size; stats.livingCount / deadCount in the
        // cached layout would otherwise go stale.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val memo = CladogramLayoutMemo()
        val first = memo.get(initial)

        val afterDeath = initial.copy(livingLineageIds = setOf(2L))
        val second = memo.get(afterDeath)
        assertNotSame(first, second, "death must invalidate the memo")
        assertEquals(1, second.stats.livingCount)
        assertEquals(2, second.stats.deadCount)
    }

    @Test
    fun reset_forces_rebuild_even_when_stamp_matches() {
        // Defensive path for snapshot-restore: if a restore happens to produce a
        // lineage with the same stamp as the pre-restore state but a different
        // shape, reset() prevents serving a stale layout.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
            ),
            living = setOf(2L),
        )
        val memo = CladogramLayoutMemo()
        val first = memo.get(lineage)
        memo.reset()
        val second = memo.get(lineage)
        assertNotSame(first, second, "reset must drop the cache")
    }

    @Test
    fun matches_fresh_build_across_a_birth_death_sequence() {
        var lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val memo = CladogramLayoutMemo()
        memo.get(lineage)

        var nextId = 4L
        val rng = kotlin.random.Random(42)
        for (step in 0 until 50) {
            val living = lineage.livingLineageIds.toList()
            if (rng.nextFloat() < 0.6f || living.size < 2) {
                val mother = living.random(rng)
                val father = living.takeIf { it.size > 1 }?.filter { it != mother }?.randomOrNull(rng)
                val newId = nextId++
                lineage = lineageOf(
                    nodes = lineage.nodes + (newId to node(newId, mother = mother, father = father)),
                    living = lineage.livingLineageIds + newId,
                )
            } else {
                val dying = living.random(rng)
                lineage = lineage.copy(livingLineageIds = lineage.livingLineageIds - dying)
            }
            val cached = memo.get(lineage)
            val fresh = CladogramLayout.build(lineage)
            assertEquals(fresh, cached, "step $step: memo result must match fresh build")
        }
    }

    private fun node(id: Long, mother: Long? = null, father: Long? = null) =
        DrocketLineageNode(
            lineageId = id,
            motherLineageId = mother,
            fatherLineageId = father,
            birthTick = id * 100L,
            deathTick = null,
            sex = Sex.FEMALE,
            genome = Genome(),
        )

    private fun lineageOf(
        nodes: Map<Long, DrocketLineageNode>,
        living: Set<Long>,
    ) = DrocketLineageState(
        nextLineageId = (nodes.keys.maxOrNull() ?: 0L) + 1L,
        nodes = nodes,
        livingLineageIds = living,
    )
}
