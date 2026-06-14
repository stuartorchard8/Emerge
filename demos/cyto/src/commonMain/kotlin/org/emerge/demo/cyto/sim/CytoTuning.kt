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
    /** Moving light: when true, a single daylight BAND sweeps across the world (a day/night terminator),
     *  wrapping once per [LIGHT_ORBIT_PERIOD] ticks, replacing the 4 static sources. Cells must then hoard
     *  through the dark (store bonded molecules + a BreakBond gene to burn them) or follow the light.
     *  false = the 4 static quarter-point sources (the original world). */
    const val LIGHT_MOVING = false
    /** Ticks for the daylight band to sweep once around the torus — the day/night period. At ~60 ticks/s,
     *  3600 ≈ one minute. (Only used when [LIGHT_MOVING].) */
    const val LIGHT_ORBIT_PERIOD = 3600L

    // ── Matter dynamics (the conserved resource's per-tick law; its *seed* is in CytoSeed) ────────────
    /** Slow inter-grid-cell diffusion: per tick each edge moves `⌊|gradient|·NUM/DEN⌋` down-gradient.
     *  Keep `4·NUM/DEN ≤ 1` (a cell has 4 edges) so a cell can't be over-drawn negative — violating it
     *  makes the bump-to-zero clamp destroy matter (breaks conservation). Smaller = slower, coarser settle. */
    const val MATTER_DIFFUSE_NUM = 1
    const val MATTER_DIFFUSE_DEN = 8

    // ── Metabolism / energy (per gene, per tick) ─────────────────────────────────────────────────────
    /** light → quanta: `quanta = ⌊field × exposure × SCALE⌋` (a fully-exposed cell on a source gets
     *  ~`STRENGTH·SCALE` ops/tick). 1 quantum = 1 op. */
    const val LIGHT_QUANTA_SCALE = 6000
    /** Safety backstop on ops/gene/tick (Light is already capped by quanta, BreakBond by available bonds;
     *  this caps a pathological store from being processed all at once). */
    const val MAX_OPS_PER_GENE = 4096
    /** Connection damage healed per Repair op (one quantum). 0.25 ≈ the old free per-tick heal, so ~one
     *  op/tick maintains a lightly-loaded connection; more stress needs more energy. */
    const val REPAIR_PER_OP = 0.25f

    // ── Growth, size & death ─────────────────────────────────────────────────────────────────────────
    /** Biomass bonds for a full-size (radius 1.0) cell — `radius = sqrt(bonds / BONDS_PER_FULL)`. */
    const val BONDS_PER_FULL = 16
    /** Degradation: a cell's wear accumulator gains its total biomass bonds each tick; every
     *  DEGRADE_PERIOD of accumulated wear breaks one bond (so decay rate ∝ size). */
    const val DEGRADE_PERIOD = 4000
    /** Cell dies when total biomass falls below this (1 ⇒ dies once biomass is empty). */
    const val DEATH_BIOMASS = 1
    /** Min cell radius (logical), from the original Cyto `Cell`. */
    val MIN_RADIUS = Frac(1, 4)
    /** Elastic blend pulling a cell's radius toward its biomass baseline each tick (higher = slower,
     *  springier relaxation; also relaxes a flexed radius back when the flex gene stops). */
    const val RADIUS_ELASTICITY = 3
    /** Radius moved per Expand/Contract op (one quantum). */
    val FLEX_STEP = Frac(1, 64)
    /** Max radius deviation a flex gene can hold the cell at, away from its biomass baseline (Expand up to
     *  baseline+this; Contract down to [MIN_RADIUS]) — bounds the actuator. */
    val FLEX_RANGE = Frac(1, 2)

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
    /** Connection breaks when accumulated stress damage exceeds this (higher = less fragile). */
    const val CONNECTION_BREAK_DAMAGE = 3f
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
}
