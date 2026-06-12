package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
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
    /** Divide (mitosis); [GeneAction.a]/[GeneAction.b] unused. */
    Mitosis,
}

/** A gene's action plus its (action-dependent) operands — single atoms for [ActionType.FormBond],
 *  a species for [ActionType.Import]/[ActionType.Convert], unused for [ActionType.Mitosis]. */
data class GeneAction(val type: ActionType, val a: String = "", val b: String = "")

data class Gene(val source: EnergySource, val condition: GeneCondition, val action: GeneAction)

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
    /** Energy quanta available this tick (1 quantum = 1 op), spent down as genes act. */
    var quanta: Int,
    /** Degradation accumulator carried across ticks (gains total-biomass-bonds each tick). */
    var wear: Int,
    /** The environment matter-grid cell this cell sits in (where Import draws / death deposits). -1 if
     *  the cell has no position. */
    val gridIndex: Int,
) {
    /** Set true by a fired Mitosis gene; the lifecycle splits the cell. */
    var dividing = false
}

/** Total biomass of a [biomass] map: Σ count × bond-count. Drives cell size and the death threshold. */
fun totalBiomassBonds(biomass: Map<String, Int>): Int {
    var sum = 0
    for ((species, count) in biomass) sum += count * Molecules.bondCount(species)
    return sum
}

// ── Preset genomes ───────────────────────────────────────────────────────────
// Tunable knobs (MORPHOGENESIS §v1 spec): authoring thresholds for the autotroph.
private const val STOCK_TARGET = 4     // import a/b until the cytoplasm holds this many
private const val DIVIDE_BIOMASS = 8   // divide once biomass reaches this many bonds

/**
 * The hand-authored **light-only autotroph** (the v1 test creature): import the monomers a and b,
 * bond them into `ab` (light-powered), lock `ab` into biomass to grow, and divide once big enough.
 * A clonal colony of these grows and then **plateaus** as the local environment's a/b is drawn down —
 * the matter carrying capacity.
 */
val AUTOTROPH_GENES: List<Gene> = listOf(
    Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "a", Comparison.Less, STOCK_TARGET), GeneAction(ActionType.Import, "a")),
    Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "b", Comparison.Less, STOCK_TARGET), GeneAction(ActionType.Import, "b")),
    Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "a", Comparison.Greater, 0), GeneAction(ActionType.FormBond, "a", "b")),
    Gene(EnergySource.Light, GeneCondition(ConditionType.ChemQty, "ab", Comparison.Greater, 0), GeneAction(ActionType.Convert, "ab")),
    Gene(EnergySource.Light, GeneCondition(ConditionType.Biomass, "", Comparison.Greater, DIVIDE_BIOMASS), GeneAction(ActionType.Mitosis)),
)

// Heterotroph knobs.
private const val HET_RESERVE = 2      // keep this much cytoplasm 'ab' as an energy reserve
private const val HET_DIVIDE = 8       // divide once biomass reaches this many bonds

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
