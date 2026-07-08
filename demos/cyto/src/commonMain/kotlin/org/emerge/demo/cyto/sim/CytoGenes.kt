package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Frac

/**
 * The matter-model gene (MORPHOGENESIS.md). A gene is exactly three parts — **one energy source** (how
 * many discrete ops it can do this tick), **one binary condition** (flatly gates it on/off), and **one
 * action** — with no weighted-sum activation. A cell's **genome** (its [Gene] list) is heritable: seeded
 * from [genomeForType] at spawn, carried per-cell, and inherited clonally on division.
 *
 * Chemistry is integer molecule counts; energy is discrete quanta (1 per bond); 1 quantum = 1 op. Genes
 * read/write the cell's [CellWork]; the per-cell execution (gates, ops, env access) lives in
 * [CytoBiologyCore].
 */

/** Where a gene gets the energy that powers its action this tick — one quantum per op:
 *  [Light] (autotrophy — free environmental flux scaled by surface exposure) or [BreakBond]
 *  (heterotrophy — break a stored bond, releasing its quantum and splitting the molecule back to
 *  fragments). */
sealed class EnergySource {
    /** Environmental light: up to `floor(field × exposure × scale)` quanta this tick. */
    object Light : EnergySource()

    /** Break one instance of [bond] (a 2-atom pair, e.g. `"rg"`) in a cytoplasm molecule per op,
     *  releasing its quantum; the molecule splits into two fragments returned to the cytoplasm. */
    data class BreakBond(val bond: String) : EnergySource()
}

/** A binary gate comparator. */
enum class Comparison { Greater, Less }

/** One side of a gene's binary [GeneCondition] — the value fed to the comparator. Either a fixed
 *  [Constant] (the former gate "threshold"), or one of three **live readings** of the cell this tick:
 *  a cytoplasm [Chem] count, total [Biomass], or the [Touching] contact count. Because *both* sides of a
 *  condition are operands, a gene can gate on a relationship between two live quantities — e.g.
 *  `Biomass < Chem("rg")` ("while I'm smaller than my stored `rg` reserve") — not only variable-vs-constant. */
sealed class Operand {
    /** A fixed integer compared against (the former gate threshold). */
    data class Constant(val value: Int) : Operand()

    /** Count of [species] in the cytoplasm (0 for an absent / unknown species). */
    data class Chem(val species: String) : Operand() {
        /** [species] resolved to its [SpeciesRegistry] id once at construction (a pure function of the
         *  immutable [species] string), so the per-tick gate path reads an int instead of re-hashing the
         *  string every evaluation. Not a constructor param ⇒ untouched by equals/hashCode/copy. */
        val speciesId: Int = SpeciesRegistry.id(species)
    }

    /** Total biomass — Σ count × bond-count (also drives cell size + the death threshold). */
    object Biomass : Operand()

    /** Number of **un-connected** cells this cell is in physical contact with this tick — i.e.
     *  `Touching > 0` fires while the cell is bumping a neighbour it isn't welded to. The matter-model
     *  port of old Cyto's `Touch` gene input (a cell sensing collision pressure); welded neighbours don't
     *  count (they're structure, not a touch event). A reactive, contact-driven gate. */
    object Touching : Operand()

    /** **Concentration** of [species] — `count(species) · CytoTuning.CONC_SCALE / totalBiomass`, the
     *  size-normalised counterpart of [Chem] (which is the raw count). 0 when biomass is 0 or the species is
     *  absent. Because it divides by body size, a *fixed* morphogen bolus reads lower as the cell grows — a
     *  developmental clock for free — and a positional gradient reads as concentration *bands* independent of
     *  cell size. The morphogen-for-shape readout (MORPHOGENESIS.md §Morphogens for shape). Like [Chem] it is
     *  a *sensor*, never added to the metabolic reach (sensing ≠ permeability — see [handleableOf]). */
    data class Conc(val species: String) : Operand() {
        /** [species] resolved to its [SpeciesRegistry] id once at construction (see [Chem.speciesId]). */
        val speciesId: Int = SpeciesRegistry.id(species)
    }
}

/** One AND-clause of a gene's gate: `lhs cmp rhs`, each side an [Operand]. */
data class Clause(val lhs: Operand, val cmp: Comparison, val rhs: Operand)

/** A gene's gate: a **conjunction** of [Clause]s — the gene fires this tick iff **every** clause holds
 *  (an empty list is vacuously true). NOT is expressed as [Comparison.Less] (below/absence); OR by separate
 *  genes with the same action; there are deliberately **no weighted sums** (they saturate, don't evolve).
 *  Two clauses give a positional *band* (`lo < Conc(m)` AND `Conc(m) < hi`), the French-flag readout shape
 *  is built from. */
