package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.primitives.Frac

/**
 * **The fixed laws of the Cyto world** — the invariable constants the simulation reads *every tick* to
 * decide how matter, energy, growth, death, and physics behave. They don't change during a run; tuning
 * one changes the *rules* and re-bases the `CytoGoldenTest` goldens (re-baseline from the test's printed
 * digests after a deliberate change). Grouped by subsystem; the original owners ([CytoBiologyCore],
 * [CytoLightField], [CytoMatterGrid], [CytoExposure]) and the runtime [CytoConfig] read their values
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

    // ── Grid geometry (shared by the light field + matter reservoir) ─────────────────────────────────
    /** Resolution per torus axis of the light field + matter grid (the fields are smooth, so coarse is
     *  plenty). The grid has RES² cells. */
    const val GRID_RES = 64

    // ── Light field (the open energy source) ─────────────────────────────────────────────────────────
    /** Peak light at a source (≈ quanta/tick a fully-exposed cell sitting on it harvests, before the
     *  LIGHT_QUANTA_SCALE conversion). In Frac's [0,1] range. */
    val LIGHT_STRENGTH = Frac(1, 200)
    /** Gaussian falloff radius of a light source (logical units): light is strong within ~σ and ~0 well
     *  before the midpoint between sources, leaving dark contested zones. In moving mode it's the
     *  half-width of the daylight band (how much of the world is "day" at once). */
    const val LIGHT_FALLOFF = 200f
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
    /** Ticks for the daylight band to sweep once around the torus — the day/night period. At ~60 ticks/s,
     *  3600 ≈ one minute. (Only used when [LIGHT_MOVING].) */
    const val LIGHT_ORBIT_PERIOD = 3600L

    // ── Matter dynamics (the conserved resource's per-tick law; its *seed* is in CytoSeed) ────────────
    /** Slow inter-grid-cell diffusion: per tick each edge moves `⌊|gradient|·NUM/DEN⌋` down-gradient.
     *  Keep `4·NUM/DEN ≤ 1` (a cell has 4 edges) so a cell can't be over-drawn negative — violating it
     *  makes the bump-to-zero clamp destroy matter (breaks conservation). Smaller = slower, coarser settle. */
    const val MATTER_DIFFUSE_NUM = 1
    const val MATTER_DIFFUSE_DEN = 8
    /** Run the (whole-grid) diffusion step only every Nth tick — it's a slow background process, so
     *  per-tick is wasted work. Higher = cheaper but slower matter spread (net flow ~ 1/this). ⚙ */
    const val MATTER_DIFFUSE_PERIOD = 8L
    /** Environmental decay: free molecules break their leftmost bond at rate 1/this per decay step (run on
     *  the diffusion cadence). Returns matter stranded by selective uptake (species no live cell can use)
     *  toward monomers. Higher = slower decay; 0 disables. ⚙ */
    const val MATTER_DECAY_PERIOD = 4000

    // ── Metabolism / energy (per gene, per tick) ─────────────────────────────────────────────────────
    /** light → quanta: `quanta = ⌊field × exposure × SCALE⌋` (a fully-exposed cell on a source gets
     *  ~`STRENGTH·SCALE` ops/tick). 1 quantum = 1 op. At STRENGTH=1/200 the peak per-tick budget is
     *  `SCALE/200`, so SCALE=120_000 ⇒ ~600 ops/tick at full exposure (was 6_000_000 ⇒ ~30_000, an
     *  absurd one-tick energy that let cells photosynthesise-and-divide; division is now break-powered and
     *  this is nerfed ~50× so growth/charge-up is metered — tune by watching the cell panel's quanta). */
    const val LIGHT_QUANTA_SCALE = 120_000
    /** Per-gene efficiency gear (Gene.efficiency, g): a throughput action does `g+1` actions per energy unit
     *  but may spend at most `EFFICIENCY_REF shr g` energy/tick. REF=2^16: g=0 ⇒ 1 action/energy, 65 536
     *  energy cap (effectively unlimited at the light scale, so g=0 is the neutral default); g=16 ⇒ 17
     *  actions/energy but only 1 energy/tick (≈17 actions). Optimum gear is niche-dependent — see [Gene]. */
    const val EFFICIENCY_REF = 1 shl 16
    const val EFFICIENCY_MAX_GEAR = 16
    /** Connection damage healed per Repair op (one quantum). 0.25 ≈ the old free per-tick heal, so ~one
     *  op/tick maintains a lightly-loaded connection; more stress needs more energy. */
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
    /** Biomass bonds for a full-size (radius 1.0) cell — `radius = sqrt(bonds / BONDS_PER_FULL)`. */
    const val BONDS_PER_FULL = 16_000
    /** Metabolic slowdown scale: every gene op **except Mitosis** is throttled by `SCALE/(SCALE+biomass)`,
     *  so metabolism runs at half speed when biomass = this. A bigger cell builds (and acquires) slower
     *  while size-proportional decay (degrade) keeps rising, so growth can't outpace decay above an
     *  EMERGENT size — a soft, strength-dependent limit (stronger cells settle larger), not a hard cap.
     *  Lower = an earlier/tighter plateau. ⚙ */
    const val METABOLIC_BIOMASS_SCALE = 32_000
    /** Degradation: a cell's wear accumulator gains its total biomass bonds each tick; every
     *  DEGRADE_PERIOD of accumulated wear breaks one bond (so decay rate ∝ size). */
    const val DEGRADE_PERIOD = 4000
    /** Cell dies when total biomass falls below this. At the ×1000 scale a divided daughter is ~4000 bonds,
     *  so 1000 is a real starvation floor with headroom (raise it for harsher culling). */
    const val DEATH_BIOMASS = 1_000
    /** Min cell radius (logical), from the original Cyto `Cell`. */
    val MIN_RADIUS = Frac(1, 4)
    /** Elastic blend pulling a cell's radius toward its biomass baseline each tick (higher = slower,
     *  springier relaxation; also relaxes a flexed radius back when the flex gene stops). */
    const val RADIUS_ELASTICITY = 3
    /** Radius moved per Contract op (one quantum), shrinking the cell below its biomass baseline. */
    val FLEX_STEP = Frac(1, 64)
    /** Cap on a cell's **collision/physical** radius (the broadphase + welding + render footprint), even as
     *  its biomass/metabolic size grows past it. Decouples emergent metabolic size from physical size: a
     *  hoarding cell can be metabolically huge but never balloons its collider, which would otherwise coarsen
     *  the spatial grid (cellSize = 2·maxRadius) and weld it to the whole colony — an O(n·degree) per-tick
     *  blow-up. Normal cells sit under this (radius 1.0 = biomass 16k; carrying-capacity cells ~0.7-0.87, and
     *  the seed GROW gates are < 16k), so only oversized cells have their footprint capped. */
    val MAX_COLLISION_RADIUS = Frac(1, 1)

    // ── Exposure / shading ───────────────────────────────────────────────────────────────────────────
    /** Max connected neighbours considered when computing a cell's surface exposure (a cell with more is
     *  buried → tiny exposure regardless). */
    const val EXPOSURE_MAX_NEIGHBOURS = 32

    // ── Connection physics & feel (defaults for the runtime [CytoConfig]; tune live on runCyto) ──────
    /** Position-relaxation rate of a connection per solver iteration (pseudo-velocity channel). < 1 ⇒
     *  soft: a loaded connection sits stretched instead of snapping to rest (and that stretch is what
     *  drives force-based breaking). Softening it injects no kinetic energy. */
    val SPRING_STIFFNESS = Frac(1, 20)
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
    /** Connection breaks when accumulated stress damage exceeds this (higher = less fragile). */
    const val CONNECTION_BREAK_DAMAGE = 3f
    /**
     * Hard cap on how many welds one cell can hold. A new weld (division, contact-stick, or Repair-heal) is
     * refused once either endpoint already has this many. A cell has at most ~6 spatial neighbours in 2D, so
     * 10 is generous slack — but it stops a rapidly-dividing lineage from accreting an unbounded weld fan
     * (degree 25-30) that turns the colony into a wildly over-constrained network the explicit spring solver
     * can't satisfy (it diverges). Pure safety backstop; normal bodies never reach it. Also bounds
     * [CYTOPLASM_DIFFUSE_DENOM] (the diffusion divisor must stay ≥ a cell's degree to keep cytoplasm
     * diffusion non-negative). ⚙ */
    const val MAX_WELD_DEGREE = 10
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
    const val DRAG_COEFFICIENT = 0.2f
    /** Max fraction of a cell's exposed speed drag may remove in one tick (≤ 1) — lets a flicked cell
     *  decelerate smoothly and glide rather than slam to a stop. */
    const val DRAG_MAX_FRACTION = 0.3f
    /** Linear per-cell width drag: `coeff · radius · speed` — damps at all speeds, settling the slow
     *  drift the quadratic term leaves behind. Keep < 1/radius. */
    const val CELL_WIDTH_DRAG_COEFFICIENT = 0.02f
    /** Stretch (logical units) → stress, for connection damage. Lower = a given stretch hurts less. */
    const val CONNECTION_STRESS_SCALE = 0.5f
    /**
     * Over-stretch break distance, in multiples of a connection's rest length (rest = sum of the two radii ≈
     * one cell-width, two touching cells' centres being one diameter apart). At this much STRETCH (gap beyond
     * rest) a link takes a full CONNECTION_BREAK_DAMAGE of stress in a single tick — enough to destroy a
     * perfectly healthy link instantly, no matter how well-knit or how hard it's repaired (this term is NOT
     * degree-discounted). 2.0 ⇒ a ~2-cell-width gap, where the bond rendering starts breaking down. Lower =
     * links snap sooner under load. ⚙ */
    const val OVERSTRETCH_BREAK_MULTIPLE = 2.0f
    /**
     * Exponent of the over-stretch damage ramp: damage = (stretch / breakDistance)^this × CONNECTION_BREAK_DAMAGE.
     * 1 = linear (a given stretch fraction hurts proportionally — too fragile, moderate stretch breaks links);
     * higher = the curve stays near zero through low/moderate stretch and spikes only as it nears the break
     * distance, so links flex and recover under normal load but still snap instantly at ~2 cell-widths.
     * Integer (applied by repeated multiply) so it's deterministic — no transcendental pow(). ⚙ */
    const val OVERSTRETCH_DAMAGE_EXPONENT = 3
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
     *  `0` disables mutation. At 1/100k a cell accrues well under one mutation per ~10k-tick lifetime, so
     *  most individuals persist unmodified while the population explores. */
    const val MUTATION_RATE_DENOM = 100_000
    /** Master switch for mutation. `false` makes the live config run with rate-denom 0 — fully deterministic,
     *  no genetic drift — for authoring / observing a fixed genome. (Probes that pass an explicit
     *  `mutationRateDenom` are unaffected; this only changes the [CytoConfig] default.) */
    const val MUTATION_ENABLED = true
    /** Max distinct bond-types a genome may reach (formed/broken/referenced). A mutation that would push a
     *  genome past this is rejected, bounding each cell's metabolic reach — hence (with selective uptake)
     *  its per-cell species to ≤ the molecules buildable from B bonds (B=5 → ≤52). The dense-chemistry
     *  representation relies on this bound. Different lineages can carry different B-bond sets, so global
     *  diversity isn't capped — only per-organism breadth. ⚙ */
    const val GENOME_MAX_BOND_TYPES = 5
    /** Fixed scale for the [org.emerge.demo.cyto.sim.Operand.Conc] (concentration) operand: `Conc(sp)`
     *  evaluates to `count(sp) · this / totalBiomass` (size-normalised), so a constant threshold reads as
     *  "molecules of sp per unit body, ×this". 1000 ⇒ a threshold of 100 ≈ 0.1 molecule per biomass-bond.
     *  Size-independent (a fixed bolus dilutes as biomass grows → a developmental clock). ⚙ */
    const val CONC_SCALE = 1_000
    /** Max AND-clauses in one gene's condition. A mutation that would add a clause past this is rejected
     *  (bounds gate complexity + mutation cost); a positional *band* needs only 2 (`lo < Conc < hi`). ⚙ */
    const val GENOME_MAX_CLAUSES = 4
}
