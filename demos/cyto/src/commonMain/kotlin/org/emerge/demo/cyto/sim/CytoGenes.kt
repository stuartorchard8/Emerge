package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType

/**
 * Gene model, ported from Cyto's `Gene.kt` (the gdx-bound `Gene.act(Cell)` is replaced by
 * [evaluate] operating on a mutable [CellWork]). Genes read chemicals/touch and emit
 * outputs (contract, inhibit, enzyme, sticky). Mitosis/Meiosis/Reinforce were `TODO()` in
 * the original and remain no-ops.
 *
 * A cell's **genome** is its ordered list of [Gene]s — carried per-cell ([CellWork.genome],
 * [CytoCellComponent.genome]) and inherited clonally on division, *not* looked up from the cell
 * type. [genomeForType] supplies the authored preset for each legacy type at spawn, so the
 * library of hand-built "types" still works, but the substrate is now data-driven and fertile
 * for mutation/selection. (The energy/division **economy** — Support's surplus, Stem's split —
 * stays keyed on cell type so empty-genome colonies keep the dense fast path; only the reactive
 * gene network is genome-driven here.)
 */
enum class GeneInputType { Chem, Touch, Light }
enum class GeneOutputType { Contract, Mitosis, Meiosis, Inhibit, Enzyme, Sticky, Reinforce, Secrete }

data class GeneInput(val type: GeneInputType, val chem: String, val weight: Float)
data class GeneOutput(val type: GeneOutputType, val chem1: String, val chem2: String, val bias: Float)
class Gene(val inputs: List<GeneInput>, val output: GeneOutput)

/**
 * Mutable per-tick working state for one cell, used by the biology system. `transfers`
 * accumulates this tick's outgoing chemical changes (applied next tick); `suppression`
 * persists/accumulates across ticks as in the original.
 */
class CellWork(
    val chemicals: MutableMap<String, Float>,
    val transfers: MutableMap<String, Float>,
    initialSuppression: Map<String, Float>,
    var touch: Float,
    var logicalRadius: Float,
    var divideCharge: Float,
    val type: CellType,
    val genome: List<Gene>,
    /** Environmental light at this cell's position this tick (sampled from [CytoLightField] before
     *  the gene pass). Read-only input — a Collector gene turns it into energy. */
    val light: Float = 0f,
) {
    var contraction = 0f
    val enzymes = mutableSetOf<Pair<String, String>>()
    var isStickyTemp = false
    var dividing = false

    // Suppression is read every tick but written only by an Inhibit gene (currently none
    // exist). Share the source map read-only and copy-on-write on the first [inhibit], so the
    // common case allocates nothing instead of cloning an (almost always empty) map per cell.
    private val initialSuppression: Map<String, Float> = initialSuppression
    private var suppressionOverride: MutableMap<String, Float>? = null
    val suppression: Map<String, Float> get() = suppressionOverride ?: initialSuppression
    fun inhibit(chem: String, amount: Float) {
        val m = suppressionOverride ?: HashMap(initialSuppression).also { suppressionOverride = it }
        m[chem] = (m[chem] ?: 0f) + amount
    }
}

private fun Gene.evaluate(cell: CellWork, delta: Float) {
    val activation = inputs.fold(output.bias) { acc, input ->
        acc + when (input.type) {
            GeneInputType.Chem -> {
                // Consume input chemicals (may over-consume; matches original).
                cell.transfers[input.chem] = (cell.transfers[input.chem] ?: 0f) - (delta * kotlin.math.abs(input.weight))
                (cell.chemicals[input.chem] ?: 0f) * input.weight
            }
            GeneInputType.Touch -> cell.touch * input.weight
            // Environmental light — a free input (not a chemical the cell holds, so nothing consumed).
            GeneInputType.Light -> cell.light * input.weight
        }
    }
    when (output.type) {
        GeneOutputType.Contract -> cell.contraction += activation
        GeneOutputType.Inhibit -> cell.inhibit(output.chem1, activation)
        GeneOutputType.Enzyme -> if (activation > 0f) cell.enzymes.add(Pair(output.chem1, output.chem2))
        GeneOutputType.Sticky -> cell.isStickyTemp = true
        // Mitosis is a charge-accumulating EVENT (not a per-tick effect): the activation is a rate of
        // division progress. [CytoBiologyCore.act] fires the split + resets the charge at threshold.
        // The input chemical is consumed during warm-up (above), so a finite substrate bounds division.
        GeneOutputType.Mitosis -> cell.divideCharge += activation
        // Secrete: produce chem1 at rate = activation (a Collector turns light → energy). Goes through
        // transfers like any chemical change, applied next tick + clamped to MAX_CHEM.
        GeneOutputType.Secrete -> cell.transfers[output.chem1] = (cell.transfers[output.chem1] ?: 0f) + activation
        GeneOutputType.Meiosis, GeneOutputType.Reinforce -> Unit
    }
}

