package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.primitives.Frac

/**
 * **The fixed laws of the Cyto world** — the invariable constants the simulation reads *every tick* to
 * decide how matter, energy, growth, death, and physics behave. They don't change during a run; tuning
 * one changes the *rules* and re-bases the `CytoGoldenTest` goldens (re-baseline from the test's printed
 * digests after a deliberate change). Grouped by subsystem; the original owners ([CytoBiologyCore],
 * [CytoLightField], [CytoMatterField], [CytoExposure]) and the runtime [CytoConfig] read their values
 * from here.
 *
 * The *complement* of this object is [CytoSeed] — the **initial data** (starting reservoir + seed-organism
 * design) the sim is set up with and then evolves/depletes away from. If a value defines "what the world
 * starts as" rather than "a rule that holds forever", it belongs there, not here.
 *
 * Two things deliberately stay out of both:
 *  - **World coordinate scale** — `CytoUnits.CELLS_PER_AXIS` (the torus is this many base-cell diameters
 *    per axis); it defines the unit system the geometry below derives from.
 *  - **Genome *structure*** — which genes the presets are (`AUTOTROPH_GENES`/`HETEROTROPH_GENES` in
 *    [CytoGenes]); only their seed thresholds are data, and those live in [CytoSeed].
 */
object CytoTuning {

    /** Max distinct chemical species a single cell (cytoplasm or biomass) may hold. Fixed so the cell
     *  chem store is a pre-sized, non-growing array (uniform column → double-buffer-friendly). A cell
     *  acquiring a species beyond this (mainly via lysis-ingested toxins) evicts its scarcest one — the
     *  cap doubles as the toxicity mechanic (Phase 2). Grid-leaf reservoirs are uncapped. */
    const val CELL_CHEM_CAP = 32

    // ── Grid geometry (shared by the light field + matter reservoir) ─────────────────────────────────
    /** Resolution per torus axis of the light field + matter grid (the fields are smooth, so coarse is
     *  plenty). The grid has RES² cells. */
    const val GRID_RES = 64

    // ── Light field (the open energy source) ─────────────────────────────────────────────────────────
    /** Peak light at a source (≈ quanta/tick a fully-exposed cell sitting on it harvests, before the
     *  LIGHT_QUANTA_SCALE conversion). In Frac's [0,1] range. */
    val LIGHT_STRENGTH = Frac(1,1)
    /** Gaussian falloff radius of a light source (logical units): light is strong within ~σ and ~0 well
     *  before the midpoint between sources, leaving dark contested zones. In moving mode it's the
     *  half-width of the daylight band (how much of the world is "day" at once). **Scales with the world**
     *  (CELLS_PER_AXIS/4 ⇒ the band is always 1/8 of the torus span) so the day/night cycle stays
     *  self-similar under a world-size change — a fixed value would shrink the relative daylight slice and
     *  starve a center-seeded autotroph as the torus grows. ⚙ */
    val LIGHT_FALLOFF: Float get() = CytoWorldConfig.dayFraction * CytoUnits.CELLS_PER_AXIS
    /** Shading (interference competition): when true, cells sharing a grid-cell split that cell's incident
     *  light by capture weight (exposure × radius), so a bigger cell starves its neighbours. False = every
     *  cell gets its own full light (no co-located split) — toggle to test whether shading still earns its
     *  keep now the day/night cycle provides periodic selection pressure. (Lone cells are identical either
     *  way, so this only changes crowded grid-cells.) */
    const val LIGHT_SHADING = false
    /** Whether light reaches surrounded cells. **true** (current): light is independent of surface exposure —
     *  a fully-buried interior cell still photosynthesises (as if light arrives from an orthogonal 3rd
     *  dimension), so it can fund its own upkeep. **false** (the original behaviour): light scales by surface
     *  exposure, so a surrounded cell gets ~0 light. Toggle to compare interior-cell viability either way. */
    const val LIGHT_IGNORES_EXPOSURE = false
    /** Moving light: when true, a single daylight BAND sweeps across the world (a day/night terminator),
     *  wrapping once per [LIGHT_ORBIT_PERIOD] ticks, replacing the 4 static sources. Cells must then hoard
     *  through the dark (store bonded molecules + a BreakBond gene to burn them) or follow the light.
     *  false = the 4 static quarter-point sources (the original world). */
    const val LIGHT_MOVING = true
    /** Ticks for the daylight band to sweep once around the torus — the day/night period. (Only used when [LIGHT_MOVING].) */
    val LIGHT_ORBIT_PERIOD: Long get() = CytoWorldConfig.orbitPeriod