data class GeneCondition(val clauses: List<Clause>) {
    /** Single-clause convenience — the overwhelmingly common shape; keeps terse call sites + presets. */
    constructor(lhs: Operand, cmp: Comparison, rhs: Operand) : this(listOf(Clause(lhs, cmp, rhs)))
}

/** The single action a gene performs (v1 set). */
enum class ActionType {
    /** Move molecules of [GeneAction.a] from the local environment into the cytoplasm. */
    Import,
    /** Join a cytoplasm molecule ending in atom [GeneAction.a] with one starting in atom [GeneAction.b]. */
    FormBond,
    /** Lock molecules of [GeneAction.a] from cytoplasm into biomass (structure → size). */
    Convert,
    /** Push the cell's radius **below** its biomass baseline, down to [MIN_RADIUS] (operands unused). Each
     *  op nudges the radius in by a fixed step; the per-tick elastic relaxation toward the biomass-derived
     *  size (see [CytoBiologyCore.finish]) pulls it back when the gene stops firing, so it behaves like an
     *  actively-held muscle — the locomotion actuator, flexed against the asymmetric surface drag. There is
     *  deliberately **no Expand counterpart**: a radius *above* the biomass baseline would raise the
     *  broadphase's max-radius (which sets the spatial-grid cell size), coarsening the collision grid for the
     *  whole world. Contraction only ever shrinks a cell, so it never coarsens the grid; it's sufficient for
     *  locomotion on its own (a travelling contraction wave). */
    Contract,
    /** Divide (mitosis). [GeneAction.a] optionally names a **morphogen** allocated *whole to one daughter*
     *  (asymmetric division, MORPHOGENESIS.md §C); empty ⇒ symmetric 50/50 split. [GeneAction.b] unused. */
    Mitosis,
    /** Repair connection damage: each op heals the cell's most-damaged connection (operands unused).
     *  Holding a body together is therefore genetic + energy-costing — there is no free heal. */
    Repair,
    /** Tear biomass from touching un-welded cells: the attacker shreds the victim's biomass and
     *  assimilates everything it can hold. Undigestible species are forced into the attacker's
     *  cytoplasm — a metabolic burden that accumulates and dilutes useful cytoplasm. This creates
     *  an evolutionary arms race: prey can evolve a predator-toxic chemical (held by prey,
     *  undigestible by predator) as defense, forcing predators to either evolve detox or avoid that
     *  prey type. Efficiency gear controls the damage/capture balance per MORPHOGENESIS.md §B. */
    Lyse,
}

/** A gene's action plus its (action-dependent) operands — single atoms for [ActionType.FormBond],
 *  a species for [ActionType.Import]/[ActionType.Convert], an optional morphogen species for
 *  [ActionType.Mitosis] (asymmetric division; empty ⇒ symmetric). [morphogenToMother] only applies to
 *  [ActionType.Mitosis] with a non-empty morphogen: it picks **which side keeps the morphogen** — `false`
 *  (default) hands it to the **daughter** (placed outward → an *edge/axial* source as the colony grows),
 *  `true` keeps it in the **mother** (stays embedded → a *centred/radial* source). A body-plan selector
 *  (MORPHOGENESIS.md §Source placement); invariant: only ever `true` when [type] is Mitosis.
 *
 *  **Oriented division** (MORPHOGENESIS.md §Morphogens for shape): for [ActionType.Mitosis], [b] names an
 *  **axis-morphogen** — if non-empty, the daughter is placed relative to that morphogen's *local gradient*
 *  (computed at division from welded neighbours) instead of toward free space: [divideAcross] `false` =
 *  *along* ∇ (project → extends a thread), `true` = *across* ∇ (slice → widens into a 2D sheet). Empty [b] ⇒
 *  unoriented (today's free-space placement). [divideAcross] is only ever `true` when [type] is Mitosis.
 *
 *  **FormBond reactant matching** (MORPHOGENESIS.md §2026-06-18): for [ActionType.FormBond], [a]/[b] name the
 *  two reactants by **exact species** (`r`+`g` → monomer `r` joined to monomer `g` → `rg`). [aWild] makes
 *  [a] a **suffix wildcard** (match the most-abundant molecule *ending* with [a], the legacy behaviour);
 *  [bWild] makes [b] a **prefix wildcard** (most-abundant *starting* with [b]). The bare atoms always live in
 *  [a]/[b] (the flags only switch exact↔wildcard), so [handleableOf] reads them unchanged. Both flags are
 *  only ever `true` when [type] is FormBond. */
 data class GeneAction(
    val type: ActionType,
    val a: String = "",
    val b: String = "",
    val morphogenToMother: Boolean = false,
    val divideAcross: Boolean = false,
    /** When true (Mitosis only): the daughter rejects all welds from the mother, splitting off as a
     *  separate 1-celled organism. The mother keeps its connections intact. */
    val rejectMother: Boolean = false,
    val aWild: Boolean = false,
    val bWild: Boolean = false,
) {
    /** [a]/[b] resolved to [SpeciesRegistry] ids once at construction (pure functions of the immutable
     *  operand strings; -1 when empty/not a species), so applyGene's exact-species paths (Convert / Import /
     *  exact FormBond) read an int instead of re-hashing. Not constructor params ⇒ no effect on data-class
     *  equality. The wildcard FormBond paths still match on the [a]/[b] strings. */
    val aId: Int = SpeciesRegistry.id(a)
    val bId: Int = SpeciesRegistry.id(b)
}