/** Division progress needed for a [GeneOutputType.Mitosis] gene to trigger one split (the warm-up
 *  threshold; charge resets to 0 after dividing). */
const val DIVIDE_THRESHOLD = 200f

/** The Stem preset's mitosis gate: it only accrues division charge while energy exceeds this, so a
 *  Stem divides only with an energy surplus (a developmental genome gates Mitosis on a morphogen
 *  instead). */
const val STEM_MITOSIS_ENERGY_GATE = 5f

fun runGenes(cell: CellWork, delta: Float) {
    for (gene in cell.genome) gene.evaluate(cell, delta)
}

// Preset genomes are immutable and shared by every cell spawned as a given type — define them
// once rather than rebuilding the list (and its Gene/GeneInput/GeneOutput objects) per spawn.
private val MUSCLE_GENES = listOf(
    Gene(
        inputs = listOf(GeneInput(GeneInputType.Chem, chem = "e", weight = 1f)),
        output = GeneOutput(GeneOutputType.Contract, chem1 = "", chem2 = "", bias = 0f),
    ),
)
private val NOT_GENES = listOf(
    Gene(
        inputs = listOf(GeneInput(GeneInputType.Chem, chem = "e", weight = -1f)),
        output = GeneOutput(GeneOutputType.Contract, chem1 = "", chem2 = "", bias = 1f),
    ),
)
private val JUMP_GENES = listOf(
    Gene(
        inputs = emptyList(),
        output = GeneOutput(GeneOutputType.Contract, chem1 = "", chem2 = "", bias = 1f),
    ),
)
private val TOUCH_GENES = listOf(
    Gene(
        inputs = listOf(GeneInput(GeneInputType.Touch, chem = "", weight = 1f)),
        output = GeneOutput(GeneOutputType.Enzyme, chem1 = "e", chem2 = "n", bias = 0f),
    ),
)
// Stem: divide on an energy surplus. activation = energy − gate, so charge only accrues while
// energy > gate (and faster the larger the surplus); energy is consumed as it warms up, and the
// split halves energy — so division is self-bounding by the energy economy.
private val STEM_GENES = listOf(
    Gene(
        inputs = listOf(GeneInput(GeneInputType.Chem, chem = "energy", weight = 1f)),
        output = GeneOutput(GeneOutputType.Mitosis, chem1 = "", chem2 = "", bias = -STEM_MITOSIS_ENERGY_GATE),
    ),
)
// Collector: turn environmental light into energy (photosynthesis). No free lunch — output scales
// with the light at the cell's position, so a Collector only feeds a colony while it sits in the
// light. This is the gene-driven replacement for the hardcoded Support "+5 from nothing".
private val COLLECTOR_GENES = listOf(
    Gene(
        inputs = listOf(GeneInput(GeneInputType.Light, chem = "", weight = 1f)),
        output = GeneOutput(GeneOutputType.Secrete, chem1 = "energy", chem2 = "", bias = 0f),
    ),
)

/** The authored preset genome for a legacy cell type — used to seed a freshly-spawned cell.
 *  After spawn the genome lives on the cell and is inherited on division, so it can diverge. */
fun genomeForType(type: CellType): List<Gene> = when (type) {
    CellType.Muscle -> MUSCLE_GENES
    CellType.Not -> NOT_GENES
    CellType.Jump -> JUMP_GENES
    CellType.Touch -> TOUCH_GENES
    CellType.Stem -> STEM_GENES
    CellType.Collector -> COLLECTOR_GENES
    else -> emptyList()
}
