package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoUnits
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Operand
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **The replacement for the AoS-oracle gate.** Instead of re-deriving correctness from a second
 * (array-of-structs) implementation tick-for-tick, this freezes the *current, known-good* behaviour of
 * the live [CytoSoaReducer] as committed golden digests and gates future optimisations against it: the
 * "pre-optimised source of truth". The sim is fixed-point deterministic, so a fixed seed + scripted
 * input yields a bit-stable trajectory across runs/machines, and a digest of the resulting state is a
 * stable regression key.
 *
 * Why this is *more* coverage than the old AoS gate, not less: the AoS equivalence gate was `@Ignore`d
 * under mutation (a never-resolved AoS↔SoA divergence), so it never covered the **mutation-on** config
 * the game actually ships. The golden does — [mutationOn] locks the live evolving trajectory down.
 *
 * The digest is split into dimensions (meta / physics / biology / topology / grid) so a failure says
 * *what* drifted. To intentionally re-baseline after a deliberate behaviour change, run the test, copy
 * the `actual` hashes from the assertion messages into the constants below, and justify it in the commit.
 *
 * Determinism of the *parallel* path and faithfulness of the toSimState boundary are gated separately
 * ([parallelMatchesSequential], [grownStateRoundTrips]) — no AoS implementation involved anywhere here.
 */
class CytoGoldenTest {