/**
 * A gene: an energy source, a binary condition, an action — and an **efficiency gear** [efficiency] (g, in
 * `[0, CytoTuning.EFFICIENCY_MAX_GEAR]`). The gear is a per-gene rate↔efficiency trade-off for the
 * *throughput* actions (Convert / Import / Repair / Contract): each energy unit performs `g+1` actions (so
 * higher g = more output per scarce energy), but the energy it may spend this tick is capped at
 * `EFFICIENCY_REF >> g` (so higher g = a lower throughput ceiling). For **Contract** this is the muscle-fibre
 * axis: low g = fast-twitch (1 flex step/quantum, burns energy hard for max per-tick travel), high g =
 * slow-twitch (g+1 steps/quantum so contraction sips fuel — fewer BreakBond bonds per step — but the cap
 * rate-limits per-tick travel). **FormBond gets the *cap* but not the
 * `g+1` multiplier** (it's a lossless 1:1 conversion — a multiplier would mint bonds): there the gear is pure
 * potency-limiting, used e.g. to set how far a morphogen spreads from a source/sink loop. **Mitosis is
 * exempt** (a fixed `biomass/4` bulk event). The optimum gear is niche-dependent —
 * energy-poor cells favour high g (squeeze every quantum), energy-rich cells favour low g (burn surplus for
 * raw throughput) — and a low-g gene is *always* less efficient (1 action/energy), even when its high
 * ceiling goes unused, which is the cost that makes high throughput a niche adaptation, not a free bonus.
 */
data class Gene(
    val source: EnergySource,
    val condition: GeneCondition,
    val action: GeneAction,
    val efficiency: Int = 0,
) {
    /** Pre-computed: efficiency gear + 1 (throughput multiplier for actions). */
    val gP1: Int get() = efficiency + 1

    /** Pre-computed: energy cap based on efficiency gear. 0 = uncapped (Int.MAX_VALUE). */
    val energyCap: Int get() {
        val g = efficiency.coerceIn(0, CytoTuning.EFFICIENCY_MAX_GEAR)
        return if (g == 0) Int.MAX_VALUE else CytoTuning.EFFICIENCY_REF ushr g
    }

    /** Pre-computed: whether this gene's action uses efficiency gear
     *  (Convert/Import/Repair/Contract = throughput multiplier, Lyse = capture fraction). */
    val actionHasEfficiency: Boolean get() = when (action.type) {
        ActionType.Convert, ActionType.Import, ActionType.Repair, ActionType.Contract, ActionType.Lyse -> true
        else -> false
    }

    /** Pre-computed: whether this gene's action has the energy cap (Convert/Import/Repair/FormBond/Contract). */
    val actionHasCap: Boolean get() = when (action.type) {
        ActionType.Convert, ActionType.Import, ActionType.Repair, ActionType.FormBond, ActionType.Contract -> true
        else -> false
    }

    // Note: breakSpId, fragLId, fragRId are computed at apply time because they depend on snap.
    // But bondIndexOf is pre-computable.
    val preBondIdx: Int get() = (source as? EnergySource.BreakBond)?.let {
        SpeciesRegistry.bondIndexOf(it.bond)
    } ?: -1
}

/**
 * A cell's metabolic reach — the bonds and atoms its genome can handle — used by **selective uptake**:
 * a cell may only absorb/hold a species *all* of whose bonds (and, for a monomer, whose atom) it can
 * handle. This bounds per-cell species to molecules buildable from the genome's bond-set (≤ a few dozen
 * at the B=5 bond-cap), regardless of how diverse the surrounding environment is.
 */
