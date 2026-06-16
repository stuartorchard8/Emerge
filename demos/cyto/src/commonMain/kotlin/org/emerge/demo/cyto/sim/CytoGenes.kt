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

    /** Break one instance of [bond] (a 2-atom pair, e.g. `"ab"`) in a cytoplasm molecule per op,
     *  releasing its quantum; the molecule splits into two fragments returned to the cytoplasm. */
    data class BreakBond(val bond: String) : EnergySource()
}

/** A binary gate comparator. */
enum class Comparison { Greater, Less }

/** One side of a gene's binary [GeneCondition] — the value fed to the comparator. Either a fixed
 *  [Constant] (the former gate "threshold"), or one of three **live readings** of the cell this tick:
 *  a cytoplasm [Chem] count, total [Biomass], or the [Touching] contact count. Because *both* sides of a
 *  condition are operands, a gene can gate on a relationship between two live quantities — e.g.
 *  `Biomass < Chem("ab")` ("while I'm smaller than my stored `ab` reserve") — not only variable-vs-constant. */
sealed class Operand {
    /** A fixed integer compared against (the former gate threshold). */
    data class Constant(val value: Int) : Operand()

    /** Count of [species] in the cytoplasm (0 for an absent / unknown species). */
    data class Chem(val species: String) : Operand()

    /** Total biomass — Σ count × bond-count (also drives cell size + the death threshold). */
    object Biomass : Operand()

    /** Number of **un-connected** cells this cell is in physical contact with this tick — i.e.
     *  `Touching > 0` fires while the cell is bumping a neighbour it isn't welded to. The matter-model
     *  port of old Cyto's `Touch` gene input (a cell sensing collision pressure); welded neighbours don't
     *  count (they're structure, not a touch event). A reactive, contact-driven gate. */
    object Touching : Operand()
}

/** The one binary gate that turns a gene on/off this tick: `lhs cmp rhs`, each side an [Operand]
 *  (a [Operand.Constant] or a live cell reading). */
data class GeneCondition(val lhs: Operand, val cmp: Comparison, val rhs: Operand)

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
    /** Divide (mitosis); [GeneAction.a]/[GeneAction.b] unused. */
    Mitosis,
    /** Repair connection damage: each op heals the cell's most-damaged connection (operands unused).
     *  Holding a body together is therefore genetic + energy-costing — there is no free heal. */
    Repair,
}

/** A gene's action plus its (action-dependent) operands — single atoms for [ActionType.FormBond],
 *  a species for [ActionType.Import]/[ActionType.Convert], unused for [ActionType.Mitosis]. */
data class GeneAction(val type: ActionType, val a: String = "", val b: String = "")

/**
 * A gene: an energy source, a binary condition, an action — and an **efficiency gear** [efficiency] (g, in
 * `[0, CytoTuning.EFFICIENCY_MAX_GEAR]`). The gear is a per-gene rate↔efficiency trade-off for the
 * *throughput* actions (Convert / Import / Repair only — FormBond is already a lossless energy conversion,
 * and Mitosis is a fixed `biomass/4` bulk event): each energy unit performs `g+1` actions (so higher g =
 * more output per scarce energy), but the energy it may spend this tick is capped at
 * `EFFICIENCY_REF >> g` (so higher g = a lower throughput ceiling). The optimum gear is niche-dependent —
 * energy-poor cells favour high g (squeeze every quantum), energy-rich cells favour low g (burn surplus for
 * raw throughput) — and a low-g gene is *always* less efficient (1 action/energy), even when its high
 * ceiling goes unused, which is the cost that makes high throughput a niche adaptation, not a free bonus.
 */
data class Gene(
    val source: EnergySource,
    val condition: GeneCondition,
    val action: GeneAction,
    val efficiency: Int = 0,
)