    // ── Golden digests: { dimension -> FNV-1a hex } per scenario. Captured from the live SoA reducer. ──
    // Each re-baseline reflects a deliberate change to game rules, tuning, or gene model.
    // Full history is in git log (see cleanup initiative docs for detail).
    // GROWTH captured at the current MATTER_UNIFORM_LEVEL_CELL_SCALE seed density.
    // Re-baselined 2026-07-09: `cyto: 4x world size` (2ede0271) enlarged the torus (CytoMatterField/
    // CytoUnits) without recapturing goldens — the world geometry shifts toroidal positions, grid indices,
    // and (over 1500 ticks) the colony population. All five scenario goldens move on world extent alone;
    // parallelMatchesSequential + grownStateRoundTrips held throughout, so this is a pure world-size
    // re-baseline (no rule/tuning change). Trajectory goldens shift meta/physics/grid; weld goldens shift
    // physics/topology.
    // Re-baselined 2026-07-09 (#2): LIGHT_FALLOFF now scales with the world (CELLS_PER_AXIS/4 = 16, was a
    // fixed 8) so the moving daylight band keeps the same RELATIVE width + onset timing under the 4x world —
    // without this a center-seeded autotroph starves (the band's fixed 8-unit half-width covered only half
    // the relative slice of the doubled torus). growth + interact shift (light-driven trajectories); the
    // no/low-light mutation/weld/sticky goldens are unaffected.
    // Re-baselined 2026-07-14: round-robin gene evaluation removed — every non-division gene now re-checks
    // its condition every tick (was 1 gene/tick, rest on a stale cache). Genes respond to their gates
    // immediately, so light-driven growth follows a slightly different (more responsive) trajectory. Only
    // the light-driven goldens (growth + interact) shift; mutation/weld/sticky and parallel==sequential are
    // unaffected. topology unchanged (structure identical).
    // Re-baselined 2026-07-15: MATTER_COLLAPSE_DELAY 256 -> 2048 (20470a2b), a deliberate VISUAL change —
    // leaves are held ~8x longer before merging, so the matter field keeps its definition instead of pooling
    // away. The delay doubles per layer above the finest, so within a 1500-tick run no merge fires at all
    // now: the tree simply stays refined. physics/biology/grid shift because a differently-refined tree
    // distributes matter slightly differently through the passiveEnvExchange junction, which propagates to
    // cell chemistry and thus position; meta/topology are byte-identical. The ECOLOGY is unchanged — the
    // founder's population curve is identical at both delays (1 -> 41 by tick 1500, first division ~tick
    // 1000) — so this is a pure grid-structure re-baseline, not a rule/tuning change. The mutation/weld/
    // sticky goldens are unaffected (no merge fires within their runs either); parallelMatchesSequential +
    // grownStateRoundTrips held throughout.
    // Re-baselined 2026-07-15 (#2): the matter field became a dense per-species grid (was a quad-tree). ALL
    // five goldens shift, but only in microstate: meta is byte-identical on growth/mutation and topology on
    // every one, and the population curve is IDENTICAL to the tree's for the first 1500 ticks (the goldens'
    // whole horizon), drifting to only -2.8% by tick 6000 with the same lag, take-off and shape. The cause
    // is ITERATION ORDER, not a rule change: the tree walked a footprint in DFS quadrant (Z) order, the
    // dense grid walks it row-major, and two ops are order-sensitive — deposit hands its ±1 remainder to the
    // first N texels, and an Export clamps against the live cytoplasm so later texels see a depleted
    // reserve. Both are arbitrary either way; row-major is simply the dense field's natural (and
    // cache-friendly) order. "grid" additionally shifts because the digest now enumerates texels, not
    // leaves. parallelMatchesSequential + grownStateRoundTrips + conservation held throughout.
    // Re-baselined 2026-07-16: the matter field DIFFUSES again (PLAN_diffusion.md §2b). Unlike the dense
    // port above, this IS a deliberate rule change, not a re-ordering — matter now moves without a cell to
    // move it, so real divergence is expected and wanted. An integer edge-flux pass runs on the existing
    // MATTER_MAINTAIN_PERIOD cadence: every texel edge moves ⌊Δ/8⌋, or one unit where that rounds to zero
    // but a gradient remains. Purpose is ecological recovery — a depleted crater stays a legible scar on a
    // biological timescale and returns to ~69% of seed on a geological one (it never fully equalises; a
    // slope-1 staircase survives, which is what lets the pass terminate). Only the three light-driven
    // trajectory goldens move; the weld/sticky goldens are unaffected (their runs are too short for a
    // maintenance pass to matter). topology is byte-identical on all three — diffusion moves matter, not
    // connectivity — and meta is byte-identical on mutation + interact.
    // Re-baselined 2026-07-16 (#2): the diffusion SCHEDULE became one species per pass (was: all 3 monomers
    // plus every present species of one chain length). Even passes take a monomer, round-robin b→g→r; odd
    // passes take a polymer, its length picked by the ruler sequence and its identity round-robined within
    // that length. Same flux law, so this is purely a re-rating of who moves when — but it is a big one:
    // each monomer now diffuses every 6th pass rather than every pass, so recovery stretches ~6x deeper into
    // geological time, and cost fell from 0.64% of tick to ~0.10% with the 21ms spike gone (a sweep costs
    // the same whether or not matter moves, so cost tracks columns-swept; capping that at one is the whole
    // trick). Only the three light-driven trajectory goldens move; topology is byte-identical on all three
    // and meta on mutation + interact.
    // Re-baselined 2026-07-16 (#3): MATTER_DIFFUSE_DEN 8 -> 64, a deliberate VISUAL change. Diffusion is a
    // discrete event every 128 ticks, and at DEN=8 a fresh scar's rim texel jumped 28 units in one instant —
    // 22% of the 125 seed level, which reads as a pop rather than as weather. DEN=64 is the integer floor
    // (every edge moves the minimum 1 unit at seed-scale gradients, ≤4 per texel across the H+V sweeps, ~3%);
    // 128 and 256 measure identically, so there is nothing past 64. This barely touches WHERE diffusion
    // gets — a 21-wide crater settles at 84 vs 86 — it only removes the conspicuous opening transient, which
    // is precisely the thing being paid for. Large piles (a death dump, Δ in the thousands) still disperse
    // proportionally via the quotient term, which is wanted. Cost is unchanged (the sweep runs regardless).
    // ECOLOGY VERIFIED before re-baselining: the founder's population curve keeps its shape, lag and
    // take-off (1→40 by tick 1500 vs 41 with diffusion off; 3037 vs 3194 by tick 6000, -4.9%).
    // checkCytoConservation is exact on all 3 elements over 6000 ticks (the flux is edge-symmetric, so
    // conservation is exact by construction), and parallelMatchesSequential held with NO special handling —
    // integer += into the flux accumulator is order-independent, so any edge visitation order is identical.
    private val GROWTH = mapOf(
        "meta" to "6690f1e62190d995",
        "physics" to "519b154d179ac49d",
        "biology" to "f5701ce4adf00b07",
        "topology" to "cbf29ce484222325",
        "grid" to "3a7e3ea4bad8abfc",
    )
    // Re-baselined 2026-07-05: CYTOPLASM_DIFFUSE_PERIOD=2 — cytoplasm diffusion runs every 2nd tick,
    // halving the diffuse cost. Changes inter-cell nutrient sharing dynamics.
    // Re-baselined 2026-07-04: EXCHANGE_BATCHES=4 — staggered cell↔environment exchange so each tick only
    // 1/N of cells exchange, dividing exchange cost by N. Changes nutrient timing → different trajectories.
    // mutation on (rateDenom 200), 250 ticks — the live evolving config the AoS gate never covered.
    // Re-baselined 2026-06-29: SIZE-DEPENDENT DIFFUSION — CytoMatterField.balance() now uses a
    // size-aware denominator (2 × (1 + (atomCount-1) × scale)) for pairwise cell↔leaf averaging.
    // With scale=0 (default), the formula preserves backward-compatible damping (denom=2) but has
    // slightly different truncation than the original pair-wise half() approach, shifting all
    // trajectories that exercise the passiveEnvExchange junction. determinism + conservation held.
    // Re-baselined twice for deliberate gene-model extensions, both of which re-route point-mutation's
    // PRNG-driven choices (and thus every downstream dimension): (1) the Expand/Contract flex actions
    // grew ActionType 5→7, remapping `ActionType.entries[nextInt(size)]`; (2) the Touching gate condition
    // changed condition-type mutation from a deterministic ChemQty↔Biomass flip to an entries draw
    // (`ConditionType.entries[nextInt(size)]`), so it now consumes a PRNG int and can reach the new type.
    // The mutation-off GROWTH/INTERACT goldens below are unchanged (no flex/touch gene in the default
    // scene, no mutation), confirming the drift is isolated to the new mechanics.
    // Re-baselined 2026-06-15: active-uptake GRADIENT COST — Import yield falls as the cell concentrates a
    // species above the ambient reservoir (CytoBiologyCore + IMPORT_GRADIENT_SCALE). Only the mutation-on
    // golden moves: the presets have no Import gene, so growth/interact are byte-identical (confirming the
    // change is isolated to active uptake, which only arises under mutation).
    // Re-baselined 2026-06-16: condition operands generalised — a GeneCondition is now `lhs cmp rhs` with
    // an Operand (Constant / Chem / Biomass / Touching) on BOTH sides. The GATE is behaviour-identical for
    // the presets (lhs=variable, rhs=constant evaluates exactly as before), proven by the deterministic
    // GROWTH/INTERACT scenarios moving ONLY in the `biology` dimension (which hashes GeneCodec.serialize,
    // whose text format changed from `ChemQty ab > 0` to `ab > 0`) — meta/physics/topology/grid stay
    // byte-identical. mutation-on moves in every dimension because point-mutation now re-rolls a whole
    // operand (`mutateOperand`, nextInt(4) + maybe a species draw) instead of the old separate species/type
    // draws, re-routing the PRNG stream and thus the whole trajectory.
    // Re-baselined 2026-06-16 (#2): BREAK-POWERED DIVISION — mitosis is now a bulk `biomass/4` cost (paid by
    // breaking stored bonds; light can't fund it) and the seed AUTOTROPH was rebuilt around it (BreakBond
    // mitosis from an `ab` reserve held to N; FormBond/Convert grow below N; no Repair gene — colonies hold
    // together only by overlap-welding, not active heal). The mitosis rule + preset genome both changed, so
    // ALL three trajectory goldens move in every dimension; determinism gates held. Validated: the founder
    // colonises hard (1→955 by tick 600 under committed dials, mutation off) — dense enough that cells
    // overlap-weld, so the mutation/interact `topology` is non-empty again. At the live
    // MUTATION_RATE_DENOM=100_000 the first division lands long before any mutation, so the lineage is
    // robust in the real world (the mutation-on goldens/specs crank the rate to 200 to exercise divergence).
    // Re-baselined 2026-06-16 (#3): SUB-TICK INTERPOLATION + biomass/4 cost. A continuous growth gene now
    // runs only for the portion of the tick before it crosses its OWN gate threshold (CytoBiologyCore
    // .selfGateCap), so it fills to its limit instead of overshooting in one bulk step; Mitosis moved to an
    // atomic END-OF-TICK pass whose gate is re-checked on the settled state. The autotroph's grow/divide
    // thresholds split (GROW 8000 > DIVIDE 6000) so the now-capped grower still crosses the divide line.
    // All three goldens move again (gene execution order + per-tick magnitudes changed); parallel==sequential
    // and matter-conservation gates held. The autotroph still colonises reliably (1→276 by tick 600 probed
    // under moving light, ~identical at the live mutation rate). This un-parks
    // heterotrophLivesOffStoredMatter (its break-powered Convert no longer overshoots, so it keeps a reserve
    // to divide).
    // Re-baselined 2026-06-16 (#4): the fresh-world default is now MOVING LIGHT (CytoTuning.LIGHT_MOVING=true,
    // committed) with the founder seeded at the world ORIGIN (0,0). The daylight band only lights the cell
    // as it sweeps past (LIGHT_ORBIT_PERIOD), so growth is orbit-paced: the first division slips to ~tick 988
    // (was ~200 under static sources). Golden + colonisation-spec tick budgets bumped past that (goldens
    // 250→1500, interact grow 80→1200) so they still capture a real colony; determinism + conservation held.
    // Re-baselined 2026-06-17: Conc operand + AND-conjunction gate. The gate is now a list of clauses (the
    // single-clause convenience ctor keeps presets identical), and point-mutation grew from nextInt(8)→(10)
    // (clause add/drop ops), drift/point now draw a clause index, and mutateOperand grew nextInt(4)→(5) (the
    // Conc kind) — all re-routing the mutation-on PRNG stream. ONLY the mutation-on golden moves; growth +
    // interact are byte-identical (presets have no Conc/multi-clause gene, and a single-clause condition
    // evaluates exactly as before), confirming the change is additive.
    // Re-baselined 2026-06-18: FormBond exact-species default + opt-in `*` wildcard (MORPHOGENESIS.md
    // §2026-06-18). FormBond genes now resolve their operands EXACTLY by default (richest suffix/prefix
    // match only when flagged wildcard), and point-mutation gained a wild-flag toggle (nextInt(10)→(11)),
    // re-routing the PRNG stream. ONLY the mutation-on golden moves: growth + interact are byte-identical
    // (the autotroph holds only {a,b,ab}, so `a` is the only a-ender and `b` the only b-starter ⇒ exact ≡
    // wildcard for the presets). meta + topology are unchanged (same cell count + spring topology); only
    // physics/biology/grid shift. Determinism gates (parallel==sequential, round-trip) held.
    // Re-baselined 2026-07-07 (ABC→RGB rename; see the GROWTH note). meta + topology are byte-identical here
    // (same seed/tick/cell-count, no springs sampled); physics/biology/grid shift with the tie-break re-order.
    // Values captured after the processLyseAttacks compaction fix (f717fc2f), which can also perturb the
    // mutation-on trajectory once a mutated Lyse gene fully drains a victim species (growth/interact have no
    // Lyse gene, so their drift is the rename alone). Determinism gates held.
    // Re-baselined 2026-07-09: 4x world size (see GROWTH). meta + biology byte-identical here (same
    // population + chemistry); physics + grid shift with the larger torus.
    // Re-baselined 2026-07-09 (#3): PER-CELL mutation RNG (splitmix64 keyed on world-seed+entityId+tick)
    // replaced the single shared LCG stream, so the write-back loop parallelises (order-independent draws).
    // A different-but-equivalent PRNG stream ⇒ the whole mutation-on trajectory moves; growth/interact
    // (rateDenom 0, never draw) are untouched. parallelMatchesSequential + all invariants held.
    // Re-baselined 2026-07-16: matter diffusion returns (see GROWTH). meta + topology are byte-identical
    // here — same population and spring structure — so the drift is pure microstate: physics/biology/grid
    // shift as the relaxing field feeds the junction slightly differently.
    private val MUTATION = mapOf(
        "meta" to "187ed166f49aa1ee",
        "physics" to "e9629ee5366336d2",
        "biology" to "cdf3a8bb4e7874ab",
        "topology" to "cbf29ce484222325",
        "grid" to "69c8b06bea4e72ad",
    )
    // grow then a scripted player-interaction sequence (delete / spawn / set / detach / grab).
    // Re-baselined 2026-07-05: restored LIGHT_QUANTA_SCALE 60k→120k (matter viability) +
    // DEGRADE_PERIOD 4000→18000 (cytoplasm degradation) + MATTER_UNIFORM_LEVEL_CELL_SCALE 2k→3k.
    // Re-baselined 2026-07-07 (ABC→RGB rename; see the GROWTH note). All dimensions shift; topology went empty.
    // Re-baselined 2026-07-08: DROP-CONTESTED passive exchange (parallelizable). A quad-tree leaf touched by
    // ≥2 cells in the same exchange batch is now DROPPED (skipped this tick) so single-owner leaves can be
    // balanced in parallel order-independently; the per-cell target divisor also becomes the uncontested-leaf
    // count. Only INTERACT moves — its scripted colony packs cells densely enough for footprints to overlap;
    // GROWTH + MUTATION are byte-identical (their colony density has zero contested leaves, so the junction is
    // unchanged there), confirming the drift is isolated to the overlapping-footprint case. The contested set
    // is geometry-only ⇒ thread-count-independent, so parallelMatchesSequential + conservation held.
    // Re-baselined 2026-07-09: 4x world size (see GROWTH). meta/physics/biology/grid all shift.
    // Re-baselined 2026-07-09 (#2): LIGHT_FALLOFF scales with the world (see GROWTH #2).
    // Re-baselined 2026-07-15: the tap hit test (CytoInteractionSystem.contains) became torus-aware — it
    // compared flat logical deltas, so a tap never matched a cell across the seam and fell through to the
    // "miss ⇒ insert a cell" branch. This script's (500, 500) taps are ~4 world-spans out and wrap to
    // logical (-12, -12): the old baseline captured the bug — Base spawned a cell there, then Set MISSED
    // that very cell and stacked a second one on top (pop 9). Now Set re-types it (pop 8), so meta moves by
    // that one cell and physics/biology/grid follow; topology unchanged.
    // Re-baselined 2026-07-14: round-robin gene evaluation removed (see GROWTH). physics/biology/grid shift
    // with the more-responsive gene trajectories; meta + topology unchanged.
    // Re-baselined 2026-07-16: matter diffusion returns (see GROWTH). meta + topology byte-identical (the
    // scripted population is unchanged); physics/biology/grid shift with the relaxing field.
    private val INTERACT = mapOf(
        "meta" to "2f287e9ca4c65e66",
        "physics" to "77ffe2337d72fa66",
        "biology" to "edb36956a9d67d7",
        "topology" to "cbf29ce484222325",
        "grid" to "1529d2d348df896d",
    )