class Handleable(
    private val bondMask: Int, private val atomMask: Int,                  // full reach: metabolised OR synthesised
    private val diffuseBondMask: Int, private val diffuseAtomMask: Int,     // metabolised only (Break / Convert / Import)
) {
    /** Distinct bond-types this genome reaches — capped by CytoTuning.GENOME_MAX_BOND_TYPES so per-cell
     *  species stay bounded (B bonds → ≤ a few dozen buildable molecules). */
    val bondTypeCount: Int get() = bondMask.countOneBits()

    /** Identity of the **canHold** reach (the (bond,atom) masks [canHold] tests against), packed into a Long.
     *  Two cells with equal keys answer [canHold] identically for every species — so passive exchange can
     *  group co-located cells by key and test each grid species against the few distinct keys instead of
     *  every cell (a clonal blob collapses to ~1 key). diffuse masks are excluded (not part of canHold). */
    val canHoldKey: Long get() = (bondMask.toLong() shl 32) or (atomMask.toLong() and 0xFFFFFFFFL)

    /** Can the cell **hold/retain** species [id] — reachable by metabolism (Break/Convert/Import) *or* by
     *  synthesis (FormBond)? A monomer iff its atom is reachable; a molecule iff *every* bond it contains is
     *  in the set (a bitmask subset over [SpeciesRegistry] ids). Gates passive uptake + retain-vs-leak: a
     *  species the genome produces or uses is kept in the cell, not shed to the environment. */
    fun canHold(id: Int): Boolean = reaches(id, bondMask, atomMask)

    /** Can the cell **diffuse** species [id] to/from welded neighbours — only species it **metabolises**
     *  (Break/Convert/Import), i.e. resources/signals *in flux*. A species the genome merely **synthesises**
     *  (FormBond) and never consumes is **intracellular**: held + readable, but never shared — cell-private
     *  memory / a non-spreading determinant (MORPHOGENESIS.md §Morphogens for shape: produce-without-diffuse,
     *  the intra-vs-inter-cellular morphogen split). */
    fun canDiffuse(id: Int): Boolean = reaches(id, diffuseBondMask, diffuseAtomMask)

    private fun reaches(id: Int, bm: Int, am: Int): Boolean {
        if (id < 0) return false
        return if (SpeciesRegistry.atomCount(id) == 1) (am ushr SpeciesRegistry.firstAtom(id)) and 1 == 1
        else (SpeciesRegistry.bondMask(id) and bm.inv()) == 0
    }
}

/** Derive a genome's [Handleable] reach: bonds it forms (FormBond), breaks (BreakBond), or names in a
 *  Convert/Import operand; atoms are the endpoints of those bonds plus any monomer operand. Bonds/atoms
 *  are accumulated as [SpeciesRegistry]-indexed bitmasks (the alphabet is ≤ k=3 atoms, k²=9 bonds, so each
 *  fits an Int).
 *
 *  **Sensing ≠ permeability:** a species a gene only *reads* in a [GeneCondition] (an [Operand.Chem] or
 *  [Operand.Conc] gate) is deliberately **not** added — a sensor is not a channel. This is what lets a **morphogen** stay a
 *  *trace* species: a fate gene can gate on `Chem(m)` to differentiate without thereby making `m`
 *  metabolisable, so the canHold-gated passive exchange + cell↔cell diffusion can't equilibrate it across a
 *  colony. The morphogen difference an asymmetric division establishes (MORPHOGENESIS.md §C) therefore
 *  persists. (A species a gene both senses *and* metabolises is still handleable via the metabolic ref.)
 *
 *  **Metabolism vs synthesis (produce-without-diffuse):** contributions are split into a **metabolic** set
 *  (Break / Convert / Import — species the genome *consumes/transports*) and a **synthetic** set (FormBond
 *  — species it *produces*). `canHold` = both (retain anything you produce or use); `canDiffuse` = metabolic
 *  only. So a species the genome only synthesises (and never consumes) is **intracellular** — held + sensed
 *  but never shared with neighbours: cell-private memory (the intra-vs-inter-cellular morphogen split). */