/**
 * A cell's metabolic reach — the bonds and atoms its genome can handle — used by **selective uptake**:
 * a cell may only absorb/hold a species *all* of whose bonds (and, for a monomer, whose atom) it can
 * handle. This bounds per-cell species to molecules buildable from the genome's bond-set (≤ a few dozen
 * at the B=5 bond-cap), regardless of how diverse the surrounding environment is.
 */
class Handleable(private val bondMask: Int, private val atomMask: Int) {
    /** Distinct bond-types this genome reaches — capped by CytoTuning.GENOME_MAX_BOND_TYPES so per-cell
     *  species stay bounded (B bonds → ≤ a few dozen buildable molecules). */
    val bondTypeCount: Int get() = bondMask.countOneBits()

    /** Can the cell metabolise (hence absorb/hold) species [id]? A monomer iff its atom is reachable; a
     *  multi-atom molecule iff *every* bond it contains is in the genome's bond-set (the original
     *  string-substring test, recast as a bitmask subset over [SpeciesRegistry]-interned ids). */
    fun canHold(id: Int): Boolean {
        if (id < 0) return false
        return if (SpeciesRegistry.atomCount(id) == 1) (atomMask ushr SpeciesRegistry.firstAtom(id)) and 1 == 1
        else (SpeciesRegistry.bondMask(id) and bondMask.inv()) == 0
    }
}

/** Derive a genome's [Handleable] reach: bonds it forms (FormBond), breaks (BreakBond), or references in
 *  a Convert/Import/ChemQty operand; atoms are the endpoints of those bonds plus any monomer operand.
 *  Bonds/atoms are accumulated as [SpeciesRegistry]-indexed bitmasks (the alphabet is ≤ k=3 atoms, k²=9
 *  bonds, so each fits an Int). */
