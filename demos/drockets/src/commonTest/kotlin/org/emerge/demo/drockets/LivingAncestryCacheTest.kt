package org.emerge.demo.drockets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class LivingAncestryCacheTest {
    @Test
    fun cache_matches_stateless_function_on_initial_state() {
        // A small lineage with two living descended from a shared grandparent. Full
        // ancestry includes the grandparent, the parents (dead but on the chain), and
        // both livings.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 2L),
                5L to node(5L, mother = 3L),
            ),
            living = setOf(4L, 5L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val cached = cache.ancestryVisibleFor(lineage, layout)
        val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_ANCESTRY)
        assertEquals(stateless, cached, "cache result should match stateless on first call")
    }

    @Test
    fun cache_handles_a_birth_incrementally_and_matches_stateless() {
        // Start with two living, then add a third living through an existing parent.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 2L),
                5L to node(5L, mother = 3L),
            ),
            living = setOf(4L, 5L),
        )
        val cache = LivingAncestryCache()
        cache.ancestryVisibleFor(initial, CladogramLayout.build(initial))

        // New birth: 6 born to 3.
        val afterBirth = lineageOf(
            nodes = initial.nodes + (6L to node(6L, mother = 3L)),
            living = initial.livingLineageIds + 6L,
        )
        val cachedAfterBirth = cache.ancestryVisibleFor(afterBirth, CladogramLayout.build(afterBirth))
        val statelessAfterBirth =
            computeVisibleLineageIds(afterBirth, CladogramLayout.build(afterBirth), CladogramFilterMode.LIVING_ANCESTRY)
        assertEquals(statelessAfterBirth, cachedAfterBirth)
    }

    @Test
    fun cache_handles_a_death_incrementally_and_matches_stateless() {
        // Three living with a shared ancestor. Kill the middle one and confirm the
        // cache drops it from visible while keeping the rest correct.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 1L),
                5L to node(5L, mother = 2L),
                6L to node(6L, mother = 3L),
                7L to node(7L, mother = 4L),
            ),
            living = setOf(5L, 6L, 7L),
        )
        val cache = LivingAncestryCache()
        cache.ancestryVisibleFor(initial, CladogramLayout.build(initial))

        val afterDeath = initial.copy(livingLineageIds = setOf(5L, 7L))
        val cachedAfterDeath = cache.ancestryVisibleFor(afterDeath, CladogramLayout.build(afterDeath))
        val statelessAfterDeath =
            computeVisibleLineageIds(afterDeath, CladogramLayout.build(afterDeath), CladogramFilterMode.LIVING_ANCESTRY)
        assertEquals(statelessAfterDeath, cachedAfterDeath)
    }

    @Test
    fun cache_keeps_dead_node_visible_if_on_living_ancestry() {
        // 4 is an ancestor of livings 5 and 6. When 4 then dies, it stays in the
        // visible set because both 5 and 6 still trace ancestry through it.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                4L to node(4L, mother = 1L),
                2L to node(2L, mother = 4L),
                3L to node(3L, mother = 4L),
                5L to node(5L, mother = 2L),
                6L to node(6L, mother = 3L),
            ),
            living = setOf(4L, 5L, 6L),
        )
        val cache = LivingAncestryCache()
        cache.ancestryVisibleFor(initial, CladogramLayout.build(initial))

        val afterDeath = initial.copy(livingLineageIds = setOf(5L, 6L))
        val cachedAfterDeath = cache.ancestryVisibleFor(afterDeath, CladogramLayout.build(afterDeath))
        val statelessAfterDeath =
            computeVisibleLineageIds(afterDeath, CladogramLayout.build(afterDeath), CladogramFilterMode.LIVING_ANCESTRY)
        assertEquals(statelessAfterDeath, cachedAfterDeath)
        // Sanity: 4 must still be visible as an ancestor of the remaining livings.
        assertEquals(true, 4L in cachedAfterDeath, "ancestor of remaining livings must stay visible")
    }

    @Test
    fun cache_drops_orphaned_branch_when_only_living_descendant_dies() {
        // Branch 7 is the lone living descendant of 4 (a sibling of the chain holding
        // 5 and 6). When 7 dies, both 7 and its dedicated ancestor 4 should drop out;
        // the rest of the tree stays visible because livings 5 and 6 still cover it.
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 1L),
                5L to node(5L, mother = 2L),
                6L to node(6L, mother = 3L),
                7L to node(7L, mother = 4L),
            ),
            living = setOf(5L, 6L, 7L),
        )
        val cache = LivingAncestryCache()
        val before = cache.ancestryVisibleFor(initial, CladogramLayout.build(initial))
        assertEquals(true, 4L in before, "4 visible while 7 alive")
        assertEquals(true, 7L in before, "7 visible while alive")

        val afterDeath = initial.copy(livingLineageIds = setOf(5L, 6L))
        val cachedAfterDeath = cache.ancestryVisibleFor(afterDeath, CladogramLayout.build(afterDeath))
        val statelessAfterDeath =
            computeVisibleLineageIds(afterDeath, CladogramLayout.build(afterDeath), CladogramFilterMode.LIVING_ANCESTRY)
        assertEquals(statelessAfterDeath, cachedAfterDeath)
        assertEquals(false, 4L in cachedAfterDeath, "4's only living descendant died → 4 invisible")
        assertEquals(false, 7L in cachedAfterDeath, "7 itself no longer living")
    }

    @Test
    fun cache_matches_stateless_through_a_long_birth_death_sequence() {
        // Run 50 random-ish birth/death events. After each, the cache result must match
        // the stateless function recomputed from scratch. Catches any incremental
        // bookkeeping drift.
        var lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val cache = LivingAncestryCache()
        cache.ancestryVisibleFor(lineage, CladogramLayout.build(lineage))

        var nextId = 4L
        val rng = kotlin.random.Random(1)
        for (step in 0 until 50) {
            val living = lineage.livingLineageIds.toList()
            // 60% chance birth, 40% chance death (skewed to grow population)
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
            val layout = CladogramLayout.build(lineage)
            val cached = cache.ancestryVisibleFor(lineage, layout)
            val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_ANCESTRY)
            assertEquals(stateless, cached, "step $step diverged: cache=$cached, stateless=$stateless")
        }
    }

    @Test
    fun cache_resets_when_lineage_shrinks_via_snapshot_load() {
        // Simulate a snapshot-load that replaces the lineage with a smaller earlier state.
        // The cache should detect the missing nodes and fully rebuild rather than serve
        // stale data.
        val full = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 2L),
                5L to node(5L, mother = 3L),
            ),
            living = setOf(4L, 5L),
        )
        val cache = LivingAncestryCache()
        cache.ancestryVisibleFor(full, CladogramLayout.build(full))

        // "Load" an earlier state with fewer nodes.
        val earlier = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val cachedAfterLoad = cache.ancestryVisibleFor(earlier, CladogramLayout.build(earlier))
        val statelessAfterLoad =
            computeVisibleLineageIds(earlier, CladogramLayout.build(earlier), CladogramFilterMode.LIVING_ANCESTRY)
        assertEquals(statelessAfterLoad, cachedAfterLoad)
        // Old nodes 4, 5 must not appear.
        assertEquals(false, 4L in cachedAfterLoad)
        assertEquals(false, 5L in cachedAfterLoad)
    }

    // ── LIVING_STEINER mode ─────────────────────────────────────────────────────

    @Test
    fun steiner_excludes_trunk_above_lca() {
        // A → B → C → {D → L1, E → L2}. LUCA = C. Steiner = {C, D, L1, E, L2}.
        // A and B are the "trunk above LUCA" — universal ancestors of all livings
        // but with only one living-bearing branch, so they're excluded.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),                       // A
                2L to node(2L, mother = 1L),          // B
                3L to node(3L, mother = 2L),          // C (LUCA)
                4L to node(4L, mother = 3L),          // D
                5L to node(5L, mother = 3L),          // E
                6L to node(6L, mother = 4L),          // L1
                7L to node(7L, mother = 5L),          // L2
            ),
            living = setOf(6L, 7L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val steiner = cache.steinerVisibleFor(lineage, layout)
        val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_STEINER)
        assertEquals(stateless, steiner, "cache vs stateless Steiner")
        assertEquals(setOf(3L, 4L, 5L, 6L, 7L), steiner, "LUCA at top, no trunk above")
    }

    @Test
    fun steiner_single_living_shows_only_that_living() {
        // T=1 case: no Steiner subgraph to construct, just the lone living.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 2L),
            ),
            living = setOf(3L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val steiner = cache.steinerVisibleFor(lineage, layout)
        assertEquals(setOf(3L), steiner)
    }

    @Test
    fun steiner_includes_lca_with_dag_multi_parent_living() {
        // L1 has two parents P1a, P1b, both descended from G. L2 is also G's
        // descendant via a separate sibling. G is the LUCA. Both P1a and P1b
        // are visible because both have lDC > 0 and lDC < T (they don't each
        // cover both livings — only their child L1).
        //
        //         G  (id 1)
        //        /|\
        //      P1a P1b L2  (ids 2, 3, 5)
        //        \ /
        //         L1   (id 4)
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),                              // G
                2L to node(2L, mother = 1L),                 // P1a
                3L to node(3L, mother = 1L),                 // P1b
                4L to node(4L, mother = 2L, father = 3L),    // L1
                5L to node(5L, mother = 1L),                 // L2
            ),
            living = setOf(4L, 5L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val steiner = cache.steinerVisibleFor(lineage, layout)
        val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_STEINER)
        assertEquals(stateless, steiner, "cache vs stateless Steiner")
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), steiner, "both parents stay visible in the DAG")
    }

    @Test
    fun steiner_trunk_flips_visibility_as_livings_diverge() {
        // Initial: single living L1 under deep chain A → B → C → L1. Steiner = {L1}.
        // Add L2 born under B (a sibling of C above L1). Now LUCA shifts to B,
        // and C must flip to visible (it's on the path between L2's branch and L1).
        // A stays excluded (still trunk above LUCA = B).
        val initial = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),                       // A
                2L to node(2L, mother = 1L),          // B (will become LUCA)
                3L to node(3L, mother = 2L),          // C
                4L to node(4L, mother = 3L),          // L1
            ),
            living = setOf(4L),
        )
        val cache = LivingAncestryCache()
        val first = cache.steinerVisibleFor(initial, CladogramLayout.build(initial))
        assertEquals(setOf(4L), first, "T=1 → just the living")

        // Add L2 under B.
        val grown = lineageOf(
            nodes = initial.nodes + (5L to node(5L, mother = 2L)),
            living = setOf(4L, 5L),
        )
        val second = cache.steinerVisibleFor(grown, CladogramLayout.build(grown))
        val stateless = computeVisibleLineageIds(grown, CladogramLayout.build(grown), CladogramFilterMode.LIVING_STEINER)
        assertEquals(stateless, second, "cache vs stateless after LUCA shift")
        assertEquals(setOf(2L, 3L, 4L, 5L), second, "LUCA = B, A excluded as trunk above")
    }

    @Test
    fun steiner_excludes_trunk_above_lca_in_dag_with_multi_child_founders() {
        // Reproduces the user-reported bug: founders A and B each have two children
        // (C1/C2 from A, D1/D2 from B). All four cross-breed into a single LUCA at L,
        // from which both livings descend. Under a naive "branchCount >= 2" predicate,
        // A and B would stay visible because each has two living-bearing children —
        // but they're trunk above LUCA in the DAG and should be excluded.
        //
        //   A           B          (founders)
        //   ├─ C1       ├─ D1
        //   └─ C2       └─ D2
        //       \         /
        //        \   ┌───┘
        //         \  │
        //          L              (LUCA: mother=C1, father=D1, the only converging child)
        //          ├─ L1          (living)
        //          └─ L2          (living)
        //
        // Both C1 and D1 should be visible (descendants of LUCA via parent edges — no
        // wait, LUCA is L, and C1 and D1 are L's parents, so they're ANCESTORS of LUCA,
        // not descendants). Under strict Steiner, only LUCA and below are visible.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),                           // A
                2L to node(2L),                           // B
                3L to node(3L, mother = 1L),              // C1
                4L to node(4L, mother = 1L),              // C2 (dead-end)
                5L to node(5L, father = 2L),              // D1
                6L to node(6L, father = 2L),              // D2 (dead-end)
                7L to node(7L, mother = 3L, father = 5L), // L (LUCA)
                8L to node(8L, mother = 7L),              // L1
                9L to node(9L, mother = 7L),              // L2
            ),
            living = setOf(8L, 9L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val steiner = cache.steinerVisibleFor(lineage, layout)
        val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_STEINER)
        assertEquals(stateless, steiner, "cache vs stateless")
        // LUCA = 7 (L). Below: L1, L2. Nothing above L should be visible.
        assertEquals(setOf(7L, 8L, 9L), steiner, "only LUCA and below")
        assertEquals(false, 1L in steiner, "founder A excluded as trunk above LUCA")
        assertEquals(false, 2L in steiner, "founder B excluded as trunk above LUCA")
        assertEquals(false, 3L in steiner, "C1 is parent of LUCA, above LUCA, excluded")
        assertEquals(false, 5L in steiner, "D1 is parent of LUCA, above LUCA, excluded")
    }

    // ── LIVING_FOCUSED mode ─────────────────────────────────────────────────────

    @Test
    fun focused_picks_smaller_luca_subgraph_when_two_cofounders_exist() {
        // Two unrelated founders A and B both cross-bred into all current livings,
        // so both are LUCAs. A's descendant subgraph is larger (A has an extra
        // dead-end chain through X) than B's. LIVING_FOCUSED should pick B and
        // show only B's descendants.
        //
        //   A           B
        //   ├─ X        ├─ L1
        //   │  └─ L1    └─ L2
        //   └─ L2
        //
        // A reaches L1 via X; B reaches L1 directly. Both reach L2 directly.
        // A's descendant set: {A, X, L1, L2} (size 4).
        // B's descendant set: {B, L1, L2}    (size 3).
        // Focused picks B.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),                              // A
                2L to node(2L),                              // B
                3L to node(3L, mother = 1L),                 // X (A's intermediate)
                4L to node(4L, mother = 3L, father = 2L),    // L1
                5L to node(5L, mother = 1L, father = 2L),    // L2
            ),
            living = setOf(4L, 5L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val focused = cache.lucaFocusedVisibleFor(lineage, layout)
        val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_FOCUSED)
        assertEquals(stateless, focused, "cache vs stateless")
        assertEquals(setOf(2L, 4L, 5L), focused, "picks B's subgraph (smaller than A's)")
    }

    @Test
    fun focused_matches_steiner_when_single_luca() {
        // A → B → C → {D → L1, E → L2}. Single LUCA = C. Focused == Steiner.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 2L),
                4L to node(4L, mother = 3L),
                5L to node(5L, mother = 3L),
                6L to node(6L, mother = 4L),
                7L to node(7L, mother = 5L),
            ),
            living = setOf(6L, 7L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val focused = cache.lucaFocusedVisibleFor(lineage, layout)
        val steiner = cache.steinerVisibleFor(lineage, layout)
        assertEquals(steiner, focused, "single-LUCA case: focused identical to steiner")
    }

    @Test
    fun focused_tie_broken_by_smallest_id() {
        // Two co-LUCAs with identical descendant sets. Deterministic tie-break
        // picks the one with smaller id.
        val lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L),
                3L to node(3L, mother = 1L, father = 2L),
                4L to node(4L, mother = 1L, father = 2L),
            ),
            living = setOf(3L, 4L),
        )
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        val focused = cache.lucaFocusedVisibleFor(lineage, layout)
        assertEquals(setOf(1L, 3L, 4L), focused, "tie: pick smallest id (1L over 2L)")
    }

    @Test
    fun focused_matches_stateless_through_a_long_birth_death_sequence() {
        var lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val cache = LivingAncestryCache()
        cache.lucaFocusedVisibleFor(lineage, CladogramLayout.build(lineage))

        var nextId = 4L
        val rng = kotlin.random.Random(11)
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
            val layout = CladogramLayout.build(lineage)
            val cached = cache.lucaFocusedVisibleFor(lineage, layout)
            val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_FOCUSED)
            assertEquals(stateless, cached, "step $step focused diverged: cache=$cached, stateless=$stateless")
        }
    }

    @Test
    fun steiner_matches_stateless_through_a_long_birth_death_sequence() {
        // Same random fuzz as the ancestry case, but on the Steiner predicate to
        // catch any branchCount / nodesByLDC bookkeeping drift.
        var lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val cache = LivingAncestryCache()
        cache.steinerVisibleFor(lineage, CladogramLayout.build(lineage))

        var nextId = 4L
        val rng = kotlin.random.Random(7)
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
            val layout = CladogramLayout.build(lineage)
            val cached = cache.steinerVisibleFor(lineage, layout)
            val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_STEINER)
            assertEquals(stateless, cached, "step $step Steiner diverged: cache=$cached, stateless=$stateless")
        }
    }

    // ── ALL / LIVING_ONLY modes ─────────────────────────────────────────────────

    @Test
    fun all_visible_matches_stateless_through_a_long_birth_death_sequence() {
        var lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val cache = LivingAncestryCache()
        cache.allVisibleFor(lineage, CladogramLayout.build(lineage))

        var nextId = 4L
        val rng = kotlin.random.Random(13)
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
            val layout = CladogramLayout.build(lineage)
            val cached = cache.allVisibleFor(lineage, layout)
            val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.ALL)
            assertEquals(stateless, cached, "step $step ALL diverged: cache=$cached, stateless=$stateless")
        }
    }

    @Test
    fun living_only_matches_stateless_through_a_long_birth_death_sequence() {
        var lineage = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val cache = LivingAncestryCache()
        cache.livingOnlyVisibleFor(lineage, CladogramLayout.build(lineage))

        var nextId = 4L
        val rng = kotlin.random.Random(17)
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
            val layout = CladogramLayout.build(lineage)
            val cached = cache.livingOnlyVisibleFor(lineage, layout)
            val stateless = computeVisibleLineageIds(lineage, layout, CladogramFilterMode.LIVING_ONLY)
            assertEquals(stateless, cached, "step $step LIVING_ONLY diverged: cache=$cached, stateless=$stateless")
        }
    }

    @Test
    fun all_visible_resets_on_snapshot_load() {
        // Same snapshot-load semantics as the ancestry case: a smaller-lineage
        // restore must trigger a reset rather than serve the pre-restore allMembers.
        val full = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
                4L to node(4L, mother = 2L),
                5L to node(5L, mother = 3L),
            ),
            living = setOf(4L, 5L),
        )
        val cache = LivingAncestryCache()
        cache.allVisibleFor(full, CladogramLayout.build(full))

        val earlier = lineageOf(
            nodes = linkedMapOf(
                1L to node(1L),
                2L to node(2L, mother = 1L),
                3L to node(3L, mother = 1L),
            ),
            living = setOf(2L, 3L),
        )
        val cached = cache.allVisibleFor(earlier, CladogramLayout.build(earlier))
        assertEquals(setOf(1L, 2L, 3L), cached)
    }

    @Test
    fun ancestry_cache_no_change_calls_do_not_scale_with_total_nodes() {
        // Build a 100k-node lineage (star: one root, the rest are leaves of that root)
        // with a single living leaf. After the initial warmup populates the cache,
        // 100 follow-up calls with unchanged lineage state must not rescan the full
        // node map — the discovery loop iterates the empty `[nextLineageId, next)`
        // range and returns immediately.
        //
        // Pre-fix, `ensureCurrent` iterated every entry of `lineage.nodes` on every
        // call, so this loop scaled with total ever-born nodes. The threshold here
        // is generous to absorb cross-platform noise (JS, CI) — the regression case
        // is hundreds of ms; the fixed case is sub-ms.
        val n = 100_000
        val nodes = LinkedHashMap<Long, DrocketLineageNode>(n + 1)
        nodes[1L] = node(1L)
        for (i in 2..n) {
            nodes[i.toLong()] = node(i.toLong(), mother = 1L)
        }
        val lineage = lineageOf(nodes = nodes, living = setOf(n.toLong()))
        val layout = CladogramLayout.build(lineage)
        val cache = LivingAncestryCache()
        cache.ancestryVisibleFor(lineage, layout) // warmup populates parents/children

        val start = TimeSource.Monotonic.markNow()
        repeat(100) { cache.ancestryVisibleFor(lineage, layout) }
        val elapsedMs = start.elapsedNow().inWholeMilliseconds

        assertTrue(
            elapsedMs < 100,
            "100 no-op cache calls over n=$n took ${elapsedMs}ms — likely scaling with total nodes",
        )
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

    /** Build a lineage state with [nextLineageId] consistent with the node map —
     *  the production cache relies on `nextLineageId == max(id) + 1` as a
     *  monotone watermark, so tests must maintain that invariant too. */
    private fun lineageOf(
        nodes: Map<Long, DrocketLineageNode>,
        living: Set<Long>,
    ) = DrocketLineageState(
        nextLineageId = (nodes.keys.maxOrNull() ?: 0L) + 1L,
        nodes = nodes,
        livingLineageIds = living,
    )
}
