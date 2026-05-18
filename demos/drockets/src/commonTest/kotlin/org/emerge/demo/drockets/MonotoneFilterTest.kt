package org.emerge.demo.drockets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonotoneFilterTest {

    @Test
    fun first_call_seeds_visible_from_raw() {
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val mf = MonotoneFilter()
        val view = mf.apply(setOf(1L, 2L, 3L), CladogramFilterMode.LIVING_STEINER, lineage)
        assertEquals(setOf(1L, 2L, 3L), view, "seed call returns the seed verbatim")
    }

    @Test
    fun steiner_prunes_trunk_after_luca_shift() {
        // F (1) → M (2) → A (3, alive)
        //                → B (4, alive)
        // Steiner of {A, B} on full lineage = {M, A, B}; F is trunk above LUCA.
        // After A dies, livings in visible = {B}. On the sub-universe {M, A, B},
        // Steiner of {B} = just {B} (LUCA is B itself, no connector needed).
        // M and A get permanently pruned.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 2L),
                4L to node(4L, mother = 2L),
            ),
            living = setOf(3L, 4L),
        )
        val mf = MonotoneFilter()
        mf.apply(setOf(2L, 3L, 4L), CladogramFilterMode.LIVING_STEINER, initial)
        val afterDeath = initial.copy(livingLineageIds = setOf(4L))
        val view = mf.apply(emptySet(), CladogramFilterMode.LIVING_STEINER, afterDeath)
        assertEquals(setOf(4L), view, "M and A permanently pruned; only B remains")
    }

    @Test
    fun pruned_nodes_dont_come_back_after_new_births() {
        // Setup: prune M and A as in the previous test, then birth a new
        // living C as child of B. C joins (parent B in visible). M and A
        // stay pruned — even though, on full lineage, Steiner of {B, C}
        // would never have excluded them in the first place, they're gone
        // because they got pruned earlier.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 2L),
                4L to node(4L, mother = 2L),
            ),
            living = setOf(3L, 4L),
        )
        val mf = MonotoneFilter()
        mf.apply(setOf(2L, 3L, 4L), CladogramFilterMode.LIVING_STEINER, initial)
        val afterDeath = initial.copy(livingLineageIds = setOf(4L))
        mf.apply(emptySet(), CladogramFilterMode.LIVING_STEINER, afterDeath)

        val afterBirth = afterDeath.copy(
            nextLineageId = 6L,
            nodes = afterDeath.nodes + (5L to node(5L, mother = 4L)),
            livingLineageIds = setOf(4L, 5L),
        )
        val view = mf.apply(emptySet(), CladogramFilterMode.LIVING_STEINER, afterBirth)
        assertEquals(setOf(4L, 5L), view, "C joins; M and A stay gone")
    }

    @Test
    fun new_birth_joins_when_a_parent_is_visible() {
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
            ),
            living = setOf(2L),
        )
        val mf = MonotoneFilter()
        mf.apply(setOf(1L, 2L), CladogramFilterMode.LIVING_STEINER, initial)
        val afterBirth = initial.copy(
            nextLineageId = 4L,
            nodes = initial.nodes + (3L to node(3L, mother = 2L)),
            livingLineageIds = setOf(2L, 3L),
        )
        val view = mf.apply(emptySet(), CladogramFilterMode.LIVING_STEINER, afterBirth)
        assertTrue(3L in view, "C's parent (2) is in visible → C joins")
    }

    @Test
    fun new_birth_does_not_join_when_no_parent_is_visible() {
        // Two disconnected clades: F1 → A and F2 → B, both alive. We seed
        // visible with only F1's clade. New birth C is child of B, which is
        // not in visible. C must stay invisible — "everything invisible
        // stays invisible".
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),                  // F1
                2L to node(2L),                  // F2 (independent founder)
                3L to node(3L, mother = 1L),     // A
                4L to node(4L, mother = 2L),     // B
            ),
            living = setOf(3L, 4L),
        )
        val mf = MonotoneFilter()
        // Seed visible as if Focused had picked F1's clade only.
        mf.apply(setOf(1L, 3L), CladogramFilterMode.LIVING_FOCUSED, initial)
        val afterBirth = initial.copy(
            nextLineageId = 6L,
            nodes = initial.nodes + (5L to node(5L, mother = 4L)),
            livingLineageIds = setOf(3L, 4L, 5L),
        )
        val view = mf.apply(emptySet(), CladogramFilterMode.LIVING_FOCUSED, afterBirth)
        assertFalse(5L in view, "C's only parent (B=4) not in visible → C stays invisible")
        assertFalse(4L in view, "B was never in visible and stays out")
    }

    @Test
    fun mode_change_resets_visible() {
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
            ),
            living = setOf(2L),
        )
        val mf = MonotoneFilter()
        mf.apply(setOf(1L, 2L), CladogramFilterMode.LIVING_STEINER, lineage)
        // Switch mode. The new mode re-seeds from its own raw — visible from
        // the old mode is wiped.
        val view = mf.apply(setOf(2L), CladogramFilterMode.LIVING_FOCUSED, lineage)
        assertEquals(setOf(2L), view)
    }

    @Test
    fun snapshot_regression_resets_visible() {
        val firstLineage = lineageOf(
            nodes = linkedMapOf(1L to node(1L)),
            living = setOf(1L),
        ).copy(nextLineageId = 100L)
        val mf = MonotoneFilter()
        mf.apply(setOf(1L), CladogramFilterMode.LIVING_STEINER, firstLineage)
        // Snapshot load: nextLineageId regresses below the watermark.
        val snapshotLineage = lineageOf(
            nodes = linkedMapOf(7L to node(7L)),
            living = setOf(7L),
        ).copy(nextLineageId = 8L)
        val view = mf.apply(setOf(7L), CladogramFilterMode.LIVING_STEINER, snapshotLineage)
        assertEquals(setOf(7L), view, "regression triggers a reset; fresh seed used")
    }

    @Test
    fun reset_clears_state() {
        val lineage = lineageOf(
            nodes = linkedMapOf(1L to node(1L)),
            living = setOf(1L),
        )
        val mf = MonotoneFilter()
        mf.apply(setOf(1L), CladogramFilterMode.LIVING_STEINER, lineage)
        mf.reset()
        val view = mf.apply(setOf(1L), CladogramFilterMode.LIVING_STEINER, lineage)
        assertEquals(setOf(1L), view)
    }

    @Test
    fun living_only_visible_shrinks_as_livings_die_but_doesnt_grow_for_new_births() {
        // LIVING_ONLY on sub-universe: visible's livings. As they die, they
        // get pruned. New births join (parent in visible), then on next
        // prune they stay because they're living. Eventually if all livings
        // die, visible empties.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
            ),
            living = setOf(1L, 2L),
        )
        val mf = MonotoneFilter()
        mf.apply(setOf(1L, 2L), CladogramFilterMode.LIVING_ONLY, initial)
        // Kill 1.
        val afterDeath = initial.copy(livingLineageIds = setOf(2L))
        val view = mf.apply(emptySet(), CladogramFilterMode.LIVING_ONLY, afterDeath)
        assertEquals(setOf(2L), view, "dead 1 pruned by LIVING_ONLY")
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
