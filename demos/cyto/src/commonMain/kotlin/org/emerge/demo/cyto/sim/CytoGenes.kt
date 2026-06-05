package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType

/**
 * Gene model, ported from Cyto's `Gene.kt` (the gdx-bound `Gene.act(Cell)` is replaced by
 * [evaluate] operating on a mutable [CellWork]). Genes read chemicals/touch and emit
 * outputs (contract, inhibit, enzyme, sticky). Mitosis/Meiosis/Reinforce were `TODO()` in
 * the original and remain no-ops.
 */
enum class GeneInputType { Chem, Touch }
enum class GeneOutputType { Contract, Mitosis, Meiosis, Inhibit, Enzyme, Sticky, Reinforce }

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
    var divideCooldown: Float,
    val type: CellType,
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
        }
    }
    when (output.type) {
        GeneOutputType.Contract -> cell.contraction += activation
        GeneOutputType.Inhibit -> cell.inhibit(output.chem1, activation)
        GeneOutputType.Enzyme -> if (activation > 0f) cell.enzymes.add(Pair(output.chem1, output.chem2))
        GeneOutputType.Sticky -> cell.isStickyTemp = true
        GeneOutputType.Mitosis, GeneOutputType.Meiosis, GeneOutputType.Reinforce -> Unit
    }
}

fun runGenes(cell: CellWork, delta: Float) {
    val genes = genesForType(cell.type)
    for (gene in genes) gene.evaluate(cell, delta)
}

// Gene sets are immutable and identical for every cell of a type — define them once rather
// than rebuilding the list (and its Gene/GeneInput/GeneOutput objects) on every per-cell,
// per-tick call.
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

fun genesForType(type: CellType): List<Gene> = when (type) {
    CellType.Muscle -> MUSCLE_GENES
    CellType.Not -> NOT_GENES
    CellType.Jump -> JUMP_GENES
    CellType.Touch -> TOUCH_GENES
    else -> emptyList()
}
