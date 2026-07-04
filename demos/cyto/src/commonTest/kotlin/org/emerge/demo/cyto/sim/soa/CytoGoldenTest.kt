package org.emerge.demo.cyto.sim.soa

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.ConnectionStateComponent
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoInput
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.TouchMode
import org.emerge.demo.cyto.sim.createCytoInitialState
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertEquals

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
    // Re-baselined 2026-06-14: passive cell↔environment exchange (CytoBiologyCore.passiveEnvExchange) went
    // from a per-cell sequential draw — which let the lowest-EntityId cell skim a shared grid-cell first
    // every tick, so founders starved their own identical-genome daughters (selection on birth order, not
    // genome) — to a batched, order-independent fair split (all co-located cells draw against one snapshot;
    // over-subscribed absorbers share proportionally). All three trajectory goldens shifted; the SoA
    // determinism gates (parallelMatchesSequential, grownStateRoundTrips) held, confirming the new split is
    // deterministic. See demos/cyto/PRESSURE.md.
    // Re-baselined again same day: light became a SHARED per-cell quanta budget (CytoBiologyCore.runGene
    // now spends `work.quanta` across all light-sourced genes in genome order) instead of handing each
    // light gene its own full-power copy — removing the genome-bloat free lunch.
    // Re-baselined 2026-06-15: LIGHT SHADING (interference competition). Cells sharing a grid-cell now split
    // its incident light by capture weight (exposure × radius) in CytoSoaReducer.biology — a bigger cell
    // captures a larger share and shades its neighbours (PRESSURE.md proposal 4, applied to light). All
    // three trajectory goldens shifted (every scene divides into co-located cells); a lone cell is
    // unaffected (capture share 1 → quanta bit-identical). parallelMatchesSequential held (the per-grid-cell
    // capture sum is order-independent).
    // Re-baselined 2026-06-15: GENOME-BLOAT TAX (CytoBiologyCore.runGenes). Each active gene is now
    // throttled to a 1/N share of its energy source (N = genes active that tick), regardless of source —
    // replacing the shared light-pool spend-down with per-gene slices (the unclaimed share is lost). All
    // three trajectory goldens shifted; the determinism gates held.
    // Re-baselined 2026-06-15: ECOLOGY TUNE for higher base population + bigger fluctuations — energy
    // income unlocked (LIGHT_QUANTA_SCALE 2000→6000, the base-pop was light-limited not matter-limited)
    // and reservoir seed raised (CytoSeed.MATTER_PEAK 64→512, now the carrying-capacity ceiling). Probe:
    // founder colony booms ~1→600, busts →~200, recovers (was a flat ~40 plateau drifting to extinction).
    // Re-baselined 2026-06-15: the sim clock (SimState.tick) now ADVANCES each tick (was dormant at 0) to
    // drive the moving light field — only the `meta` digest (which hashes tick) shifted; physics/biology/
    // topology/grid are byte-identical, confirming the clock is behaviourally inert with light-moving off.
    // Re-baselined 2026-06-15: gene execution rewritten from a per-quantum LOOP to a single BULK step —
    // each active gene gets a flat 1/N share (N = active-gene count) of the cell's quanta AND of each
    // cytoplasm species it consumes, applied in one shot (no loop; MAX_OPS_PER_GENE deleted). Same matter
    // conservation, different per-tick throughput, so all trajectory dimensions shifted.
    // Re-baselined 2026-06-15: BIG-NUMBER RESCALE (×1000) — matter/biomass/thresholds/quanta all ×1000
    // (CytoSeed + CytoTuning) for fitness-landscape resolution, clear diffusion gradients, and fair
    // integer resource splitting. Now cheap because gene ops are batched (cost independent of magnitude).
    // Spec-test fixtures scaled to match (biomass 8→8000 clears the new DEATH_BIOMASS=1000 floor).
    // Re-baselined 2026-06-15: soft size cap — Convert scaled by (1 − biomass/MAX_BIOMASS_BONDS) so growth
    // tapers near the cap, bounding cell radius (keeps the contact broadphase fine). Shifts growth/division
    // timing across all scenes.
    // Re-baselined 2026-06-15: matter diffusion now runs every MATTER_DIFFUSE_PERIOD ticks (not every
    // tick) — a perf win; shifts the reservoir/grid trajectory.
    // Re-baselined 2026-06-15: k=3 alphabet cap — world seeds a,b,c (was a..g) and mutation's alphabet is
    // a,b,c (was a,b). Bounds the molecule species space for the upcoming dense chemistry. Shifts the
    // seeded reservoir (grid) and re-routes mutation PRNG draws.
    // Re-baselined 2026-06-15: SELECTIVE UPTAKE — passive exchange + diffusion only move a species into a
    // cell that can metabolise it (all its bonds handleable by the genome). Unabsorbed matter stays put
    // (conserved). Cells now hold only their metabolic set (e.g. autotroph ignores c), shifting trajectories.
    // Re-baselined 2026-06-15: environmental decay — free molecules in the matter grid break their
    // leftmost bond at rate 1/MATTER_DECAY_PERIOD (on the diffusion cadence), eroding toward monomers.
    // Recycles matter stranded by selective uptake; conservation-exact. Shifts the grid trajectory.
    // Re-baselined 2026-06-15: METABOLIC LEAK — passive exchange now retains a species the cell can
    // metabolise (no down-gradient leak, so cells hoard usable matter / imported reserves) and only dumps
    // what it can't use. Autotrophs no longer bleed their `ab` reserve to the reservoir, so all three
    // trajectory goldens shift (grid especially); the determinism gates (parallel/round-trip) held.
    // Re-baselined 2026-06-16: HARD SIZE CAP → EMERGENT metabolic slowdown. The old Convert-only
    // `(1 − biomass/MAX_BIOMASS_BONDS)` cap is replaced by throttling EVERY op (except Mitosis) by
    // `SCALE/(SCALE+biomass)` (METABOLIC_BIOMASS_SCALE) — a bigger cell metabolises slower, so growth
    // can't outpace size-proportional decay above an emergent (strength-dependent) size, no hard number.
    // Affects Convert/FormBond/Repair (all in the presets), so all three trajectory goldens shifted;
    // determinism gates held; probeCytoPopulation sustains (plateau ~67, atoms conserved, radii bounded).
    // Re-baselined 2026-06-16: ENERGY-ECONOMY pass — (1) light nerfed ~50× (LIGHT_QUANTA_SCALE 6_000_000→
    // 120_000) so a lit tick can't fund biomass/4, making light-powered division non-viable *emergently*
    // (no hard rule); (2) the per-gene EFFICIENCY gear (Gene.efficiency: Convert/Import/Repair do g+1
    // actions/energy capped at REF>>g energy/tick — FormBond/Mitosis exempt), which adds a point-mutation
    // operand (mutation PRNG nextInt(7)→(8), re-routing the mutation-on stream) and a `@g` codec token.
    // growth (mutation off) shifts from the nerf alone; interact + mutation from nerf + the new mutation op.
    // growth, mutation off, 250 ticks from the default scene.
    // Re-baselined 2026-06-19: MAINTENANCE BONUS for connected cells — biomass degradation + connection
    // stress scale by a per-cell welded-degree factor. Tried 1/(n+1), then 1/(n+1)², now **1/2^n** (1 nbr →
    // 1/2, 2 → 1/4, 6 → 1/64; halves again per extra bond). NB the GROWTH digests here equal the original
    // 1/(n+1) baseline: this scene's colony is mostly degree 0–1, where 1/2^n == 1/(n+1) (they only diverge
    // at degree ≥2), so the golden doesn't exercise the deep-interior regime. mutation-on + interact
    // byte-identical (lone cells → degree 0 → no-op). The light-exposure toggle defaults to current
    // (decoupled), so it's golden-neutral. Gates held.
    // Re-baselined 2026-06-21: VELOCITY RECONCILIATION (v = Δx/dt in integrate). The weld solve moves cells
    // through the position channel (impPos, pseudo-velocity); integrate now sets velocity to the realized
    // per-tick displacement instead of discarding the position-correction part, so velocity-reading forces
    // (drag, contacts) finally see constraint-driven motion — unblocking locomotion (a breathing organism in
    // the save now drifts ~115 cell-diam over 20k ticks; was bit-frozen). ONLY the GROWTH `physics` digest
    // moves (it hashes velocity); meta/biology/topology/grid are byte-identical (colony size, chemistry, welds
    // unchanged), and mutation-on + interact goldens are fully byte-identical (their sampled cells aren't in
    // active spring motion). Determinism gates (parallel==sequential, round-trip) held; welds now carry inertia
    // (SPRING_DAMPING bleeds the ring) and the full 130-cell save stays stable over 8k ticks.
    // Re-baselined 2026-06-21: WORLD RESCALE — CytoUnits.CELLS_PER_AXIS 1024→128 (scale objects up so the
    // fixed ±Int.MAX torus is the right size — colonies now reach + wrap at the seam) + the world-scale
    // constants ÷8 (LIGHT_FALLOFF 200→25, LIGHT_ORBIT_PERIOD 3600→450, MATTER_FALLOFF 70→9). Body-relative
    // dynamics are preserved (contraction amplitude byte-identical in probes), but absolute positions, light
    // timing, and the seeded matter field all shift, so ALL THREE trajectory goldens move in every dimension.
    // Seeded world still grows (autotrophGrowsIntoAColony green); determinism gates held; new homogeneity
    // tests (boundary≡centre) added. Grid cell is now 4 cell-diam (was 32) — the matter-field work can follow.
    // Re-baselined 2026-06-21: MATTER FIELD REWORK — MATTER_GRID_RES=1024 (sub-cell, decoupled from light),
    // dense-sparse storage, diffusion REMOVED (decay kept), uptake is now a circular DISC gather over the
    // cell's footprint (sequential id-order, conservation-exact), and seeding flipped to UNIFORM (no diffusion
    // ⇒ matter must be everywhere). All three trajectory goldens move in every dimension; matterIsConserved +
    // autotrophGrowsIntoAColony + parallel==sequential + round-trip + torus-homogeneity all held.
    // Re-baselined 2026-06-21: ADAPTIVE QUAD-TREE MATTER FIELD (QUADTREE.md). The flat CytoMatterGrid is
    // replaced by CytoMatterField — a per-tile adaptive quad-tree refined to sub-cell leaves under cell
    // footprints, with a bidirectional diffusion JUNCTION (openFootprint/balance) replacing the old flat
    // disc-gather + grid-diffusion, and observer-gated progressive collapse instead of full-map diffusion.
    // Cell matter uptake is now sampled from the fine leaves around each cell rather than a coarse grid index,
    // so per-tick uptake magnitudes differ → the whole trajectory diverges. ALL three goldens move in every
    // changed dimension; the `grid` digest now hashes the quad-tree leaves (forEachLeaf). Determinism gates
    // (parallel==sequential, round-trip) and matter conservation held.
    // Re-baselined 2026-06-21 (#2): DEPTH-SCALED COLLAPSE DELAY. The collapse threshold now DOUBLES per layer
    // above the finest (64 at depth 9, 128 at 8, 256 at 7, …) so dispersal advances at a constant speed —
    // twice as far takes twice as long (CytoMatterField.maintainNode). The finest-layer timing is unchanged,
    // so only scenarios whose unobserved regions collapse 2+ layers within budget move: GROWTH + INTERACT
    // drift (cells vacate regions that then pool more slowly), MUTATION is byte-identical. Determinism +
    // conservation gates held.
    // Re-baselined 2026-06-21 (#3): base MATTER_COLLAPSE_DELAY 64→256 (finest layer now holds 256 ticks before
    // pooling; coarser layers scale from there). Same shape as #2 — GROWTH + INTERACT drift, MUTATION
    // byte-identical; determinism + conservation held.
    // Re-baselined 2026-06-22: IMPORT FIX — the gene phase now runs BEFORE the cell↔env junction (was after),
    // so an Import gene's bias actually reaches the junction the same tick (it was cleared every tick at build
    // before the junction could ever read it ⇒ Import was inert). The reorder gives every cell a one-tick
    // phase shift between metabolism and intake, so GROWTH + INTERACT (mutation-off presets, no Import gene)
    // drift; mutationOn is byte-identical. Determinism + conservation gates held.
    private val GROWTH = mapOf(
        "meta" to "9e9bec4ae4480164",
        "physics" to "a304ed27b543623",
        "biology" to "8d961fe807057236",
        "topology" to "9aecbc0aea4ff130",
        "grid" to "6b15a597bcca8dfa",
    )
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
    private val MUTATION = mapOf(
        "meta" to "1e92bfc864dfae61",
        "physics" to "695b74b1307ab28d",
        "biology" to "be2364ac99b61159",
        "topology" to "cbf29ce484222325",
        "grid" to "3f1cd0282e716c4f",
    )
    // grow then a scripted player-interaction sequence (delete / spawn / set / detach / grab).
    private val INTERACT = mapOf(
        "meta" to "d381cf84adc97208",
        "physics" to "7263129c34560229",
        "biology" to "41fd9d494c696408",
        "topology" to "9dbfa258647279bb",
        "grid" to "8866f6988026bd69",
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
        grid?.forEachLeaf { x, y, sz, store ->
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