    // ── Matter dynamics (the conserved resource's per-tick law; its *seed* is in CytoSeed) ────────────
    /** Cadence of the matter field's maintenance pass (decay, then diffusion). Both are slow background
     *  processes — per-tick would be wasted work — and diffusion's cost is amortised over this period.
     *
     *  **This is the primary RATE knob for diffusion** — the only one. [MATTER_DIFFUSE_DEN] sets how visible
     *  an event is rather than how far it gets, and the size schedule is fixed, so this is the dial to turn
     *  if ecological recovery feels too slow. One pass diffuses exactly one species (~1 ms), so halving this
     *  doubles both recovery speed and diffusion's ~0.10% share of the tick — directly coupled.
     *
     *  ⚠️ **NOT a diffusion-only knob**: `maintain` runs decay AND diffusion on this cadence, so halving it
     *  also **doubles the environmental decay rate**. Pair any change with a compensating
     *  [MATTER_DECAY_PERIOD], or give diffusion its own cadence first. ⚙ */
    const val MATTER_MAINTAIN_PERIOD = 128L
    /** Environmental decay: free molecules break their leftmost bond at rate 1/this per decay step (run on
     *  the maintenance cadence). Returns matter stranded by selective uptake toward monomers. Higher = slower decay; 0 disables. ⚙ */
    const val MATTER_DECAY_PERIOD = 8000
    /** Diffusion divisor: each scheduled pass moves ⌊Δ/this⌋ across every texel edge (or one unit, when that
     *  quotient rounds to zero but a gradient remains — see [CytoMatterField.diffuse]). 0 disables diffusion.
     *
     *  **This knob sets how VISIBLE a diffusion event is, not how far diffusion gets.** It barely touches the
     *  endpoint — measured, a 21-wide crater settles at 84-86/125 for every value from 8 to 256 — but it
     *  sets the size of the single-pass jump, because diffusion is a discrete event every
     *  [MATTER_MAINTAIN_PERIOD] ticks and a texel takes flux on up to 4 edges across the H and V sweeps:
     *
     *  | DEN | biggest single-pass change to a texel, at a fresh scar rim |
     *  |-----|------------------------------------------------------------|
     *  | 8   | 28 units — 22% of the 125 seed level, in one instant: a visible pop |
     *  | 32  | 6 |
     *  | 64  | 4 — the integer floor |
     *  | 128+| 4 — identical; nothing left to gain |
     *
     *  **64 is the floor value**: at seed-level gradients the quotient rounds to zero, so every edge moves
     *  the minimum 1 unit and diffusion becomes an imperceptible creep. Raising it further only slows the
     *  dispersal of *large* piles (a death dump, where Δ is thousands) — and those should disperse at a rate
     *  proportional to how conspicuous they are, which is what the quotient term gives for free.
     *
     *  Rate lives in [MATTER_MAINTAIN_PERIOD]; size-based rate lives in the SCHEDULE
     *  ([CytoMatterField.scheduledSpecies] runs longer molecules on exponentially rarer passes). Do not
     *  reintroduce a per-species DEN scale — it would double-count the schedule's slowness.
     *
     *  **Must be ≥ 2**, and the field asserts it rather than clamping: clamping a texel up to zero would
     *  destroy matter and break conservation. ⚙ */
    const val MATTER_DIFFUSE_DEN = 64