fun handleableOf(genome: List<Gene>): Handleable {
    var bondMask = 0; var atomMask = 0          // full reach (metabolic ∪ synthetic) → canHold
    var mBondMask = 0; var mAtomMask = 0        // metabolic only (Break/Convert/Import) → canDiffuse
    fun addAtom(c: Char, metabolic: Boolean) {
        val a = SpeciesRegistry.atomIndexOf(c); if (a >= 0) { atomMask = atomMask or (1 shl a); if (metabolic) mAtomMask = mAtomMask or (1 shl a) }
    }
    fun addBond(pair: String, metabolic: Boolean) {
        val b = SpeciesRegistry.bondIndexOf(pair); if (b >= 0) { bondMask = bondMask or (1 shl b); if (metabolic) mBondMask = mBondMask or (1 shl b) }
    }
    fun addSpecies(s: String, metabolic: Boolean) {
        if (s.isEmpty()) return
        for (c in s) addAtom(c, metabolic)
        for (i in 0 until s.length - 1) addBond(s.substring(i, i + 2), metabolic)
    }
    for (g in genome) {
        (g.source as? EnergySource.BreakBond)?.let { addSpecies(it.bond, metabolic = true) }   // catabolism consumes the bond
        // NB: condition (Operand.Chem/Conc) operands are NOT added — sensing a species doesn't make it
        // transportable (see the kdoc: this keeps a gated morphogen a trace species).
        when (g.action.type) {
            ActionType.FormBond -> {
                // SYNTHESIS — the cell produces the joined molecule (and handles the operand fragments + the
                // junction bond suffix.last–prefix.first). Production lets it HOLD the species, but does NOT
                // make it diffusible (metabolic = false): a synthesised-but-never-consumed species stays
                // intracellular. (If some gene also metabolises it, that ref flips it diffusible.)
                val a = g.action.a; val b = g.action.b
                if (a.isNotEmpty() && b.isNotEmpty()) { addSpecies(a, metabolic = false); addSpecies(b, metabolic = false); addBond("${a.last()}${b.first()}", metabolic = false) }
            }
            ActionType.Convert, ActionType.Import -> addSpecies(g.action.a, metabolic = true)   // consumes / transports
            else -> {}
        }
    }
    return Handleable(bondMask, atomMask, mBondMask, mAtomMask)
}

/**
 * Mutable per-tick working state for one cell. Matter lives in two integer count maps: mobile
 * [cytoplasm] (genes act on it; diffuses to neighbours) and locked [biomass] (structure; sets size).
 * [quanta] is this tick's energy budget (from light × exposure); [wear] is the degradation accumulator
 * carried across ticks. No `Frac` chemistry, no energy pool.
 *
 * **Pooled:** the SoA reducer keeps one CellWork per slot and [reset]s it every tick rather than
 * allocating a fresh object — so all fields are mutable and [connectionDamage] is a reused map (cleared +
 * refilled). [handleable] is recomputed only when the [genome] reference actually changes (it's stable
 * across ticks unless mutated/edited), saving the per-tick bitmask rebuild.
 *
 * **Column-slab double-buffer:** [reset] assigns [cytoplasm]/[biomass] to reference the column's
 * back-buffer slot directly (no copy). Genes mutate the back buffer in place. [writeback] commits
 * by swapping the column's front/back buffers in O(1).
 */