    @Test
    fun growthMutationOff() {
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(1500) { w = soa.tick(w, CytoInput.EMPTY) }   // past the founder's first division (~tick 988 under moving light)
        val state = w.toSimState()
        val sd = w.getSpringData()
        assertGolden("growth", GROWTH, state, sd)
    }

    @Test
    fun mutationOn() {
        val cfg = CytoConfig(mutationRateDenom = 200)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(1500) { w = soa.tick(w, CytoInput.EMPTY) }
        val state = w.toSimState()
        val sd = w.getSpringData()
        assertGolden("mutation", MUTATION, state, sd)
    }

    @Test
    fun scriptedInteractions() {
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(1200) { w = soa.tick(w, CytoInput.EMPTY) }   // grow a connected colony (slower under moving light)
        // A fixed input script (positions in logical units), one tap per tick, deterministic.
        val collector = CellType.Collector
        w = soa.tick(w, CytoInput(taps = listOf(CytoInput.Tap(0f, 0f, TouchMode.Delete, collector))))
        w = soa.tick(w, CytoInput(taps = listOf(CytoInput.Tap(500f, 500f, TouchMode.Base, collector))))
        w = soa.tick(w, CytoInput(taps = listOf(CytoInput.Tap(500f, 500f, TouchMode.Set, CellType.Blank))))
        repeat(20) { w = soa.tick(w, CytoInput.EMPTY) }
        val state = w.toSimState()
        val sd = w.getSpringData()
        assertGolden("interact", INTERACT, state, sd)
    }