    // ── Metabolism / energy (per gene, per tick) ─────────────────────────────────────────────────────
    /** Scale factor for all chemical interactions. Defines the ratio between the minimum cell biomass and the smallest energy unit */
    const val CHEMISTRY_SCALE = 1_000
    /** light → quanta: `quanta = ⌊field × exposure × SCALE⌋` (a fully-exposed cell on a source gets
     *  ~`STRENGTH·SCALE` ops/tick). 1 quantum = 1 op. The peak per-tick budget scales with LIGHT_QUANTA_SCALE
     *  to meter cell growth and charge-up rates. Tune by watching the cell panel's quanta. ⚙ */
    const val LIGHT_QUANTA_SCALE = CHEMISTRY_SCALE/4
    /** Per-gene efficiency gear (Gene.efficiency, g): a throughput action does `g+1` actions per energy unit
     *  but may spend at most `EFFICIENCY_REF shr g` energy/tick. g=0 is the uncapped neutral default;
     *  higher values increase actions/energy but cap the energy budget, trading throughput rate for fuel
     *  efficiency. Optimum gear is niche-dependent — see [Gene]. ⚙ */
    const val EFFICIENCY_MAX_GEAR = 16
    const val EFFICIENCY_REF = 1 shl EFFICIENCY_MAX_GEAR
    /** Import gain: each energy unit an Import gene spends lowers the cell's effective junction target
     *  (`cEff = cytoplasm − gain·k`) by this much, so the passive diffusion junction draws that many extra
     *  units IN per spent quantum (CytoBiologyCore.passiveEnvExchange). >1 makes active uptake more efficient,
     *  which a cell needs to concentrate a species above ambient and hold separation from the environment. */
    const val IMPORT_BIAS_GAIN = 4
    /** Connection damage healed per Repair op (one quantum). ⚙ */
    const val REPAIR_PER_OP = 0.25f
    /**
     * Max damage a single EXISTING connection can be healed per tick, however much repair energy the cell
     * has. Repair mends at a bounded RATE — so a connection whose per-tick stretch stress exceeds this accrues
     * net damage and eventually breaks regardless of efficiency or hoarded fuel (without this, an energy-rich
     * cell healed all damage every tick and stayed welded under any stretch). Since stretch stress scales with
     * stretch, this is effectively the *stretch the link can be held against*: below it repair wins, above it
     * the link gives. Birth-heal welds (gene-driven adhesion) are exempt — they form once at full strength. ⚙ */
    const val MAX_REPAIR_HEAL_PER_TICK = 0.5f
    /** Active-uptake gradient cost (CytoBiologyCore Import): a gene's `k` energy units import
     *  `⌊k·SCALE / (SCALE + max(0, cyto − env))⌋` molecules — 1:1 at/below the ambient reservoir level,
     *  then diminishing as the cell concentrates above it. SCALE is the excess (cyto − env) at which yield
     *  halves; lower = a tighter soft cap on hoarding + a steeper nutrient-poor niche barrier. ⚙ */
    const val IMPORT_GRADIENT_SCALE = 4_000

