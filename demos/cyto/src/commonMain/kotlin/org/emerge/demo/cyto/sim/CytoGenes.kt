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

/** What a gene's binary condition tests. */
enum class ConditionType {
    /** Count of [GeneCondition.species] in the cytoplasm ≷ [GeneCondition.threshold]. */
    ChemQty,
    /** Total biomass (Σ count × bond-count) ≷ [GeneCondition.threshold]; [GeneCondition.species] ignored. */
    Biomass,
    /** Number of **un-connected** cells this cell is in physical contact with this tick ≷
     *  [GeneCondition.threshold] ([GeneCondition.species] ignored) — i.e. `Touching > 0` fires while the
     *  cell is bumping a neighbour it isn't welded to. The matter-model port of old Cyto's `Touch` gene
     *  input (a cell sensing collision pressure); welded neighbours don't count (they're structure, not
     *  a touch event). A reactive, contact-driven gate — e.g. divide / secrete / grip on contact. */
    Touching,
}

/** The one binary gate that turns a gene on/off this tick. */
data class GeneCondition(val type: ConditionType, val species: String, val cmp: Comparison, val threshold: Int)

/** The single action a gene performs (v1 set). */
enum class ActionType {
    /** Move molecules of [GeneAction.a] from the local environment into the cytoplasm. */
    Import,
    /** Join a cytoplasm molecule ending in atom [GeneAction.a] with one starting in atom [GeneAction.b]. */
    FormBond,
    /** Lock molecules of [GeneAction.a] from cytoplasm into biomass (structure → size). */
    Convert,
    /** Push the cell's radius **above** its biomass baseline (operands unused). Each op nudges the
     *  radius out by a fixed step up to a flex limit; the per-tick elastic relaxation toward the
     *  biomass-derived size (see [CytoBiologyCore.finish]) pulls it back when the gene stops firing, so
     *  it behaves like an actively-held muscle. The actuator a locomotion driver flexes against the
     *  asymmetric surface drag. */
    Expand,
    /** Push the cell's radius **below** its biomass baseline, down to [MIN_RADIUS] (operands unused).
     *  The contractile counterpart of [Expand]; same elastic relaxation back to baseline. */
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

data class Gene(val source: EnergySource, val condition: GeneCondition, val action: GeneAction)

/**
 * A cell's metabolic reach — the bonds and atoms its genome can handle — used by **selective uptake**:
 * a cell may only absorb/hold a species *all* of whose bonds (and, for a monomer, whose atom) it can
 * handle. This bounds per-cell species to molecules buildable from the genome's bond-set (≤ a few dozen
 * at the B=5 bond-cap), regardless of how diverse the surrounding environment is.
 */
class Handleable(private val bonds: Set<String>, private val atoms: Set<Char>) {
    /** Distinct bond-types this genome reaches — capped by CytoTuning.GENOME_MAX_BOND_TYPES so per-cell
     *  species stay bounded (B bonds → ≤ a few dozen buildable molecules). */
    val bondTypeCount: Int get() = bonds.size

