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
}