    // ── Growth, size & death ─────────────────────────────────────────────────────────────────────────
    /** Biomass (atoms) for a full-size (radius 1.0) cell — `radius = sqrt(atoms / ATOMS_PER_FULL)`. */
    const val ATOMS_PER_FULL = 16 * CHEMISTRY_SCALE
    /** Metabolic slowdown scale: every gene op **except Divide** is throttled by `SCALE/(SCALE+biomass)`,
     *  so metabolism runs at half speed when biomass = this. A bigger cell builds (and acquires) slower
     *  while size-proportional decay (degrade) keeps rising, so growth can't outpace decay above an
     *  EMERGENT size — a soft, strength-dependent limit (stronger cells settle larger), not a hard cap.
     *  Lower = an earlier/tighter plateau. ⚙ */
    const val METABOLIC_BIOMASS_SCALE = 32 * CHEMISTRY_SCALE
    /** Degradation: a cell's wear accumulator gains its total biomass (atoms) each tick; every
     *  DEGRADE_PERIOD of accumulated wear sheds one molecule (so decay rate ∝ size). Lower = faster decay
     *  = a stiffer maintenance cost = more selection pressure for genomes that rebuild efficiently. ⚙ */
    const val DEGRADE_PERIOD = 6000
    /** Cell dies when total biomass falls below this. */
    const val DEATH_BIOMASS = 1 * CHEMISTRY_SCALE
    /** Min cell radius (logical), from the original Cyto `Cell`. */
    val MIN_RADIUS = Frac(1, 4)
    /** Elastic blend pulling a cell's radius toward its biomass baseline each tick (higher = slower,
     *  springier relaxation; also relaxes a flexed radius back when the flex gene stops). */
    const val RADIUS_ELASTICITY = 3
    /** Radius moved per Contract op (one quantum), shrinking the cell below its biomass baseline. */
    val FLEX_STEP = Frac(1, 64)
    /** Cap on a cell's **physical** radius — everything the cell occupies space with: broadphase, welding,
     *  render, AND its matter footprint (exchange + every deposit). Emergent metabolic size still grows past
     *  it: a hoarding cell can be metabolically huge but never balloons its collider, which would otherwise
     *  coarsen the spatial grid and weld it to the whole colony — an O(n·degree) per-tick blow-up. Only
     *  oversized cells are capped. Always apply via [physicalRadius]. ⚙ */
    val MAX_COLLISION_RADIUS = Frac(1, 1)

    /** A cell's **physical** radius: its emergent metabolic [logical] radius clamped to
     *  [MAX_COLLISION_RADIUS]. Use this for anything the cell should not be able to reach beyond its own
     *  visible boundary.
     *
     *  **The matter footprint goes through here too**, as of 2026-07-16. It used to pass `logicalRadius`
     *  raw, bounded only by `CytoMatterField.MAX_DISC_RADIUS` (4.0) — 4× this cap, so **16× the area**. A
     *  giant cell rendered 1.0 wide while feeding from a 4.0 disc, visibly reaching outside itself.
     *
     *  Absorption and the deposits must clamp *identically*: a cell sheds into exactly the disc it
     *  exchanges over (see `CytoDegradeDepositTest`), so capping one without the other would let it drop
     *  matter it cannot reclaim. */
    fun physicalRadius(logical: Frac): Frac = logical.coerceAtMost(MAX_COLLISION_RADIUS)

    // ── Exposure / shading ───────────────────────────────────────────────────────────────────────────
    /** Max connected neighbours considered when computing a cell's surface exposure (a cell with more is
     *  buried → tiny exposure regardless). */
    const val EXPOSURE_MAX_NEIGHBOURS = 32
    /** Smallest exposure milli-units that allows cells to be exposed to the environment matter grid. */
    const val MIN_EXPOSURE_FOR_TRANSFER = 200

