package org.emerge.demo.drockets

import kotlin.test.Test
import kotlin.test.assertEquals

class PairwiseMrcaUnionCacheTest {
    @Test
    fun cache_matches_stateless_function_on_initial_state() {
        // A small lineage with two living descended from a shared grandparent.
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 2L),
                5L to node(5L, mother = 3L),
            ),
            livingLineageIds = setOf(4L, 5L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = PairwiseMrcaUnionCache()
        val cached = cache.visibleFor(lineage, layout)
        val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_PAIRWISE_MRCA)
        assertEquals(stateless, cached, "cache result should match stateless on first call")
    }

    @Test
    fun cache_handles_a_birth_incrementally_and_matches_stateless() {
        // Start with two living, then add a third living through an existing parent.
        val initial = DrocketLineageState(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 2L),
                5L to node(5L, mother = 3L),
            ),
            livingLineageIds = setOf(4L, 5L),
        )
        val cache = PairwiseMrcaUnionCache()
        cache.visibleFor(initial, CladogramLayout.build(initial))

        // New birth: 6 born to 3.
        val afterBirth = initial.copy(
            nodes = initial.nodes + (6L to node(6L, mother = 3L)),
            livingLineageIds = initial.livingLineageIds + 6L,
        )
        val cachedAfterBirth = cache.visibleFor(afterBirth, CladogramLayout.build(afterBirth))
        val statelessAfterBirth =
            computeVisibleLineageIds(afterBirth, CladogramLayout.build(afterBirth), CladogramFilterMode.LIVING_PAIRWISE_MRCA)
        assertEquals(statelessAfterBirth, cachedAfterBirth)
    }

    @Test
    fun cache_handles_a_death_incrementally_and_matches_stateless() {
        // Three living with a shared ancestor. Kill the middle one and confirm the
        // cache drops it from visible while keeping the rest correct.
        val initial = DrocketLineageState(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 1L),
                5L to node(5L, mother = 2L),
                6L to node(6L, mother = 3L),
                7L to node(7L, mother = 4L),
            ),
            livingLineageIds = setOf(5L, 6L, 7L),
        )
        val cache = PairwiseMrcaUnionCache()
        cache.visibleFor(initial, CladogramLayout.build(initial))

        val afterDeath = initial.copy(livingLineageIds = setOf(5L, 7L))
        val cachedAfterDeath = cache.visibleFor(afterDeath, CladogramLayout.build(afterDeath))
        val statelessAfterDeath =
            computeVisibleLineageIds(afterDeath, CladogramLayout.build(afterDeath), CladogramFilterMode.LIVING_PAIRWISE_MRCA)
        assertEquals(statelessAfterDeath, cachedAfterDeath)
    }

    @Test
    fun cache_keeps_dead_node_visible_if_on_other_living_pair_paths() {
        // 4 is the MRCA of living 5 and 6. When 4 then dies, it stays in the visible
        // set because the (5, 6) pair-wise path still uses it as the MRCA. This is the
        // most subtle case to get right with reference-counting.
        val initial = DrocketLineageState(
            nodes = linkedMapOf(
                1L to node(1L),
                4L to node(4L, mother = 1L),
                2L to node(2L, mother = 4L),
                3L to node(3L, mother = 4L),
                5L to node(5L, mother = 2L),
                6L to node(6L, mother = 3L),
            ),
            livingLineageIds = setOf(4L, 5L, 6L),
        )
        val cache = PairwiseMrcaUnionCache()
        cache.visibleFor(initial, CladogramLayout.build(initial))

        val afterDeath = initial.copy(livingLineageIds = setOf(5L, 6L))
        val cachedAfterDeath = cache.visibleFor(afterDeath, CladogramLayout.build(afterDeath))
        val statelessAfterDeath =
            computeVisibleLineageIds(afterDeath, CladogramLayout.build(afterDeath), CladogramFilterMode.LIVING_PAIRWISE_MRCA)
        assertEquals(statelessAfterDeath, cachedAfterDeath)
        // Sanity: 4 must still be visible as the MRCA of the remaining pair (5, 6).
        assertEquals(true, 4L in cachedAfterDeath, "MRCA of remaining living pair must stay visible")
    }

    @Test
    fun cache_matches_stateless_through_a_long_birth_death_sequence() {
        // Run 50 random-ish birth/death events. After each, the cache result must match
        // the stateless function recomputed from scratch. Locks in incremental
        // correctness against any subtle reference-count / pair-bookkeeping bug.
        var lineage = DrocketLineageState(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            livingLineageIds = setOf(2L, 3L),
        )
        val cache = PairwiseMrcaUnionCache()
        cache.visibleFor(lineage, CladogramLayout.build(lineage))

        var nextId = 4L
        val rng = kotlin.random.Random(1)
        for (step in 0 until 50) {
            val living = lineage.livingLineageIds.toList()
            // 60% chance birth, 40% chance death (skewed to grow population)
            if (rng.nextFloat() < 0.6f || living.size < 2) {
                val mother = living.random(rng)
                val father = living.takeIf { it.size > 1 }?.filter { it != mother }?.randomOrNull(rng)
                val newId = nextId++
                lineage = lineage.copy(
                    nodes = lineage.nodes + (newId to node(newId, mother = mother, father = father)),
                    livingLineageIds = lineage.livingLineageIds + newId,
                )
            } else {
                val dying = living.random(rng)
                lineage = lineage.copy(livingLineageIds = lineage.livingLineageIds - dying)
            }
            val layout = CladogramLayout.build(lineage)
            val cached = cache.visibleFor(lineage, layout)
            val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_PAIRWISE_MRCA)
            assertEquals(stateless, cached, "step $step diverged: cache=$cached, stateless=$stateless")
        }
    }

    @Test
    fun cache_resets_when_lineage_shrinks_via_snapshot_load() {
        // Simulate a snapshot-load that replaces the lineage with a smaller earlier state.
        // The cache should detect the missing nodes and fully rebuild rather than serve
        // stale data.
        val full = DrocketLineageState(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 2L),
                5L to node(5L, mother = 3L),
            ),
            livingLineageIds = setOf(4L, 5L),
        )
        val cache = PairwiseMrcaUnionCache()
        cache.visibleFor(full, CladogramLayout.build(full))

        // "Load" an earlier state with fewer nodes.
        val earlier = DrocketLineageState(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            livingLineageIds = setOf(2L, 3L),
        )
        val cachedAfterLoad = cache.visibleFor(earlier, CladogramLayout.build(earlier))
        val statelessAfterLoad =
            computeVisibleLineageIds(earlier, CladogramLayout.build(earlier), CladogramFilterMode.LIVING_PAIRWISE_MRCA)
        assertEquals(statelessAfterLoad, cachedAfterLoad)
        // Old nodes 4, 5 must not appear.
        assertEquals(false, 4L in cachedAfterLoad)
        assertEquals(false, 5L in cachedAfterLoad)
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
}