class CellWork(
    var cytoplasm: MoleculeStore,
    var biomass: MoleculeStore,
    var logicalRadius: Frac,
    var type: CellType,
    genome: List<Gene>,
    /** Light energy available to the cell this tick (1 quantum = 1 op). Each active light gene gets a
     *  1/N share of it (the bloat tax — see CytoBiologyCore.runGenes), not the whole pool. */
    var quanta: Int,
    /** Number of un-connected cells this cell is in contact with this tick (the [Operand.Touching]
     *  gate reads it). Transient — recomputed from the physics contacts each tick, never persisted. */
    var touchCount: Int,
    /** Degradation accumulator carried across ticks (gains total-biomass-bonds each tick). */
    var wear: Int,
    /** The environment matter-grid cell this cell sits in (where Import draws / death deposits). -1 if
     *  the cell has no position. */
    var gridIndex: Int,
    /** This cell's per-connection stress damage (neighbour → damage), seeded from its
     *  [ConnectionStateComponent]. A Repair gene op heals the most-damaged entry; the biology system
     *  writes the (possibly healed) map back, and connection maintenance accrues stress on top. */
    val connectionDamage: MutableMap<EntityId, Float>,
) {
    /** The genome this tick. Reassigning recomputes [handleable] only when the reference differs. */
    var genome: List<Gene> = genome
        private set

    /** What this cell can metabolise (hence absorb/hold), derived from its genome — selective uptake gates
     *  passive exchange + diffusion on this so per-cell species stay bounded (see [handleableOf]). */
    var handleable: Handleable = handleableOf(genome)
        private set

    /** Set true by a fired Mitosis gene; the lifecycle splits the cell. */
    var dividing = false

    /** The fired Mitosis gene's operand (its [GeneAction.a]) — the morphogen species allocated **whole to
     *  one daughter** on division (asymmetric mitosis, MORPHOGENESIS.md §C). "" ⇒ symmetric 50/50 split. */
    var divideMorphogen: String = ""

    /** The fired Mitosis gene's [GeneAction.morphogenToMother] — keep [divideMorphogen] in the mother
     *  (centred source) rather than handing it to the daughter (edge source). */
    var divideMorphogenToMother: Boolean = false

    /** The fired Mitosis gene's [GeneAction.b] (axis-morphogen) + [GeneAction.divideAcross] — orient the
     *  split along (`false`) / across (`true`) that morphogen's local gradient. Empty ⇒ unoriented. */
    var divideAxisMorphogen: String = ""
    var divideAcross: Boolean = false

    /** The fired Mitosis gene's [GeneAction.rejectMother] — the daughter rejects all mother welds,
     *  splitting off as a separate 1-celled organism. */
    var divideRejectMother: Boolean = false

    /** Cooldown ticks remaining before mitosis genes are re-evaluated. Set to [genomeSize] when a
     *  mitosis fires, preventing the cell from dividing again until its biomass recovers and the
     *  round-robin cycles past the mitosis gene. Prevents instant re-division cascades. */
    var mitosisCooldown: Int = 0

    /** True once a Repair gene healed any connection this tick — gates writing [connectionDamage] back. */
    var repaired = false

    /** Un-welded cells this cell is **touching** this tick (filled from the contacts phase after [reset]).
     *  A firing Repair gene welds these (CytoBiologyCore.applyRepair) — gene-driven adhesion. */
    val touchingIds: MutableList<EntityId> = ArrayList()

    /** Lysis attack targets: victim entity-id → count of species to steal. A firing Lyse gene queues
     *  attacks on all touching un-welded cells; the attack phase processes them after biology (MORPHOGENESIS.md §B). */
    val lyseTargets: MutableMap<EntityId, Int> = HashMap()

    /** Logical positions of touching cells, aligned with [touchingIds].
     *  Used by degrade to find the center-most touching cell for biomass shedding. */
    var _touchingCellCx = FloatArray(8)
    var _touchingCellCy = FloatArray(8)
    var _touchingCellN = 0

    /** Cells this cell shares a weld-neighbour with, derived from [touchingIds] by the reducer before the
     *  biology phase (used to restrict adhesion welds to internal body repair). */
    val internalTouching: MutableSet<EntityId> = HashSet()

    /** Welds the Repair action wants to form this tick: touching-cell id → the repair healed into the new
     *  weld at birth. Drained by the reducer into [systems.WeldHealIntent]s for the lifecycle. */
    val weldHeals: MutableMap<EntityId, Float> = HashMap()

    /** This cell's surface exposure this tick, in milli-units (0..1000; 1000 = lone/fully exposed, ~0 =
     *  fully surrounded). Damps passive env↔cytoplasm exchange (CytoBiologyCore.exchangeSpecies) so a
     *  buried cell is buffered from the environment — its internal chemistry isn't perturbed by the reservoir
     *  and a body's interior is a protected milieu. (Set by the reducer from [CytoExposure.weight].) */
    var exposureMilli = 1000

    /** This cell's welded-neighbour count this tick. The **maintenance bonus**: a more-connected (less
     *  exposed) cell pays less upkeep — biomass degradation is scaled by `1/(weldedDegree+1)` (and connection
     *  stress likewise, in the connections phase), so 1 connection halves it, 2 thirds it, etc. */
    var weldedDegree = 0

    /** This cell's logical world position this tick — the centre of its circular **disc gather** footprint
     *  (CytoBiologyCore passive exchange + Import draw matter from grid cells within [logicalRadius] of here).
     *  Set by the reducer alongside [gridIndex]; defaults to origin so a position-less probe still runs. */
    var cx = 0f
    var cy = 0f

    /** Per-tick active-uptake bias (species id → units): an Import gene lowers the cell's effective target for
     *  that species in the passive diffusion junction (CytoBiologyCore.passiveEnvExchange), so it absorbs that
     *  much extra. Recorded grid-free during the (parallel) gene phase, then consumed THIS SAME TICK by the
     *  sequential junction (the reducer runs genes before the junction so the bias is non-empty when read).
     *  Cleared each [reset] — i.e. at the start of the next tick's build. */
    val importBias: MutableMap<Int, Int> = HashMap()

    /** Exchange batch assignment: this cell's environment exchange occurs only when `(tick % EXCHANGE_BATCHES) == exchangeBatch`.
     *  Set once when the cell first appears; persists across ticks. -1 = not yet assigned (assigned on first build). */
    var exchangeBatch: Int = -1

    /** Cached result of `hasConnectionDamage(work)` for the current tick, precomputed in [prefillSpeciesCache].
     *  Avoids iterating over the HashMap once per gene during isActive scanning. */
    var _cachedHasDamage = false
    /** Cached result of `canContract(work)` for the current tick. */
    var _cachedCanContract = false

    /** Cached per-gene active state for round-robin condition evaluation.
     *  When round-robin is enabled, only one gene per tick gets its condition re-evaluated;
     *  the rest use this cached result. Set to false when the genome reference changes. */
    var _cachedActive = BooleanArray(genome.size.coerceAtLeast(1)); private set

    /** Per-work scratch reused by [CytoBiologyCore.runGenes] so a tick's gene execution allocates nothing:
     *  the tick-start cytoplasm snapshot ([snapScratch], filled via [MoleculeStore.copyFrom]), the active-gene
     *  index list ([activeScratch], grown with the genome), and the per-gene consumed-species accumulator
     *  ([consumeIds]/[consumePer], ≤ 3 distinct: BreakBond substrate + Convert/FormBond inputs). All are owned
     *  by this CellWork, which a single thread processes during the (grid-cell-partitioned) gene phase, so
     *  they need no synchronisation. */
    val snapScratch = MoleculeStore()
    var activeScratch = IntArray(genome.size.coerceAtLeast(1)); private set
    /** Per-work scratch for the neighbour diamond-angle exposure sample built in the biology build phase.
     *  Owned by this CellWork so the build phase can run slot-partitioned in parallel without sharing a
     *  single state-level scratch array across workers. */
    val expoScratch = LongArray(CytoExposure.MAX_NEIGHBOURS)
    val consumeIds = IntArray(4)
    val consumePer = IntArray(4)
    /** Species count cache for gate evaluation: species id → count. Pre-populated from [cytoplasm] at the
     *  start of [CytoBiologyCore.runGenes], so gate lookups are O(1) array scans instead of
     *  binary-searching the MoleculeStore (O(log n)). Max 32 entries. */
    private val _speciesCache = IntArray(32)
    private val _speciesCacheIds = IntArray(32)
    private var _speciesCacheSize = 0

    /** Populate the species cache from [cytoplasm]. Call once at the start of runGenes so all gate
      *  evaluations get O(1) lookups. Max 32 entries (most cells have ~5 species). */
    fun prefillSpeciesCache() {
        _speciesCacheSize = 0
        val size = cytoplasm.size
        val limit = if (size < 32) size else 32
        for (i in 0 until limit) {
            _speciesCacheIds[_speciesCacheSize] = cytoplasm.idAt(i)
            _speciesCache[_speciesCacheSize] = cytoplasm.countAt(i)
            _speciesCacheSize++
        }
        // Pre-compute expensive per-gene checks so isActive doesn't iterate HashMaps/compare Fracs per gene.
        _cachedHasDamage = connectionDamage.values.any { it > 0f } || touchingIds.isNotEmpty()
        _cachedCanContract = logicalRadius > CytoTuning.MIN_RADIUS
    }
    /** Get count from pre-populated species cache. Returns 0 for unknown species. */
    fun cachedCount(id: Int): Int {
        val size = _speciesCacheSize
        for (i in 0 until size) if (_speciesCacheIds[i] == id) return _speciesCache[i]
        return 0
    }

    /**
     * Repopulate this pooled instance for a new tick. [connectionDamage] is cleared (the caller refills
     *  it); [handleable] is rebuilt only if [genome] changed reference.
     *
     * Column-slab double-buffer: the caller has assigned [cytoplasm]/[biomass] to point at the
     * column's back-buffer stores. This method copies the column's front buffer into those stores
     * (initializing the back buffer for genes to mutate), then genes mutate in place. At the tick
     * barrier, [CytoCellColumnStore.swapBuffers()] commits by swapping front/back references.
     */
    fun reset(
        cytoplasm: MoleculeStore, biomass: MoleculeStore, logicalRadius: Frac, type: CellType,
        genome: List<Gene>, quanta: Int, touchCount: Int, wear: Int, gridIndex: Int, weldedDegree: Int,
        seed: Int = 0,  // Used to seed the round-robin gene index for new cells
    ) {
        // Column-slab double-buffer: [cytoplasm]/[biomass] are the column's BACK-buffer slot stores
        // (the caller has already copied front → back for this slot). Reference them directly so genes
        // mutate the back buffer in place; CytoCellColumnStore.swapBuffers() commits at the tick barrier.
        this.cytoplasm = cytoplasm
        this.biomass = biomass
        this.logicalRadius = logicalRadius
        this.type = type
        if (genome !== this.genome) {
            this.genome = genome; this.handleable = handleableOf(genome)
            if (activeScratch.size < genome.size) activeScratch = IntArray(genome.size)
            if (_cachedActive.size < genome.size) _cachedActive = BooleanArray(genome.size)
        }
        this.quanta = quanta
        this.touchCount = touchCount
        this.wear = wear
        this.gridIndex = gridIndex
        this.weldedDegree = weldedDegree
        connectionDamage.clear()
        touchingIds.clear()
        internalTouching.clear()
        _touchingCellN = 0
        importBias.clear()
        weldHeals.clear()
        lyseTargets.clear()
        dividing = false
        divideMorphogen = ""
        divideMorphogenToMother = false
        divideAxisMorphogen = ""
        divideAcross = false
        divideRejectMother = false
        repaired = false
        _speciesCacheSize = 0
        // Reset mitosis cooldown when genome changes (new cell or mutation).
        if (genome !== this.genome) {
            this.mitosisCooldown = 0
            // Clear cached active states for the new genome.
            _cachedActive.fill(false)
        }
    }
}

