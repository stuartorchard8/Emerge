package org.emerge.demo.drockets

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForceDirectedLayoutSolverTest {
    @Test
    fun empty_visible_set_returns_empty_positions() {
        val solver = ForceDirectedLayoutSolver()
        val out = solver.step(
            layout = emptyLayout(),
            lineage = DrocketLineageState.EMPTY,
            visibleIds = emptySet(),
        )
        assertTrue(out.isEmpty())
        assertTrue(solver.isEmpty)
    }

    @Test
    fun new_node_with_two_parents_seeds_at_their_midpoint() {
        val mother = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val father = lineageNode(2L, null, null, Sex.MALE, 0L)
        val child = lineageNode(3L, motherId = 1L, fatherId = 2L, sex = Sex.FEMALE, birthTick = 10L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to mother, 2L to father, 3L to child),
            livingLineageIds = setOf(1L, 2L, 3L),
        )
        val layout = CladogramLayout.build(lineage)
        val solver = ForceDirectedLayoutSolver()
        solver.seedFrom(mapOf(
            1L to (-1.0f to 2.0f),
            2L to (3.0f to 5.0f),
        ))
        // Use a fresh visible set including the child; the very first thing step does
        // is seed any visible id not in state. positionOf reports the seed before
        // forces have moved it.
        solver.step(layout, lineage, visibleIds = setOf(1L, 2L, 3L))
        // We can't read the pre-step seed directly (step also integrates), but the
        // one-step displacement is bounded by MAX_DISPLACEMENT — so the post-step
        // child is within that of the midpoint (1.0, 3.5).
        val childPos = solver.positionOf(3L) ?: error("child missing")
        val midX = 1.0f
        val midY = 3.5f
        val dx = childPos.first - midX
        val dy = childPos.second - midY
        assertTrue(
            sqrt(dx * dx + dy * dy) < 0.02f,
            "child not seeded near parent midpoint: pos=$childPos, midpoint=($midX, $midY)",
        )
    }

    @Test
    fun new_node_seeds_near_its_parent() {
        val parent = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val child = lineageNode(2L, motherId = 1L, fatherId = null, sex = Sex.MALE, birthTick = 10L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to parent, 2L to child),
            livingLineageIds = setOf(1L, 2L),
        )
        val layout = CladogramLayout.build(lineage)
        val solver = ForceDirectedLayoutSolver()
        // Seed parent at a known position so we can assert the child seed is local to it.
        solver.seedFrom(mapOf(1L to (3.0f to 7.0f)))

        val out = solver.step(layout, lineage, visibleIds = setOf(1L, 2L))
        val childPos = out[2L]
        assertNotNull(childPos)
        // After exactly one integration step the child can have moved by at most
        // MAX_DISPLACEMENT (0.05) from its seed; the seed is < SEED_OFFSET (0.04) +
        // a spring-induced fraction from the parent at (3, 7). Allow a generous bound.
        val dx = childPos.first - 3.0f
        val dy = childPos.second - 7.0f
        val dist = sqrt(dx * dx + dy * dy)
        assertTrue(dist < 0.2f, "child seeded too far from parent (dist=$dist)")
        assertTrue(dist > 0f, "child must be perturbed off the parent's exact spot")
    }

    @Test
    fun chain_relaxes_with_bounded_spring_stretches() {
        // Three nodes: gp (visible root, buoyant) → parent → child (both non-roots,
        // gravity). At steady state the top spring (gp↔parent) carries the
        // accumulated gravity of every node below; the bottom spring (parent↔child)
        // only carries one node's worth. Both should be modestly stretched past REST
        // — modest, not unbounded.
        val gp = lineageNode(0L, null, null, Sex.FEMALE, 0L)
        val parent = lineageNode(1L, motherId = 0L, fatherId = null, sex = Sex.FEMALE, birthTick = 1L)
        val child = lineageNode(2L, motherId = 1L, fatherId = null, sex = Sex.MALE, birthTick = 10L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(0L to gp, 1L to parent, 2L to child),
            livingLineageIds = setOf(2L),
        )
        val layout = CladogramLayout.build(lineage)
        val solver = ForceDirectedLayoutSolver()
        solver.seedFrom(mapOf(
            0L to (0.0f to 0.0f),
            1L to (1.0f to 0.0f),
            2L to (2.0f to 0.0f),
        ))

        repeat(2000) {
            solver.step(layout, lineage, visibleIds = setOf(0L, 1L, 2L))
        }
        val out = solver.step(layout, lineage, visibleIds = setOf(0L, 1L, 2L))
        val gpp = out.getValue(0L)
        val pp = out.getValue(1L)
        val cp = out.getValue(2L)
        val dTop = sqrt((pp.first - gpp.first).let { it * it } + (pp.second - gpp.second).let { it * it })
        val dBot = sqrt((cp.first - pp.first).let { it * it } + (cp.second - pp.second).let { it * it })
        // Physical invariants only — generous absolute bounds so tuning experiments
        // (eg different SPRING_K, GRAVITY_K) don't break this test. Under the
        // "hung from both ends" model both springs in this 3-node chain carry the
        // same single-leaf weight, so their stretches are roughly equal; the
        // assertion just checks they stay finite and bounded.
        assertTrue(dBot.isFinite() && dBot > 0f, "bottom spring collapsed/NaN: dBot=$dBot")
        assertTrue(dTop.isFinite() && dTop > 0f, "top spring collapsed/NaN: dTop=$dTop")
        assertTrue(dBot < ForceDirectedLayoutSolver.REST_LENGTH * 10f,
            "bottom spring exploded: dBot=$dBot")
        assertTrue(dTop < ForceDirectedLayoutSolver.REST_LENGTH * 10f,
            "top spring exploded: dTop=$dTop")
    }

    @Test
    fun visible_root_floats_above_descendants() {
        // Buoyancy on the visible root + gravity on its descendants should leave the
        // root visibly higher (greater y) than any node beneath it once the chain
        // relaxes from a far-apart seed.
        val root = lineageNode(0L, null, null, Sex.FEMALE, 0L)
        val mid = lineageNode(1L, motherId = 0L, fatherId = null, sex = Sex.FEMALE, birthTick = 1L)
        val leaf = lineageNode(2L, motherId = 1L, fatherId = null, sex = Sex.MALE, birthTick = 10L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(0L to root, 1L to mid, 2L to leaf),
            livingLineageIds = setOf(2L),
        )
        val layout = CladogramLayout.build(lineage)
        val solver = ForceDirectedLayoutSolver()
        // Seed at the same height so any vertical separation comes from buoyancy/gravity,
        // not the seed itself.
        solver.seedFrom(mapOf(
            0L to (0.0f to 0.0f),
            1L to (0.1f to 0.0f),
            2L to (0.2f to 0.0f),
        ))

        repeat(2000) {
            solver.step(layout, lineage, visibleIds = setOf(0L, 1L, 2L))
        }
        val out = solver.step(layout, lineage, visibleIds = setOf(0L, 1L, 2L))
        val rootY = out.getValue(0L).second
        val midY = out.getValue(1L).second
        val leafY = out.getValue(2L).second
        assertTrue(rootY > midY, "root y=$rootY should be above mid y=$midY")
        assertTrue(midY > leafY, "mid y=$midY should be above leaf y=$leafY")
    }

    @Test
    fun vertically_offset_nodes_repel_along_the_line_between_them() {
        // Two unconnected nodes at the same x but different y. Under isotropic
        // 1/r² repulsion the force vector lies along the line between them — for
        // a pure-y offset that means the force is pure-y, with no horizontal
        // component, and the pair drifts further apart vertically.
        val a = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val b = lineageNode(2L, null, null, Sex.FEMALE, 0L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to a, 2L to b),
            livingLineageIds = setOf(1L, 2L),
        )
        val layout = CladogramLayout(
            positions = emptyMap(),
            edges = emptyList(),
            depthById = mapOf(1L to 0, 2L to 0),
            stats = CladogramStats(2, 2, 0, 0, 0, 2),
        )
        val solver = ForceDirectedLayoutSolver()
        solver.seedFrom(mapOf(1L to (0.0f to 0.0f), 2L to (0.0f to 0.05f)))

        repeat(300) {
            solver.step(layout, lineage, visibleIds = setOf(1L, 2L))
        }
        val pa = solver.positionOf(1L) ?: error("missing 1L")
        val pb = solver.positionOf(2L) ?: error("missing 2L")
        val dx = abs(pa.first - pb.first)
        val dy = abs(pa.second - pb.second)
        assertTrue(dy > 0.05f, "vertically-offset pair didn't separate further in y: |dy|=$dy")
        assertTrue(dx < 1e-4f, "pure-y offset shouldn't produce horizontal drift: |dx|=$dx")
    }

    @Test
    fun coincident_nodes_repel_apart() {
        val a = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val b = lineageNode(2L, null, null, Sex.FEMALE, 0L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to a, 2L to b),
            livingLineageIds = setOf(1L, 2L),
        )
        // Build a layout that doesn't connect them with an edge so only repulsion acts.
        val layout = CladogramLayout(
            positions = emptyMap(),
            edges = emptyList(),
            depthById = mapOf(1L to 0, 2L to 0),
            stats = CladogramStats(2, 2, 0, 0, 0, 2),
        )
        val solver = ForceDirectedLayoutSolver()
        solver.seedFrom(mapOf(1L to (0.0f to 0.0f), 2L to (0.0f to 0.0f)))

        repeat(20) {
            solver.step(layout, lineage, visibleIds = setOf(1L, 2L))
        }
        val out = solver.step(layout, lineage, visibleIds = setOf(1L, 2L))
        val pa = out.getValue(1L)
        val pb = out.getValue(2L)
        val d2 = (pa.first - pb.first).let { it * it } + (pa.second - pb.second).let { it * it }
        assertTrue(d2 > 1e-6f, "coincident nodes failed to separate (d²=$d2)")
    }

    @Test
    fun positions_persist_across_filter_changes() {
        val parent = lineageNode(1L, null, null, Sex.FEMALE, 0L, deathTick = 5L)
        val child = lineageNode(2L, motherId = 1L, fatherId = null, sex = Sex.MALE, birthTick = 10L)
        val lineage = DrocketLineageState(
            nodes = linkedMapOf(1L to parent, 2L to child),
            livingLineageIds = setOf(2L), // parent is dead
        )
        val layout = CladogramLayout.build(lineage)
        val solver = ForceDirectedLayoutSolver()
        solver.seedFrom(mapOf(1L to (0.0f to 0.0f), 2L to (0.1f to -0.1f)))

        // Pretend the filter has hidden the dead parent — only the child is "visible".
        repeat(5) {
            solver.step(layout, lineage, visibleIds = setOf(2L))
        }
        // The hidden parent's retained position should be exactly the seed: no force ran
        // on it across any of the steps above. positionOf reads state without stepping.
        val retained = solver.positionOf(1L)
        assertNotNull(retained)
        assertEquals(0.0f, retained.first)
        assertEquals(0.0f, retained.second)
    }

    @Test
    fun forgotten_lineage_state_is_dropped() {
        val a = lineageNode(1L, null, null, Sex.FEMALE, 0L)
        val full = DrocketLineageState(
            nodes = linkedMapOf(1L to a),
            livingLineageIds = setOf(1L),
        )
        val solver = ForceDirectedLayoutSolver()
        solver.step(CladogramLayout.build(full), full, visibleIds = setOf(1L))
        assertTrue(!solver.isEmpty)

        // Snapshot replacement: a new lineage doesn't contain id 1L anymore.
        val empty = DrocketLineageState(nodes = emptyMap(), livingLineageIds = emptySet())
        solver.step(emptyLayout(), empty, visibleIds = emptySet())
        assertTrue(solver.isEmpty, "state for forgotten ids should be cleaned up")
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
        stats = CladogramStats(0, 0, 0, 0, 0, 0),
    )
}