fun handleableOf(genome: List<Gene>): Handleable {
    var bondMask = 0
    var atomMask = 0
    fun addAtom(c: Char) { val a = SpeciesRegistry.atomIndexOf(c); if (a >= 0) atomMask = atomMask or (1 shl a) }
    fun addBond(pair: String) { val b = SpeciesRegistry.bondIndexOf(pair); if (b >= 0) bondMask = bondMask or (1 shl b) }
    fun addSpecies(s: String) {
        if (s.isEmpty()) return
        for (c in s) addAtom(c)
        for (i in 0 until s.length - 1) addBond(s.substring(i, i + 2))
    }
    for (g in genome) {
        (g.source as? EnergySource.BreakBond)?.let { addSpecies(it.bond) }
        (g.condition.lhs as? Operand.Chem)?.let { addSpecies(it.species) }
        (g.condition.rhs as? Operand.Chem)?.let { addSpecies(it.species) }
        when (g.action.type) {
            ActionType.FormBond -> {
                // Operands are a suffix/prefix the matched molecules carry, so the cell handles their whole
                // atom/bond content plus the junction bond (suffix.last–prefix.first).
                val a = g.action.a; val b = g.action.b
                if (a.isNotEmpty() && b.isNotEmpty()) { addSpecies(a); addSpecies(b); addBond("${a.last()}${b.first()}") }
            }
            ActionType.Convert, ActionType.Import -> addSpecies(g.action.a)
            else -> {}
        }
    }
    return Handleable(bondMask, atomMask)
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

    /** True once a Repair gene healed any connection this tick — gates writing [connectionDamage] back. */
    var repaired = false

    /** Per-work scratch reused by [CytoBiologyCore.runGenes] so a tick's gene execution allocates nothing:
     *  the tick-start cytoplasm snapshot ([snapScratch], filled via [MoleculeStore.copyFrom]), the active-gene
     *  index list ([activeScratch], grown with the genome), and the per-gene consumed-species accumulator
     *  ([consumeIds]/[consumePer], ≤ 3 distinct: BreakBond substrate + Convert/FormBond inputs). All are owned
     *  by this CellWork, which a single thread processes during the (grid-cell-partitioned) gene phase, so
     *  they need no synchronisation. */
    val snapScratch = MoleculeStore()
    var activeScratch = IntArray(genome.size.coerceAtLeast(1)); private set
    val consumeIds = IntArray(4)
    val consumePer = IntArray(4)

    /** Repopulate this pooled instance for a new tick. [connectionDamage] is cleared (the caller refills
     *  it); [handleable] is rebuilt only if [genome] changed reference. */
    fun reset(
        cytoplasm: MoleculeStore, biomass: MoleculeStore, logicalRadius: Frac, type: CellType,
        genome: List<Gene>, quanta: Int, touchCount: Int, wear: Int, gridIndex: Int,
    ) {
        this.cytoplasm = cytoplasm
        this.biomass = biomass
        this.logicalRadius = logicalRadius
        this.type = type
        if (genome !== this.genome) {
            this.genome = genome; this.handleable = handleableOf(genome)
            if (activeScratch.size < genome.size) activeScratch = IntArray(genome.size)
        }
        this.quanta = quanta
        this.touchCount = touchCount
        this.wear = wear
        this.gridIndex = gridIndex
        connectionDamage.clear()
        dividing = false
        repaired = false
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
 * it bonds the monomers a+b into `ab` ([FormBond], topping the cytoplasm `ab` reserve up to GROW) and
 * locks `ab` into biomass to grow ([Convert]) while biomass < GROW. Sub-tick interpolation
 * (CytoBiologyCore.selfGateCap) stops each growth gene exactly at GROW rather than overshooting. Division
 * is a *bulk* cost (≈ biomass/4 energy, which a per-tick light flux can't fund), so it's paid by
 * **breaking** the stored `ab`: once biomass > DIVIDE, the BreakBond-powered [Mitosis] (resolved at end of
 * tick) burns ~biomass/4 of the reserve to split. DIVIDE < GROW so the self-capped grower still crosses the
 * divide line; the reserve (held to GROW ≈ 4× the cost) keeps division affordable. a and b are absorbed for
 * free by passive uptake near light. At the live mutation rate (CytoTuning.MUTATION_RATE_DENOM) the first
 * division lands long before any mutation, so the lineage colonises reliably.
 */
val AUTOTROPH_GENES: List<Gene> = listOf(
    Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(DIVIDE_BIOMASS)), GeneAction(ActionType.Mitosis)),
    Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(GROW_BIOMASS)), GeneAction(ActionType.Convert, "ab")),
    Gene(EnergySource.Light, GeneCondition(Operand.Chem("ab"), Comparison.Less, Operand.Constant(GROW_BIOMASS)), GeneAction(ActionType.FormBond, "a", "b")),
)

// Heterotroph seed thresholds — values in CytoSeed (initial data; evolve under mutation).
private const val HET_GROW = CytoSeed.HETEROTROPH_GROW_BIOMASS
private const val HET_DIVIDE = CytoSeed.HETEROTROPH_DIVIDE_BIOMASS

/**
 * A hand-authored **heterotroph**: it has no light genes — it lives on `ab` molecules already in its
 * cytoplasm (received by diffusion from autotroph neighbours, or its starter reserve), **breaking** some
 * `ab` to power both converting more `ab` into biomass (grow up to GROW, sub-tick-capped so it stops there
 * instead of overshooting and stranding the reserve) and dividing (Mitosis once biomass > DIVIDE < GROW,
 * funded by breaking the reserve it kept). Closes the food web: autotroph light → `ab` → (diffusion /
 * death) → heterotroph biomass. Starves (and recycles its matter) once the `ab` runs out.
 */
val HETEROTROPH_GENES: List<Gene> = listOf(
    Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(HET_GROW)), GeneAction(ActionType.Convert, "ab")),
    Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(HET_DIVIDE)), GeneAction(ActionType.Mitosis)),
    // Hold together by burning stored 'ab' for repair — a real matter cost; frays once 'ab' runs out.
    Gene(EnergySource.BreakBond("ab"), GeneCondition(Operand.Chem("ab"), Comparison.Greater, Operand.Constant(0)), GeneAction(ActionType.Repair)),
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