    // ── Connection physics & feel (defaults for the runtime [CytoConfig]; tune live on runCyto) ──────
    /** Position-relaxation rate of a connection per solver iteration (pseudo-velocity channel). < 1 ⇒
     *  soft: a loaded connection sits stretched instead of snapping to rest (and that stretch is what
     *  drives force-based breaking). Softening it injects no kinetic energy. */
    val SPRING_STIFFNESS = Frac(1, 20)
    /**
     * Asymmetric weld stiffness: a COMPRESSED weld (crushed below rest) relaxes this many times harder than a
     * stretched one (which uses [SPRING_STIFFNESS]). Tension stays soft so flex/contraction/locomotion are
     * untouched; only the *outward push* against crushing is stiffened. This is the dial for the through-cell
     * degeneracy: a chord A–C welded across a middle cell B is held short by B being squashable, so it settles
     * at a low over-stretch ratio — *below* the band of ordinary welds, so it can't be told apart by stretch.
     * Stiffening B's compression resistance makes the A–B/B–C struts win the equilibrium and hold A,C apart,
     * raising the chord's settled over-stretch ratio toward the straight-line ceiling: for a symmetric collinear
     * triad, ratio = m / (2·(m+2)) (m = this multiple). Welds stay fully compressible (B still squashes);
     * the chord never reaches a stretch that breaks it alone, but it lifts clear of the legit band (so a tuned
     * over-stretch break can target it) and the higher carried load makes the collinear config more sensitive
     * to perturbation (buckles B out into 2D → a legitimate triangle). Integer multiple of the base relaxation
     * rate; keep within a stable range so m·SPRING_STIFFNESS stays <1 per-iteration. ⚙ */
    const val WELD_COMPRESSION_STIFFNESS_MULTIPLE = 5
    /** Fraction of relative normal velocity cancelled per iteration (real, dissipative) — the overdamping. */
    val SPRING_DAMPING = Frac(1, 4)
    /** Repulsion impulse fraction for overlapping, non-connected cells. */
    val REPULSION = Frac(2, 3)
    /**
     * Fraction of the relative NORMAL velocity removed from an (un-welded) contact each tick — makes the
     * collision inelastic. Without it, repulsion injects an outward velocity impulse with no dissipation, so
     * a continuously-overlapping cluster (e.g. a cell dividing faster than its daughters can separate) pumps
     * unbounded velocity. With it, overlap reaches a finite equilibrium separation speed (~push/damping)
     * instead of ramping. Mirrors SPRING_DAMPING for welds.
     */
    val CONTACT_DAMPING = Frac(1, 4)
    /** When true, two non-connected cells that overlap significantly auto-weld on contact. This causes
     *  cells to stick together when physics pushes them into deep overlap (e.g. cells dividing while
     *  surrounded). ⚙ */
    const val AUTO_WELD_ON_OVERLAP = false
    /** Connection breaks when accumulated stress damage exceeds this (higher = less fragile). Halved from 5
     *  (2026-07-11): welds were too durable — a dragged welded body never tore, so cohesion/Repair had no
     *  visible stakes. Damage *scaling* (how stretch accrues stress) is unchanged; only the break threshold
     *  drops, so a hard pull now snaps un-repaired welds while Repair mends fast enough to hold. ⚙ */
    const val CONNECTION_BREAK_DAMAGE = 2.5f
    /**
     * Hard cap on how many welds one cell can hold. A new weld (division, contact-stick, or Repair-heal) is
     * refused once either endpoint already has this many. A cell has at most ~6 spatial neighbours in 2D, so
     * 10 is generous slack — but it stops a rapidly-dividing lineage from accreting an unbounded weld fan
     * (degree 25-30) that turns the colony into a wildly over-constrained network the explicit spring solver
     * can't satisfy (it diverges). Pure safety backstop; normal bodies never reach it. Also bounds
     * [CYTOPLASM_DIFFUSE_DENOM] (the diffusion divisor must stay ≥ a cell's degree to keep cytoplasm
     * diffusion non-negative). ⚙ */
    const val MAX_WELD_DEGREE = 10
    /** Size-dependence of diffusion rate. Each molecule beyond a monomer scales its diffusion divisor
     *  by this factor: `denom = base * (1 + (atomCount - 1) * scale)`. Monomers diffuse at full speed;
     *  polymers slow down. `0` = current behavior (no size effect). ⚙ */
    const val DIFFUSION_SCALE_FACTOR = 1f
    /**
     * Cytoplasm cell↔cell diffusion divisor: each cell sends `⌊count/this⌋` of a diffusible species to **each**
     * welded neighbour and keeps the rest ([CytoBiologyCore.diffuse]). A **fixed** divisor (not `degree+1`) is
     * the whole point: dividing by the *sender's own degree* makes the steady-state concentration `∝ (degree+1)`
     * — high-degree interior cells pile up ~2× their low-degree neighbours, which stalls a low↔high chemical
     * clock and corrupts a positional morphogen gradient. A fixed divisor is edge-symmetric (Fickian) → the
     * steady state is **uniform** across identical cells (a source/sink still makes a clean distance-from-source
     * gradient); the divisor only sets the *speed*, never the bias. Must stay `≥ MAX_WELD_DEGREE` so the integer
     * floor guarantees `out·degree ≤ count` (no cell goes negative); `MAX_WELD_DEGREE + 1` is the tightest
     * value with no convergence transient (the densest cell would otherwise momentarily empty). ⚙ */
    const val CYTOPLASM_DIFFUSE_DENOM = MAX_WELD_DEGREE + 1
    /**
     * Overlap (logical units, past which two *welded* cells crushed closer than their rest length start to
     * accrue breaking stress — and unlike tension stress this is NOT discounted by the maintenance bonus, so
     * a crushed weld breaks no matter how well-connected. An over-packed blob (a cell dividing faster than
     * its daughters can separate) therefore sheds its welds and fragments/disperses instead of fusing into
     * one rigid mass. Mild resting overlap below this is free, so normal packed bodies are unaffected.
     */
    const val COMPRESSION_TOLERANCE = 0.1f
    /** Exposed-surface viscous drag, quadratic coefficient over exposed speed (logical units/tick). */
    const val DRAG_COEFFICIENT = 0.8f
    /** Max fraction of a cell's exposed speed drag may remove in one tick (≤ 1) — lets a flicked cell
     *  decelerate smoothly and glide rather than slam to a stop. */
    const val DRAG_MAX_FRACTION = 0.3f
    /** Linear per-cell width drag: `coeff · radius · speed` — damps at all speeds, settling the slow
     *  drift the quadratic term leaves behind. Keep < 1/radius. */
    const val CELL_WIDTH_DRAG_COEFFICIENT = 0.00f
    /** Stretch (logical units) → stress, for connection damage. Lower = a given stretch hurts less. */
    const val CONNECTION_STRESS_SCALE = 0.5f
    /**
     * Over-stretch break distance, in multiples of a connection's rest length (rest = sum of the two radii).
     * At this much STRETCH (gap beyond rest) a link takes a full CONNECTION_BREAK_DAMAGE of stress in a
     * single tick — enough to destroy a perfectly healthy link instantly, no matter how well-knit or how
     * hard it's repaired (this term is NOT degree-discounted). Lower = links snap sooner under load. ⚙ */
    const val OVERSTRETCH_BREAK_MULTIPLE = 2.2f
    /**
     * Exponent of the over-stretch damage ramp: damage = (stretch / breakDistance)^this × CONNECTION_BREAK_DAMAGE.
     * 1 = linear (a given stretch fraction hurts proportionally — too fragile, moderate stretch breaks links);
     * higher = the curve stays near zero through low/moderate stretch and spikes only as it nears the break
     * distance, so links flex and recover under normal load but still snap at high stretch.
     * Integer (applied by repeated multiply) so it's deterministic — no transcendental pow(). ⚙ */
    const val OVERSTRETCH_DAMAGE_EXPONENT = 3
    /**
     * Through-cell (collinearity) damage: a weld whose two endpoints share a welded neighbour B sitting
     * ~collinear BETWEEN them — a chord passing *through* cell B — is a structural degeneracy the stretch
     * terms can't catch: in a crammed body such a chord sits near rest (low/zero over-stretch) yet is
     * geometrically unmistakable. When a common neighbour's angle exceeds acos(this) the chord accrues
     * [WELD_COLLINEAR_DAMAGE] of stress (NOT degree-discounted) until it breaks, leaving the real A–B / B–C
     * welds intact. The threshold sits in the dead zone between legit triangles and through-cell chords.
     * Compared via SQUARED cosine (sqrt-free) so it stays deterministic — no transcendental. ⚙ */
    const val WELD_COLLINEAR_COS = -0.7f
    /** Stress/tick added to a confirmed through-cell chord ([WELD_COLLINEAR_COS]). Net of repair (capped at
     *  [MAX_REPAIR_HEAL_PER_TICK]) this must be positive to ever break it. Higher values break chords
     *  faster. ⚙ */
    const val WELD_COLLINEAR_DAMAGE = 2.0f
    /** Scan cadence (ticks) for the collinearity check. A through-cell chord persists for thousands of ticks,
     *  so it needn't be scanned every tick; >1 amortizes the (small, degree-bounded) cost — the per-scan damage
     *  is ×this so the average break rate is cadence-independent. 1 = every tick. ⚙ */
    const val WELD_COLLINEAR_CHECK_PERIOD = 3
    /** Mouse-drag pull toward the pointer, and its damping. */
    val GRAB_STIFFNESS = Frac(1, 2)
    val GRAB_DAMPING = Frac(1, 1)
    /** Mouse-joint reach cap (logical units): the grab pull is computed as if the pointer is at most this
     *  far, so a fast/far pointer can't inject a teleporting one-tick velocity that whips the network. */
    const val GRAB_MAX_REACH = 4f
    /** Variable-mass propulsion ("rocket"): rescale velocity to hold momentum when a cell's atoms change
     *  (shed matter → speed up). A diagnostic toggle — false ablates it. */
    const val VARIABLE_MASS = true

