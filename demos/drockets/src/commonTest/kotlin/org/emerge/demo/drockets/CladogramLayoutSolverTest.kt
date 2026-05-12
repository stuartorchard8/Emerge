package org.emerge.demo.drockets

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CladogramLayoutSolverTest {
    @Test
    fun empty_lineage_returns_empty_positions() {
        val solution = CladogramLayoutSolver.solve(
            layout = emptyLayout(),
            lineage = DrocketLineageState.EMPTY,
            filterMode = CladogramFilterMode.ALL,
        )
        assertTrue(solution.positions.isEmpty())
    }

    @Test
    fun single_root_node_lays_out_at_origin_after_centering() {
        val node = lineageNode(id = 1L, motherId = null, fatherId = null, sex = Sex.FEMALE, birthTick = 0L)
        val lineage = DrocketLineageState(nodes = mapOf(1L to node), livingLineageIds = setOf(1L))
        val solution = CladogramLayoutSolver.solve(
            layout = CladogramLayout.build(lineage),
            lineage = lineage,
            filterMode = CladogramFilterMode.ALL,
        )
        val pos = solution.positions[1L]
        assertNotNull(pos)
        // Single node centred around its own mean -> x ≈ 0, y at depth 0 -> 0.
        assertTrue(abs(pos.first) < 1e-5f, "expected x≈0, got ${pos.first}")
        assertEquals(0f, pos.second)
    }

    @Test
    fun parent_child_chain_places_child_below_parent() {
        val parent = lineageNode(id = 1L, motherId = null, fatherId = null, sex = Sex.FEMALE, birthTick = 0L)
        val child = lineageNode(id = 2L, motherId = 1L, fatherId = null, sex = Sex.MALE, birthTick = 100L)
        val lineage = DrocketLineageState(nodes = linkedMapOf(1L to parent, 2L to child), livingLineageIds = setOf(2L))
        val solution = CladogramLayoutSolver.solve(
            layout = CladogramLayout.build(lineage),
            lineage = lineage,
            filterMode = CladogramFilterMode.ALL,
        )
        val parentPos = solution.positions[1L]
        val childPos = solution.positions[2L]
        assertNotNull(parentPos)
        assertNotNull(childPos)
        // Child is a generation below parent; y axis grows negative downward.
        assertEquals(0f, parentPos.second)
        assertEquals(-CladogramLayoutSolver.GENERATION_Y_SPACING, childPos.second)
        // Single chain: child x aligns with parent x after solver convergence.
        assertTrue(
            abs(childPos.first - parentPos.first) < 1e-5f,
            "child x ${childPos.first} should align with parent x ${parentPos.first}",
        )
    }

    @Test
    fun siblings_at_same_depth_do_not_overlap() {
        val parent = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val childA = lineageNode(2L, 1L, null, Sex.FEMALE, 10L)
        val childB = lineageNode(3L, 1L, null, Sex.MALE, 20L)
        val childC = lineageNode(4L, 1L, null, Sex.FEMALE, 30L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to parent, 2L to childA, 3L to childB, 4L to childC),
            livingLineageIds = setOf(2L, 3L, 4L),
        )
        val solution = CladogramLayoutSolver.solve(
            layout = CladogramLayout.build(lineage),
            lineage = lineage,
            filterMode = CladogramFilterMode.ALL,
        )
        val xA = solution.positions[2L]?.first ?: error("missing 2L")
        val xB = solution.positions[3L]?.first ?: error("missing 3L")
        val xC = solution.positions[4L]?.first ?: error("missing 4L")
        val xs = listOf(xA, xB, xC).sorted()
        val minSpacing = CladogramLayoutSolver.NODE_X_SPACING - 1e-5f
        assertTrue(xs[1] - xs[0] >= minSpacing, "spacing(${xs[0]}, ${xs[1]}) = ${xs[1] - xs[0]} below ${CladogramLayoutSolver.NODE_X_SPACING}")
        assertTrue(xs[2] - xs[1] >= minSpacing, "spacing(${xs[1]}, ${xs[2]}) = ${xs[2] - xs[1]} below ${CladogramLayoutSolver.NODE_X_SPACING}")
    }

    @Test
    fun disconnected_components_are_placed_side_by_side() {
        // Two separate roots, each with one child. No edges between the components.
        val r1 = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val c1 = lineageNode(2L, 1L, null, Sex.MALE, 10L)
        val r2 = lineageNode(3L, null, null, Sex.FEMALE, 0L)
        val c2 = lineageNode(4L, 3L, null, Sex.MALE, 10L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to r1, 2L to c1, 3L to r2, 4L to c2),
            livingLineageIds = setOf(2L, 4L),
        )
        val solution = CladogramLayoutSolver.solve(
            layout = CladogramLayout.build(lineage),
            lineage = lineage,
            filterMode = CladogramFilterMode.ALL,
        )
        val r1x = solution.positions[1L]?.first ?: error("missing 1L")
        val r2x = solution.positions[3L]?.first ?: error("missing 3L")
        // Side-by-side placement: roots in different components must be at least one node-spacing apart.
        assertTrue(
            abs(r1x - r2x) >= CladogramLayoutSolver.NODE_X_SPACING - 1e-5f,
            "components too close: r1x=$r1x r2x=$r2x",
        )
    }

    @Test
    fun living_only_filter_excludes_dead_nodes_from_positions() {
        val parent = lineageNode(1L, null, null, Sex.FEMALE, 0L, deathTick = 50L)
        val child = lineageNode(2L, 1L, null, Sex.MALE, 100L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to parent, 2L to child),
            livingLineageIds = setOf(2L), // parent is dead
        )
        val solution = CladogramLayoutSolver.solve(
            layout = CladogramLayout.build(lineage),
            lineage = lineage,
            filterMode = CladogramFilterMode.LIVING_ONLY,
        )
        assertTrue(solution.positions.containsKey(2L))
        assertTrue(
            !solution.positions.containsKey(1L),
            "dead parent should be filtered out in LIVING_ONLY mode",
        )
    }

    @Test
    fun dead_only_generations_collapse_in_living_only_view() {
        // Three generations; middle generation is entirely dead. Living-only view should
        // place the youngest immediately below the eldest with no empty band in between.
        val gen0 = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val gen1Dead = lineageNode(2L, 1L, null, Sex.FEMALE, 100L, deathTick = 200L)
        val gen2 = lineageNode(3L, 2L, null, Sex.MALE, 300L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to gen0, 2L to gen1Dead, 3L to gen2),
            livingLineageIds = setOf(1L, 3L),
        )
        val solution = CladogramLayoutSolver.solve(
            layout = CladogramLayout.build(lineage),
            lineage = lineage,
            filterMode = CladogramFilterMode.LIVING_ONLY,
        )
        val y0 = solution.positions[1L]?.second ?: error("missing 1L")
        val y2 = solution.positions[3L]?.second ?: error("missing 3L")
        // gen0 stays at depth 0, gen2 collapses up to depth 1 (one row below), not 2.
        assertEquals(0f, y0)
        assertEquals(-CladogramLayoutSolver.GENERATION_Y_SPACING, y2)
    }

    @Test
    fun seeded_positions_are_preserved_under_low_churn() {
        // First solve to establish a baseline layout, then re-solve with that as seed and
        // confirm we get the same positions back (no node churn -> incremental budget kicks in).
        val nodes = (1L..5L).map { id ->
            id to lineageNode(id, motherId = null, fatherId = null, sex = Sex.FEMALE, birthTick = id * 10L)
        }.toMap(linkedMapOf<Long, DrocketLineageNode>())
        val lineage = DrocketLineageState(nodes = nodes, livingLineageIds = nodes.keys.toSet())
        val layout = CladogramLayout.build(lineage)

        val first = CladogramLayoutSolver.solve(layout, lineage, CladogramFilterMode.ALL)
        val second = CladogramLayoutSolver.solve(layout, lineage, CladogramFilterMode.ALL, first.positions)

        // Same input → same output (in particular: no drift across re-solves).
        for ((id, pos) in first.positions) {
            val pos2 = second.positions[id] ?: error("$id missing on re-solve")
            assertTrue(abs(pos.first - pos2.first) < 1e-4f, "x drift on $id: ${pos.first} vs ${pos2.first}")
            assertEquals(pos.second, pos2.second, "y drift on $id")
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun lineageNode(
        id: Long,
        motherId: Long?,
        fatherId: Long?,
        sex: Sex,
        birthTick: Long,
        deathTick: Long? = null,
    ) = DrocketLineageNode(
        lineageId = id,
        motherLineageId = motherId,
        fatherLineageId = fatherId,
        birthTick = birthTick,
        deathTick = deathTick,
        sex = sex,
        genome = Genome(),
    )

    private fun emptyLayout() = CladogramLayout(
        positions = emptyMap(),
        edges = emptyList(),
        depthById = emptyMap(),
        stats = CladogramStats(
            nodeCount = 0,
            livingCount = 0,
            deadCount = 0,
            maxDepth = 0,
            edgeCount = 0,
            rootCount = 0,
        ),
    )
}
