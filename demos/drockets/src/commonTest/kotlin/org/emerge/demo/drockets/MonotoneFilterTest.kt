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
        val ctx = Context(lineage, CladogramFilterMode.LIVING_STEINER)
        val view = ctx.apply(mf, lineage, setOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), view, "seed call returns the seed verbatim")
    }

    @Test
    fun steiner_prunes_trunk_after_luca_shift() {
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
        val ctx = Context(initial, CladogramFilterMode.LIVING_STEINER)
        ctx.apply(mf, initial, setOf(2L, 3L, 4L))

        val afterDeath = initial.copy(livingLineageIds = setOf(4L))
        val view = ctx.apply(mf, afterDeath)
        assertEquals(setOf(4L), view, "M and A permanently pruned; only B remains")
    }

    @Test
    fun pruned_nodes_dont_come_back_after_new_births() {
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
        val ctx = Context(initial, CladogramFilterMode.LIVING_STEINER)
        ctx.apply(mf, initial, setOf(2L, 3L, 4L))

        val afterDeath = initial.copy(livingLineageIds = setOf(4L))
        ctx.apply(mf, afterDeath)

        val afterBirth = afterDeath.copy(
            nextLineageId = 6L,
            nodes = afterDeath.nodes + (5L to node(5L, mother = 4L)),
            livingLineageIds = setOf(4L, 5L),
        )
        val view = ctx.apply(mf, afterBirth)
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
        val ctx = Context(initial, CladogramFilterMode.LIVING_STEINER)
        ctx.apply(mf, initial, setOf(1L, 2L))

        val afterBirth = initial.copy(
            nextLineageId = 4L,
            nodes = initial.nodes + (3L to node(3L, mother = 2L)),
            livingLineageIds = setOf(2L, 3L),
        )
        val view = ctx.apply(mf, afterBirth)
        assertTrue(3L in view, "C's parent (2) is in visible → C joins")
    }

    @Test
    fun new_birth_does_not_join_when_no_parent_is_visible() {
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
        val ctx = Context(initial, CladogramFilterMode.LIVING_FOCUSED)
        // Seed visible as if Focused had picked F1's clade only.
        ctx.apply(mf, initial, setOf(1L, 3L))

        val afterBirth = initial.copy(
            nextLineageId = 6L,
            nodes = initial.nodes + (5L to node(5L, mother = 4L)),
            livingLineageIds = setOf(3L, 4L, 5L),
        )
        val view = ctx.apply(mf, afterBirth)
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
        val ctx1 = Context(lineage, CladogramFilterMode.LIVING_STEINER)
        ctx1.apply(mf, lineage, setOf(1L, 2L))
        // Switch mode. New mode re-seeds from its own raw — old visible wiped.
        val ctx2 = Context(lineage, CladogramFilterMode.LIVING_FOCUSED)
        val view = ctx2.apply(mf, lineage, setOf(2L))
        assertEquals(setOf(2L), view)
    }

    @Test
    fun snapshot_regression_resets_visible() {
        val firstLineage = lineageOf(
            nodes = linkedMapOf(1L to node(1L)),
            living = setOf(1L),
        ).copy(nextLineageId = 100L)
        val mf = MonotoneFilter()
        val ctx = Context(firstLineage, CladogramFilterMode.LIVING_STEINER)
        ctx.apply(mf, firstLineage, setOf(1L))
        // Snapshot load: nextLineageId regresses below the watermark.
        val snapshotLineage = lineageOf(
            nodes = linkedMapOf(7L to node(7L)),
            living = setOf(7L),
        ).copy(nextLineageId = 8L)
        val ctx2 = Context(snapshotLineage, CladogramFilterMode.LIVING_STEINER)
        val view = ctx2.apply(mf, snapshotLineage, setOf(7L))
        assertEquals(setOf(7L), view, "regression triggers a reset; fresh seed used")
    }

    @Test
    fun reset_clears_state() {
        val lineage = lineageOf(
            nodes = linkedMapOf(1L to node(1L)),
            living = setOf(1L),
        )
        val mf = MonotoneFilter()
        val ctx = Context(lineage, CladogramFilterMode.LIVING_STEINER)
        ctx.apply(mf, lineage, setOf(1L))
        mf.reset()
        val view = ctx.apply(mf, lineage, setOf(1L))
        assertEquals(setOf(1L), view)
    }

    @Test
    fun living_only_visible_shrinks_as_livings_die() {
        // LIVING_ONLY on sub-universe: visible's livings. As they die, they get
        // pruned. Eventually if all livings die, visible empties.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
            ),
            living = setOf(1L, 2L),
        )
        val mf = MonotoneFilter()
        val ctx = Context(initial, CladogramFilterMode.LIVING_ONLY)
        ctx.apply(mf, initial, setOf(1L, 2L))
        val afterDeath = initial.copy(livingLineageIds = setOf(2L))
        val view = ctx.apply(mf, afterDeath)
        assertEquals(setOf(2L), view, "dead 1 pruned by LIVING_ONLY")
    }

    /** Per-test helper that holds a [LivingAncestryCache] and primes it before
     *  every `apply` so the cache state matches the lineage handed to
     *  [MonotoneFilter.apply]. */
    private class Context(
        seedLineage: DrocketLineageState,
        val filter: CladogramFilterMode,
    ) {
        val cache = LivingAncestryCache()
        private var lastLineage: DrocketLineageState = seedLineage

        fun apply(
            mf: MonotoneFilter,
            lineage: DrocketLineageState,
            seedRawOverride: Set<Long>? = null,
        ): Set<Long> {
            lastLineage = lineage
            val layout = CladogramLayout.build(lineage)
            // Drive ensureCurrent so applySubUniverseFilter has fresh state.
            val raw = when (filter) {
                CladogramFilterMode.LIVING_ANCESTRY -> cache.ancestryVisibleFor(lineage, layout)
                CladogramFilterMode.LIVING_STEINER -> cache.steinerVisibleFor(lineage, layout)
                CladogramFilterMode.LIVING_FOCUSED -> cache.lucaFocusedVisibleFor(lineage, layout)
                CladogramFilterMode.LIVING_AND_CONNECTORS -> cache.connectorsVisibleFor(lineage, layout)
                CladogramFilterMode.ALL -> cache.allVisibleFor(lineage, layout)
                CladogramFilterMode.LIVING_ONLY -> cache.livingOnlyVisibleFor(lineage, layout)
            }
            val seedRaw = seedRawOverride ?: raw
            return mf.apply(seedRaw, filter, lineage, cache)
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