    // ── Evolution ────────────────────────────────────────────────────────────────────────────────────
    /** Per-tick genetic damage: each gene of every cell independently faces each mutation operator
     *  (threshold drift / duplication / deletion / point-mutation) with probability `1/this` per tick.
     *  `0` disables mutation. ⚙ */
    const val MUTATION_RATE_DENOM = 0
    /** Master switch for mutation. `false` makes the live config run with rate-denom 0 — fully deterministic,
     *  no genetic drift — for authoring / observing a fixed genome. (Probes that pass an explicit
     *  `mutationRateDenom` are unaffected; this only changes the [CytoConfig] default.) */
    const val MUTATION_ENABLED = true
    /** Cell↔environment exchange is staggered across N batches: each tick only batch `(tick % N)` exchanges,
     *  so the exchange cost is divided by N. Cells are assigned to the least-populated batch when they first
     *  appear in the world (e.g. at spawn or after division), keeping batches balanced. N ≥ 1. ⚙ */
    const val EXCHANGE_BATCHES = 4
    /** Cytoplasm diffusion between connected cells runs only every Nth tick — it's a slow process
     *  so per-tick is wasteful. Higher = cheaper but slower inter-cell nutrient sharing (⚙). */
    const val CYTOPLASM_DIFFUSE_PERIOD = 4
    /** Max distinct bond-types a genome may reach (formed/broken/referenced). A mutation that would push a
     *  genome past this is rejected, bounding each cell's metabolic reach — hence (with selective uptake)
     *  its per-cell species to ≤ the molecules buildable from B bonds (B=5 → ≤52). The dense-chemistry
     *  representation relies on this bound. Different lineages can carry different B-bond sets, so global
     *  diversity isn't capped — only per-organism breadth. ⚙ */
    const val GENOME_MAX_BOND_TYPES = 5
    /** Fixed scale for size-normalised concentration readouts: `count(sp) · this / totalBiomass`, so a
     *  constant threshold reads as "molecules of sp per unit body, ×this". Size-independent (a fixed bolus
     *  dilutes as biomass grows).
     *
     *  This OUTLIVED the `Conc` gene operand it was introduced for (retired in genome v4 — the denominator
     *  was biomass, but cytoplasm capacity is `CELL_CHEM_CAP`, so it was never a concentration). Its
     *  remaining consumer is the axis-morphogen readout in `CytoSoaReducer`, which is a rendering/analysis
     *  quantity rather than a gate — so do not delete this with the operand. ⚙ */
    const val CONC_SCALE = 1 * CHEMISTRY_SCALE
    /** Max AND-clauses in one gene's condition. A mutation that would add a clause past this is rejected
     *  (bounds gate complexity + mutation cost); a positional *band* needs only 2 (`lo < Conc < hi`). ⚙ */
    const val GENOME_MAX_CLAUSES = 4
}