    // Repair gene: burn the stored `rg` reserve for repair energy (light-independent). A touching pair
    // both repairing the same tick welds via gene-driven adhesion (the weld-heal path).
    private val repairOnly = listOf(
        Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),
    )

    // Weld-heal + sticky-weld goldens: the default GROWTH/INTERACT/MUTATION scenarios use the non-sticky,
    // no-Repair seed autotroph, so they NEVER weld — leaving the lifecycle weld / weld-heal paths ungated.
    // These two lock the current (round-trip) welding trajectory so the SoA-native weld port is bit-identical.
    // Re-baselined 2026-07-09: 4x world size (see GROWTH). physics + topology (weld positions) shift;
    // meta/biology/grid byte-identical (tiny fixed colony, same chemistry, no grid deposit in-frame).
    // Re-baselined 2026-07-11: CONNECTION_BREAK_DAMAGE halved 5→2.5 (welds were too durable — a dragged
    // welded body never tore, leaving cohesion/Repair with no visible stakes for the campaign). Only the two
    // weld goldens move, and only in their weld-dependent dimensions (weldHeal: physics/biology/topology;
    // sticky: topology — fewer welds survive under the lower break threshold); every trajectory + determinism
    // gate is byte-identical (the sandbox autotroph severs, so its colonisation doesn't depend on welds).
    private val WELD_HEAL = mapOf(
        "meta" to "bb3fa685a6d77664",
        "physics" to "b47e91853a3c8a02",
        "biology" to "999427a698b3b417",
        "topology" to "9bfc123c376c0371",
        "grid" to "7019020475aa5760",
    )
    private val STICKY_WELD = mapOf(
        "meta" to "350eaa4577a67db5",
        "physics" to "f7edf29a779a21ca",
        "biology" to "ca5045ab30a68078",
        "topology" to "8f9ae79f7f29791a",
        "grid" to "571fec754e148c01",
    )

    @Test
    fun weldHealColony() {
        // Three deeply-overlapping Repair-active cells: as they co-repair they weld-heal into a cluster
        // (weld-heal + division as reserves split), exercising the weld-heal lifecycle path.
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 1))
            val r = Frac(1, 2)
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
            b.spawnCell(CytoUnits.coord2(0f, 0.15f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, genome = repairOnly)
            b.build()
        }
        var w = CytoWorld.fromSimState(initial)
        repeat(40) { w = soa.tick(w, CytoInput.EMPTY) }
        val state = w.toSimState()
        val sd = w.getSpringData()
        assertTrue(state.components.getTable<SpringConstraintComponent>().asMap().values.any { it.springs.isNotEmpty() },
            "weld-heal scenario produced no springs — it must weld to gate the weld-heal path")
        assertGolden("weldHeal", WELD_HEAL, state, sd)
    }

    @Test
    fun stickyWeldPair() {
        // Two overlapping sticky cells (no Repair): the contact system welds them via the plain-weld
        // path (sticky ⇒ weldLo), which the default goldens never trigger (AUTO_WELD_ON_OVERLAP is off).
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 1))
            val r = Frac(1, 2)
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, sticky = true, genome = emptyList())
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000), logicalRadius = r, sticky = true, genome = emptyList())
            b.build()
        }
        var w = CytoWorld.fromSimState(initial)
        repeat(10) { w = soa.tick(w, CytoInput.EMPTY) }
        val state = w.toSimState()
        val sd = w.getSpringData()
        assertTrue(state.components.getTable<SpringConstraintComponent>().asMap().values.any { it.springs.isNotEmpty() },
            "sticky pair produced no springs — it must weld to gate the plain-weld path")
        assertGolden("stickyWeld", STICKY_WELD, state, sd)
    }

    // ── SoA-only determinism + boundary gates (no AoS) ─────────────────────────────────────────────

    @Test
    fun parallelMatchesSequential() {
        val cfg = CytoConfig(mutationRateDenom = 0)
        val executor = org.emerge.sim.core.ecs.ParallelExecutor()
        val seq = CytoSoaReducer(cfg)
        val par = CytoSoaReducer(cfg, executor = executor, springParallelThreshold = 2, bioParallelThreshold = 2)
        var ws = CytoWorld.fromSimState(createCytoInitialState())
        var wp = CytoWorld.fromSimState(createCytoInitialState())
        for (t in 1..250) {
            ws = seq.tick(ws, CytoInput.EMPTY)
            wp = par.tick(wp, CytoInput.EMPTY)
            val ss = ws.toSimState()
            val sp = wp.toSimState()
            assertEquals(digest(ss, ws.getSpringData()), digest(sp, wp.getSpringData()), "parallel != sequential at tick=$t")
        }
        executor.close()
    }

    @Test
    fun parallelMatchesSequentialWeldedColony() {
        // The default parallelMatchesSequential grows the non-welding autotroph, so it never exercises the
        // parallel weld-physics phases (ConnectionsSystem stress/damage/break, DragSystem, the spring solve).
        // Build a packed grid of sticky, overlapping, MOVING cells: they weld into a body, so springs form
        // and the connections/drag/spring-solve phases all do real work — then assert SEQ == PAR every tick.
        val cfg = CytoConfig(mutationRateDenom = 0)
        val executor = org.emerge.sim.core.ecs.ParallelExecutor()
        val seq = CytoSoaReducer(cfg)
        val par = CytoSoaReducer(cfg, executor = executor, springParallelThreshold = 2, bioParallelThreshold = 2)
        val initial = run {
            val b = SimBuilder(SimState(randomSeed = 7))
            val r = Frac(1, 2)
            val spacing = 0.06f   // < 2·radius in normalised units ⇒ cells overlap ⇒ sticky weld
            for (gy in 0 until 5) for (gx in 0 until 5) {
                // A shear velocity field (varies across the grid) stretches/compresses the welds so the
                // stress/overstretch/break paths fire, not just the rest state.
                val vx = (gy - 2) * 0.02f
                b.spawnCell(
                    CytoUnits.coord2((gx - 2) * spacing, (gy - 2) * spacing), CytoUnits.coord2(vx, 0f),
                    CellType.Collector, cytoplasm = mapOf("rg" to 50000), biomass = mapOf("rg" to 4000),
                    logicalRadius = r, sticky = true, genome = emptyList())
            }
            b.build()
        }
        var ws = CytoWorld.fromSimState(initial)
        var wp = CytoWorld.fromSimState(initial)
        var sawSprings = false
        for (t in 1..60) {
            ws = seq.tick(ws, CytoInput.EMPTY)
            wp = par.tick(wp, CytoInput.EMPTY)
            val ss = ws.toSimState()
            assertEquals(digest(ss, ws.getSpringData()), digest(wp.toSimState(), wp.getSpringData()),
                "parallel != sequential (welded colony) at tick=$t")
            if (ss.components.getTable<SpringConstraintComponent>().asMap().values.any { it.springs.isNotEmpty() }) sawSprings = true
        }
        assertTrue(sawSprings, "welded-colony equivalence test never formed a spring — it isn't exercising the weld-physics phases")
        executor.close()
    }

    @Test
    fun grownStateRoundTrips() {
        // toSimState/fromSimState is load-bearing (render + save read it). Grow a colony, round-trip the
        // SoA world through a SimState, and assert the digest is unchanged.
        val cfg = CytoConfig(mutationRateDenom = 0)
        val soa = CytoSoaReducer(cfg)
        var w = CytoWorld.fromSimState(createCytoInitialState())
        repeat(250) { w = soa.tick(w, CytoInput.EMPTY) }
        val before = w.toSimState()
        val sd = w.getSpringData()
        val round = CytoWorld.fromSimState(before).toSimState()
        val roundW = CytoWorld.fromSimState(before)
        assertEquals(digest(before, sd), digest(round, roundW.getSpringData()), "round-trip changed the state digest")
    }

    // ── digest ──────────────────────────────────────────────────────────────────────────────────

    private fun assertGolden(label: String, golden: Map<String, String>, state: SimState, springData: org.emerge.demo.cyto.CytoFrameSpringData? = null) {
        val actual = digest(state, springData)
        if (actual != golden) {
            val dump = actual.entries.joinToString(",\n        ") { "\"${it.key}\" to \"${it.value}\"" }
            throw AssertionError("$label digest drift. Current digests (paste to re-baseline):\n        $dump")
        }
    }

    /** A canonical per-dimension FNV-1a digest of the persistent sim state (impulse excluded — transient,
     *  reset each tick). Entities are visited in ascending EntityId order and every map is key-sorted, so
     *  the string — and thus the hash — is stable across runs, JVMs, and component-table iteration order.
     *  Spring/damage data is read from CSR when available, falling back to SimState tables. */
    private fun digest(s: SimState, springData: org.emerge.demo.cyto.CytoFrameSpringData? = null): Map<String, String> {
        val cells = s.components.getTable<CytoCellComponent>().asMap()
        val transforms = s.components.getTable<TransformComponent>().asMap()
        val motions = s.components.getTable<MotionComponent>().asMap()
        val materials = s.components.getTable<MaterialComponent>().asMap()
        val colliders = s.components.getTable<ColliderComponent>().asMap()
        // Fall back to SimState tables if CSR data not provided (legacy tests)
        val springsTable = if (springData == null) s.components.getTable<SpringConstraintComponent>() else null
        val connsTable = if (springData == null) s.components.getTable<ConnectionStateComponent>() else null
        val springs: Map<EntityId, org.emerge.sim.core.physics.components.SpringConstraintComponent>? =
            if (springsTable != null) springsTable.asMap() else null
        val conns: Map<EntityId, ConnectionStateComponent>? =
            if (connsTable != null) connsTable.asMap() else null
        val ids = cells.keys.sortedBy { it.value }

        val meta = "seed=${s.randomSeed};tick=${s.tick};last=${s.world.lastEntityValue};n=${ids.size}"

        val physics = StringBuilder()
        for (id in ids) {
            val p = transforms[id]?.pos; val v = motions[id]?.vel
            val m = materials[id]?.mass ?: 0u; val r = colliders[id]?.radius?.raw ?: 0L
            physics.append(id.value).append(':')
                .append(p?.x?.raw).append(',').append(p?.y?.raw).append(',')
                .append(v?.x?.raw).append(',').append(v?.y?.raw).append(',')
                .append(m).append(',').append(r).append(';')
        }

        val biology = StringBuilder()
        for (id in ids) {
            val c = cells.getValue(id)
            biology.append(id.value).append(':').append(c.type.name).append('|')
                .append(c.logicalRadius.raw).append('|').append(c.wear).append('|')
                .append(GeneCodec.serialize(c.genome)).append('|')
                .append(mapStr(c.cytoplasm)).append('|').append(mapStr(c.biomass)).append(';')
        }

        val topology = StringBuilder()
        for (id in ids) {
            // Use CSR data if available, else fall back to SimState tables
            var spStr = ""
            var dmgStr = ""
            if (springData != null) {
                val slot = springData.slotOfEntityId(id.value)
                if (slot >= 0) {
                    val lo = springData.csrOffset[slot]
                    val hi = springData.csrOffset[slot + 1]
                    // Build sorted spring string from CSR
                    data class E(val otherId: Int, val rest: Long, val stiff: Long, val damp: Long, val aux: Float)
                    val edgeList = mutableListOf<E>()
                    for (k in lo until hi) {
                        edgeList.add(E(springData.csrOtherId[k], springData.csrRestRaw[k], springData.csrStiffRaw[k], springData.csrDampRaw[k], springData.csrEdgeAux[k]))
                    }
                    spStr = edgeList.sortedBy { it.otherId }.joinToString(",") { "${it.otherId}/${it.rest}/${it.stiff}/${it.damp}" }
                    dmgStr = edgeList.filter { it.aux != 0f }.sortedBy { it.otherId }.joinToString(",") { "${it.otherId}=${it.aux}" }
                }
            } else if (springs != null) {
                spStr = springs[id]?.springs.orEmpty()
                    .sortedBy { it.other.value }
                    .joinToString(",") { "${it.other.value}/${it.restLength.raw}/${it.stiffness.raw}/${it.damping.raw}" }
                dmgStr = conns?.get(id)?.damage?.orEmpty()?.filterValues { it != 0f }
                    ?.entries?.sortedBy { it.key.value }?.joinToString(",") { "${it.key.value}=${it.value}" } ?: ""
            }
            if (spStr.isNotEmpty() || dmgStr.isNotEmpty()) topology.append(id.value).append(":[").append(spStr).append("][").append(dmgStr).append(']').append(';')
        }

        val gridSb = StringBuilder()
        val grid = s.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid
        // Quad-tree field: hash each leaf by its region (corner + size) + contents (stable DFS order).
        grid?.forEachTexel { x, y, sz, store ->
            if (store.size > 0) {
                gridSb.append(x).append(',').append(y).append(',').append(sz).append(':')
                for (i in 0 until store.size) gridSb.append(store.idAt(i)).append('=').append(store.countAt(i)).append(',')
                gridSb.append(';')
            }
        }

        return mapOf(
            "meta" to fnv(meta),
            "physics" to fnv(physics.toString()),
            "biology" to fnv(biology.toString()),
            "topology" to fnv(topology.toString()),
            "grid" to fnv(gridSb.toString()),
        )
    }

    private fun mapStr(m: Map<String, Int>): String =
        m.entries.filter { it.value != 0 }.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun fnv(s: String): String {
        var h = -3750763034362895579L  // FNV-1a 64-bit offset basis (0xcbf29ce484222325)
        for (ch in s) { h = h xor ch.code.toLong(); h *= 1099511628211L }
        return h.toULong().toString(16)
    }
}
