package org.emerge.demo.drockets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairwiseMrcaTest {
    @Test
    fun same_node_returns_null() {
        assertNull(pairwiseMrca(7L, 7L, parents = emptyMap()))
    }

    @Test
    fun disconnected_nodes_return_null() {
        // Two trees that never meet.
        val parents = mapOf(
            2L to listOf(1L),
            4L to listOf(3L),
        )
        assertNull(pairwiseMrca(2L, 4L, parents))
    }

    @Test
    fun direct_lineage_returns_parent_as_mrca_with_two_node_path() {
        // Child 2's parent is 1. MRCA(1, 2) is 1, path = {1, 2}.
        val parents = mapOf(2L to listOf(1L))
        val result = pairwiseMrca(1L, 2L, parents)
        assertNotNull(result)
        assertEquals(1L, result.mrca)
        assertEquals(setOf(1L, 2L), result.path)
    }

    @Test
    fun siblings_share_parent_as_mrca() {
        // Two children 2 and 3, both with parent 1. MRCA(2, 3) = 1, path = {1, 2, 3}.
        val parents = mapOf(
            2L to listOf(1L),
            3L to listOf(1L),
        )
        val result = pairwiseMrca(2L, 3L, parents)
        assertNotNull(result)
        assertEquals(1L, result.mrca)
        assertEquals(setOf(1L, 2L, 3L), result.path)
    }

    @Test
    fun deep_cousins_include_intermediate_ancestors_on_path() {
        // The bug-regression test. Tree:
        //
        //         1
        //        / \
        //       2   3
        //       |   |
        //       4   5
        //       |   |
        //       6   7
        //
        // MRCA(6, 7) = 1; path = {6, 4, 2, 1, 3, 5, 7}. The earlier in-class implementation
        // started from `cur = primary` and walked `cur = primaryPred[cur]`, hitting null on
        // the very first step because primary has no predecessor in its own BFS — so the
        // path collapsed to {6, 7} and no intermediate edges lit up. This asserts the
        // intermediates are present.
        val parents = mapOf(
            2L to listOf(1L),
            3L to listOf(1L),
            4L to listOf(2L),
            5L to listOf(3L),
            6L to listOf(4L),
            7L to listOf(5L),
        )
        val result = pairwiseMrca(6L, 7L, parents)
        assertNotNull(result)
        assertEquals(1L, result.mrca)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), result.path)
    }

    @Test
    fun ancestor_descendant_pair_returns_ancestor_as_mrca() {
        // When primary is itself an ancestor of secondary, MRCA == primary; path is the
        // direct lineage between them.
        //
        //   1 → 2 → 3 → 4
        //
        // MRCA(1, 4) should be 1, path = {1, 2, 3, 4}.
        val parents = mapOf(
            2L to listOf(1L),
            3L to listOf(2L),
            4L to listOf(3L),
        )
        val result = pairwiseMrca(1L, 4L, parents)
        assertNotNull(result)
        assertEquals(1L, result.mrca)
        assertEquals(setOf(1L, 2L, 3L, 4L), result.path)
    }

    @Test
    fun two_parent_dag_picks_shortest_meeting_ancestor() {
        // Node 5 has two parents 3 and 4, which share parent 1.
        // Node 6 has one parent, 2, which also has parent 1.
        //
        //         1
        //       / | \
        //      2  3  4
        //      |   \ /
        //      6    5
        //
        // BFS-up from 5 reaches: 5(0), 3(1), 4(1), 1(2).
        // BFS-up from 6 reaches: 6(0), 2(1), 1(2).
        // Common ancestors: {1}. Cost = 2 + 2 = 4.
        // Result MRCA = 1; path = {1, 2, 6, 3 or 4, 5}.  We don't pin which of 3/4 is
        // chosen by the BFS pred chain (both have distance 1 from 5), but the size of
        // the path should be exactly 5 and contain {1, 2, 5, 6} plus one of {3, 4}.
        val parents = mapOf(
            2L to listOf(1L),
            3L to listOf(1L),
            4L to listOf(1L),
            5L to listOf(3L, 4L),
            6L to listOf(2L),
        )
        val result = pairwiseMrca(5L, 6L, parents)
        assertNotNull(result)
        assertEquals(1L, result.mrca)
        assertEquals(5, result.path.size, "expected primary→...→mrca→...→secondary = 5 distinct nodes")
        assertTrue(1L in result.path)
        assertTrue(2L in result.path)
        assertTrue(5L in result.path)
        assertTrue(6L in result.path)
        assertTrue(3L in result.path || 4L in result.path)
    }
}
