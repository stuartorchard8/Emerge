package org.emerge.demo.drockets

import kotlin.test.Test
import kotlin.test.assertEquals

class CladogramVisibilityTest {
    @Test
    fun livingOnly_returns_only_living_nodes() {
        val (lineage, layout) = sampleState()

        val visible = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_ONLY)

        assertEquals(setOf(3L, 5L, 6L), visible)
    }

    @Test
    fun livingAndParents_adds_exactly_one_hop_parents() {
        val (lineage, layout) = sampleState()

        val visible = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_AND_PARENTS)

        assertEquals(setOf(2L, 3L, 4L, 5L, 6L), visible)
    }

    @Test
    fun livingAndConnectors_keeps_minimal_connectors_plus_isolated_living() {
        val (lineage, layout) = sampleState()

        val visible = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_AND_CONNECTORS)

        assertEquals(setOf(2L, 3L, 4L, 5L, 6L), visible)
    }

    @Test
    fun livingAndConnectors_excludes_singleChild_dead_ancestors_above_branching_mrca() {
        val nodes = linkedMapOf<Long, DrocketLineageNode>(
            10L to node(id = 10L, tick = 1L),
            11L to node(id = 11L, mother = 10L, tick = 2L),
            12L to node(id = 12L, mother = 11L, tick = 3L),
            13L to node(id = 13L, mother = 12L, tick = 4L),
            14L to node(id = 14L, mother = 12L, tick = 5L),
        )
        val lineage = DrocketLineageState(
            nextLineageId = 15L,
            nodes = nodes,
            livingLineageIds = linkedSetOf(13L, 14L),
            entityToLineageId = emptyMap(),
        )
        val layout = CladogramLayout.build(lineage)

        val visible = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_AND_CONNECTORS)

        // 12 is the true branching MRCA; 10 and 11 are single-child dead ancestors above it.
        assertEquals(setOf(12L, 13L, 14L), visible)
    }

    private fun sampleState(): Pair<DrocketLineageState, CladogramLayout> {
        val nodes = linkedMapOf<Long, DrocketLineageNode>(
            1L to node(id = 1L, tick = 1L),
            2L to node(id = 2L, mother = 1L, tick = 2L),
            3L to node(id = 3L, mother = 2L, tick = 3L),
            4L to node(id = 4L, mother = 2L, tick = 4L),
            5L to node(id = 5L, mother = 4L, tick = 5L),
            6L to node(id = 6L, tick = 6L),
            7L to node(id = 7L, mother = 6L, tick = 7L),
        )
        val lineage = DrocketLineageState(
            nextLineageId = 8L,
            nodes = nodes,
            livingLineageIds = linkedSetOf(3L, 5L, 6L),
            entityToLineageId = emptyMap(),
        )
        return lineage to CladogramLayout.build(lineage)
    }

    private fun node(id: Long, mother: Long? = null, father: Long? = null, tick: Long): DrocketLineageNode =
        DrocketLineageNode(
            lineageId = id,
            motherLineageId = mother,
            fatherLineageId = father,
            birthTick = tick,
            deathTick = null,
            sex = Sex.FEMALE,
            genome = Genome(),
        )

    // ── LIVING_STEINER ─────────────────────────────────────────────────────────

    @Test
    fun steiner_empty_lineage_returns_empty() {
        val empty = DrocketLineageState.EMPTY
        val visible = computeVisibleLineageIds(empty, CladogramLayout.build(empty), CladogramFilterMode.LIVING_STEINER)
        assertEquals(emptySet(), visible)
    }

    @Test
    fun steiner_single_living_returns_just_that_node() {
        // A → B → C, only C is living. Nothing to connect, so Steiner = {C}.
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(
                1L to DrocketLineageNode(1L, null, null, 0L, null, Sex.FEMALE, Genome()),
                2L to DrocketLineageNode(2L, 1L, null, 100L, null, Sex.FEMALE, Genome()),
                3L to DrocketLineageNode(3L, 2L, null, 200L, null, Sex.FEMALE, Genome()),
            ),
            livingLineageIds = setOf(3L),
        )
        val visible = computeVisibleLineageIds(lineage, CladogramLayout.build(lineage), CladogramFilterMode.LIVING_STEINER)
        assertEquals(setOf(3L), visible)
    }

    @Test
    fun steiner_includes_lca_of_two_living_via_chain() {
        // The case my first formulation missed: A → B → C → D → L1, C → E → L2.
        //   - C is the LCA of L1 and L2; removing it disconnects the pair.
        //   - D and E are each on a path between one of the living and the other.
        //   - A and B have only one living-descendant child each (both via C, whose
        //     subtree contains all the living), so they're not on any path between two
        //     living. They should be excluded.
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(
                10L to DrocketLineageNode(10L, null, null, 0L, null, Sex.FEMALE, Genome()),  // A
                11L to DrocketLineageNode(11L, 10L, null, 100L, null, Sex.FEMALE, Genome()), // B
                12L to DrocketLineageNode(12L, 11L, null, 200L, null, Sex.FEMALE, Genome()), // C (LCA)
                13L to DrocketLineageNode(13L, 12L, null, 300L, null, Sex.FEMALE, Genome()), // D
                14L to DrocketLineageNode(14L, 12L, null, 300L, null, Sex.FEMALE, Genome()), // E
                15L to DrocketLineageNode(15L, 13L, null, 400L, null, Sex.FEMALE, Genome()), // L1
                16L to DrocketLineageNode(16L, 14L, null, 400L, null, Sex.FEMALE, Genome()), // L2
            ),
            livingLineageIds = setOf(15L, 16L),
        )
        val visible = computeVisibleLineageIds(lineage, CladogramLayout.build(lineage), CladogramFilterMode.LIVING_STEINER)
        assertEquals(
            setOf(12L, 13L, 14L, 15L, 16L),
            visible,
            "LCA + both branches + both living should be included; root grandparents shouldn't be",
        )
    }

    @Test
    fun steiner_three_way_lca_includes_long_sibling_branch_only_as_needed() {
        // Three living scattered under one LCA, with one of them on a long chain past the LCA.
        //   C → D → L1, C → E → L2, C → F → G → H → L3
        // Steiner should include C, D, L1, E, L2, F, G, H, L3 — every node on a path between
        // a pair of living. Nothing above C (since all living are inside its subtree and the
        // component has no living outside).
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(
                12L to DrocketLineageNode(12L, null, null, 0L, null, Sex.FEMALE, Genome()),  // C
                13L to DrocketLineageNode(13L, 12L, null, 100L, null, Sex.FEMALE, Genome()), // D
                14L to DrocketLineageNode(14L, 12L, null, 100L, null, Sex.FEMALE, Genome()), // E
                15L to DrocketLineageNode(15L, 13L, null, 200L, null, Sex.FEMALE, Genome()), // L1
                16L to DrocketLineageNode(16L, 14L, null, 200L, null, Sex.FEMALE, Genome()), // L2
                20L to DrocketLineageNode(20L, 12L, null, 100L, null, Sex.FEMALE, Genome()), // F
                21L to DrocketLineageNode(21L, 20L, null, 200L, null, Sex.FEMALE, Genome()), // G
                22L to DrocketLineageNode(22L, 21L, null, 300L, null, Sex.FEMALE, Genome()), // H
                23L to DrocketLineageNode(23L, 22L, null, 400L, null, Sex.FEMALE, Genome()), // L3
            ),
            livingLineageIds = setOf(15L, 16L, 23L),
        )
        val visible = computeVisibleLineageIds(lineage, CladogramLayout.build(lineage), CladogramFilterMode.LIVING_STEINER)
        assertEquals(setOf(12L, 13L, 14L, 15L, 16L, 20L, 21L, 22L, 23L), visible)
    }

    @Test
    fun steiner_separate_components_dont_pull_each_other_in() {
        // Two disconnected family trees, one living in each. Neither LCA depends on the
        // other — each component contributes only its living node.
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(
                1L to DrocketLineageNode(1L, null, null, 0L, null, Sex.FEMALE, Genome()),
                2L to DrocketLineageNode(2L, 1L, null, 100L, null, Sex.FEMALE, Genome()),
                10L to DrocketLineageNode(10L, null, null, 0L, null, Sex.FEMALE, Genome()),
                11L to DrocketLineageNode(11L, 10L, null, 100L, null, Sex.FEMALE, Genome()),
            ),
            livingLineageIds = setOf(2L, 11L),
        )
        val visible = computeVisibleLineageIds(lineage, CladogramLayout.build(lineage), CladogramFilterMode.LIVING_STEINER)
        assertEquals(setOf(2L, 11L), visible)
    }
}