/** Total biomass of a [biomass] map: Σ count × bond-count. Drives cell size and the death threshold. */
fun totalBiomassBonds(biomass: Map<String, Int>): Int {
    var sum = 0
    for ((species, count) in biomass) sum += count * Molecules.bondCount(species)
    return sum
}

/** Total biomass of an id-keyed [biomass] store (the hot-path form of [totalBiomassBonds]). */
fun totalBiomassBonds(biomass: MoleculeStore): Int {
    var sum = 0
    for (i in 0 until biomass.size) sum += biomass.countAt(i) * SpeciesRegistry.bondCount(biomass.idAt(i))
    return sum
}

// ── Preset genomes ───────────────────────────────────────────────────────────
// Seed threshold values live in CytoSeed (initial data — these evolve under mutation); structure below.
private const val GROW_BIOMASS = CytoSeed.AUTOTROPH_GROW_BIOMASS
private const val DIVIDE_BIOMASS = CytoSeed.AUTOTROPH_DIVIDE_BIOMASS

/**
 * The hand-authored **autotroph** (the v1 creature), built around **break-powered division**. Under light
 * it bonds the monomers r+g into `rg` ([FormBond], topping the cytoplasm `rg` reserve up to GROW) and
 * locks `rg` into biomass to grow ([Convert]) while biomass < GROW. Sub-tick interpolation
 * (CytoBiologyCore.selfGateCap) stops each growth gene exactly at GROW rather than overshooting. Division
 * is a *bulk* cost (≈ biomass/4 energy, which a per-tick light flux can't fund), so it's paid by
 * **breaking** the stored `rg`: once biomass > DIVIDE, the BreakBond-powered [Mitosis] (resolved at end of
 * tick) burns ~biomass/4 of the reserve to split. DIVIDE < GROW so the self-capped grower still crosses the
 * divide line; the reserve (held to GROW ≈ 4× the cost) keeps division affordable. r and g are absorbed for
 * free by passive uptake near light. At the live mutation rate (CytoTuning.MUTATION_RATE_DENOM) the first
 * division lands long before any mutation, so the lineage colonises reliably.
 */