    fun canHold(species: String): Boolean {
        if (species.isEmpty()) return false
        if (species.length == 1) return species[0] in atoms
        for (i in 0 until species.length - 1) if (species.substring(i, i + 2) !in bonds) return false
        return true
    }
}

/** Derive a genome's [Handleable] reach: bonds it forms (FormBond), breaks (BreakBond), or references in
 *  a Convert/Import/ChemQty operand; atoms are the endpoints of those bonds plus any monomer operand. */
fun handleableOf(genome: List<Gene>): Handleable {
    val bonds = HashSet<String>()
    val atoms = HashSet<Char>()
    fun addSpecies(s: String) {
        if (s.isEmpty()) return
        for (c in s) atoms.add(c)
        for (i in 0 until s.length - 1) bonds.add(s.substring(i, i + 2))
    }
    for (g in genome) {
        (g.source as? EnergySource.BreakBond)?.let { addSpecies(it.bond) }
        if (g.condition.type == ConditionType.ChemQty) addSpecies(g.condition.species)
        when (g.action.type) {
            ActionType.FormBond -> {
                val a = g.action.a; val b = g.action.b
                if (a.isNotEmpty() && b.isNotEmpty()) { atoms.add(a[0]); atoms.add(b[0]); bonds.add("${a[0]}${b[0]}") }
            }
            ActionType.Convert, ActionType.Import -> addSpecies(g.action.a)
            else -> {}
        }
    }
    return Handleable(bonds, atoms)
}

/**
 * Mutable per-tick working state for one cell. Matter lives in two integer count maps: mobile
 * [cytoplasm] (genes act on it; diffuses to neighbours) and locked [biomass] (structure; sets size).
 * [quanta] is this tick's energy budget (from light × exposure); [wear] is the degradation accumulator
 * carried across ticks. No `Frac` chemistry, no energy pool.
 */
class CellWork(
    val cytoplasm: MutableMap<String, Int>,
    val biomass: MutableMap<String, Int>,
    var logicalRadius: Frac,
    val type: CellType,
    val genome: List<Gene>,
    /** Light energy available to the cell this tick (1 quantum = 1 op). Each active light gene gets a
     *  1/N share of it (the bloat tax — see CytoBiologyCore.runGenes), not the whole pool. */
    var quanta: Int,
    /** Number of un-connected cells this cell is in contact with this tick (the [ConditionType.Touching]
     *  gate reads it). Transient — recomputed from the physics contacts each tick, never persisted. */
    val touchCount: Int,
    /** Degradation accumulator carried across ticks (gains total-biomass-bonds each tick). */
    var wear: Int,
    /** The environment matter-grid cell this cell sits in (where Import draws / death deposits). -1 if
     *  the cell has no position. */
    val gridIndex: Int,
    /** This cell's per-connection stress damage (neighbour → damage), seeded from its
     *  [ConnectionStateComponent]. A Repair gene op heals the most-damaged entry; the biology system
     *  writes the (possibly healed) map back, and connection maintenance accrues stress on top. */
    val connectionDamage: MutableMap<EntityId, Float>,
) {
    /** What this cell can metabolise (hence absorb/hold), derived once from its genome — selective uptake
     *  gates passive exchange + diffusion on this so per-cell species stay bounded (see [handleableOf]). */
    val handleable: Handleable = handleableOf(genome)

    /** Set true by a fired Mitosis gene; the lifecycle splits the cell. */
    var dividing = false

    /** True once a Repair gene healed any connection this tick — gates writing [connectionDamage] back. */
    var repaired = false
}

/** Total biomass of a [biomass] map: Σ count × bond-count. Drives cell size and the death threshold. */
fun totalBiomassBonds(biomass: Map<String, Int>): Int {
    var sum = 0
    for ((species, count) in biomass) sum += count * Molecules.bondCount(species)
    return sum
}

// ── Preset genomes ───────────────────────────────────────────────────────────
// Seed threshold values live in CytoSeed (initial data — these evolve under mutation); structure below.
private const val LEAK_RESERVE = CytoSeed.AUTOTROPH_LEAK_RESERVE
private const val DIVIDE_BIOMASS = CytoSeed.AUTOTROPH_DIVIDE_BIOMASS

/**
 * The hand-authored **light-only autotroph** (the v1 creature). It absorbs the monomers a and b for
 * free by passive uptake near a light source (no Import gene needed), bonds them into `ab` under light,
 * locks most `ab` into biomass to grow, and divides. It keeps a small cytoplasm `ab` reserve which
 * **leaks** to the environment (down-gradient, free — like root exudate), feeding heterotrophs. A
 * clonal colony grows then **plateaus** as the local a/b is drawn down (the matter carrying capacity).
 */
val AUTOTROPH_GENES: List<Gene> = listOf(
    Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "a", Comparison.Greater, 0), GeneAction(ActionType.FormBond, "a", "b")),
    Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "ab", Comparison.Greater, LEAK_RESERVE), GeneAction(ActionType.Convert, "ab")),
    Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, DIVIDE_BIOMASS), GeneAction(ActionType.Mitosis)),
    // Hold the colony together: light-powered connection repair (cheap where autotrophs live).
    Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, 0), GeneAction(ActionType.Repair)),
)

// Heterotroph seed thresholds — values in CytoSeed (initial data; evolve under mutation).
private const val HET_RESERVE = CytoSeed.HETEROTROPH_RESERVE
private const val HET_DIVIDE = CytoSeed.HETEROTROPH_DIVIDE_BIOMASS

/**
 * A hand-authored **heterotroph**: it has no light genes — it lives on `ab` molecules already in its
 * cytoplasm (received by diffusion from autotroph neighbours, or its starter reserve), breaking some
 * for energy to power converting the rest into biomass and dividing. Closes the food web: autotroph
 * light → `ab` → (diffusion / death) → heterotroph biomass. Starves (and recycles its matter) once the
 * `ab` runs out.
 */
val HETEROTROPH_GENES: List<Gene> = listOf(
    Gene(EnergySource.BreakBond("ab"), GeneCondition(ConditionType.ChemQty, "ab", Comparison.Greater, HET_RESERVE), GeneAction(ActionType.Convert, "ab")),
    Gene(EnergySource.BreakBond("ab"), GeneCondition(ConditionType.Biomass, "", Comparison.Greater, HET_DIVIDE), GeneAction(ActionType.Mitosis)),
    // Hold together by burning stored 'ab' for repair — a real matter cost; frays once 'ab' runs out.
    Gene(EnergySource.BreakBond("ab"), GeneCondition(ConditionType.ChemQty, "ab", Comparison.Greater, 0), GeneAction(ActionType.Repair)),
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