val AUTOTROPH_GENES: List<Gene> = listOf(
    Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(DIVIDE_BIOMASS)), GeneAction(ActionType.Mitosis, rejectMother = true)),
    Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(GROW_BIOMASS)), GeneAction(ActionType.Convert, "rg")),
    Gene(EnergySource.Light, GeneCondition(Operand.Chem("rg"), Comparison.Less, Operand.Constant(GROW_BIOMASS)), GeneAction(ActionType.FormBond, "r", "g")),
)

// Heterotroph seed thresholds — values in CytoSeed (initial data; evolve under mutation).
private const val HET_GROW = CytoSeed.HETEROTROPH_GROW_BIOMASS
private const val HET_DIVIDE = CytoSeed.HETEROTROPH_DIVIDE_BIOMASS

/**
 * A hand-authored **heterotroph**: it has no light genes — it lives on `rg` molecules already in its
 * cytoplasm (received by diffusion from autotroph neighbours, or its starter reserve), **breaking** some
 * `rg` to power both converting more `rg` into biomass (grow up to GROW, sub-tick-capped so it stops there
 * instead of overshooting and stranding the reserve) and dividing (Mitosis once biomass > DIVIDE < GROW,
 * funded by breaking the reserve it kept). Closes the food web: autotroph light → `rg` → (diffusion /
 * death) → heterotroph biomass. Starves (and recycles its matter) once the `rg` runs out.
 */
val HETEROTROPH_GENES: List<Gene> = listOf(
    Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(HET_GROW)), GeneAction(ActionType.Convert, "rg")),
    Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(HET_DIVIDE)), GeneAction(ActionType.Mitosis)),
    // Hold together by burning stored 'rg' for repair — a real matter cost; frays once 'rg' runs out.
    Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Chem("rg"), Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),
)

/** The authored preset genome for a cell type — seeds a freshly-spawned cell; afterwards the genome
 *  lives on the cell and is inherited on division, so it can diverge. v1 presets: [Collector] = the
 *  autotroph, [Muscle] = the heterotroph (type names are vestigial labels now); other types start
 *  empty (inert) until authored. */
fun genomeForType(type: CellType): List<Gene> = when (type) {
    CellType.Collector -> AUTOTROPH_GENES
    CellType.Muscle -> HETEROTROPH_GENES
    else -> emptyList()
}
