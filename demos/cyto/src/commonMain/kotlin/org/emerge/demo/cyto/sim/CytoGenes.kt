package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.physics.primitives.Frac

/**
 * Gene model, ported from Cyto's `Gene.kt`. Genes read chemicals / touch / environmental light and
 * emit outputs. A cell's **genome** (its [Gene] list) is carried per-cell and inherited on division,
 * not looked up from the cell type; [genomeForType] supplies the authored preset at spawn.
 *
 * Chemistry runs in fixed-point [Frac] (value 1.0 = a "full" cell, the old `MAX_CHEM`). Frac×Frac
 * overflows once the product of the two *values* exceeds ~2, so concentrations live in [0,1] and the
 * old `[0,10]` magnitudes were rescaled ÷10 (gate 5→0.5, etc.) — same dynamics, Frac-safe range.
 */
enum class GeneInputType { Chem, Touch, Light }
enum class GeneOutputType { Contract, Mitosis, Meiosis, Inhibit, Enzyme, Sticky, Reinforce, Secrete }

data class GeneInput(val type: GeneInputType, val chem: String, val weight: Frac)
data class GeneOutput(val type: GeneOutputType, val chem1: String, val chem2: String, val bias: Frac)
class Gene(val inputs: List<GeneInput>, val output: GeneOutput)

private val ZERO = Frac(0, 1)

/**
 * Mutable per-tick working state for one cell. `transfers` accumulates this tick's outgoing chemical
 * changes (applied next tick); `suppression` accumulates across ticks as in the original.
 */
class CellWork(
    val chemicals: MutableMap<String, Frac>,
    val transfers: MutableMap<String, Frac>,
    initialSuppression: Map<String, Frac>,
    var touch: Frac,
    var logicalRadius: Frac,
    var divideCharge: Frac,
    val type: CellType,
    val genome: List<Gene>,
    /** Environmental light at this cell's position this tick (field × exposure, sampled before the
     *  gene pass). Read-only input — a Collector gene turns it into energy. */
    val light: Frac = ZERO,
) {
    var contraction = ZERO
    val enzymes = mutableSetOf<Pair<String, String>>()
    var isStickyTemp = false
    var dividing = false

    private val initialSuppression: Map<String, Frac> = initialSuppression
    private var suppressionOverride: MutableMap<String, Frac>? = null
    val suppression: Map<String, Frac> get() = suppressionOverride ?: initialSuppression
    fun inhibit(chem: String, amount: Frac) {
        val m = suppressionOverride ?: HashMap(initialSuppression).also { suppressionOverride = it }
        m[chem] = (m[chem] ?: ZERO) + amount
    }
}

private fun Gene.evaluate(cell: CellWork, delta: Frac) {
    var activation = output.bias
    for (input in inputs) {
        activation += when (input.type) {
            GeneInputType.Chem -> {
                // reading a chemical consumes a little of it (rate ∝ weight); ÷10 keeps the original
                // dynamics under the [0,1] rescale (chemicals were [0,10] before).
                cell.transfers[input.chem] = (cell.transfers[input.chem] ?: ZERO) - (delta * Frac.abs(input.weight)).div(10)
                (cell.chemicals[input.chem] ?: ZERO) * input.weight
            }
            GeneInputType.Touch -> cell.touch * input.weight
            GeneInputType.Light -> cell.light * input.weight
        }
    }
    when (output.type) {
        GeneOutputType.Contract -> cell.contraction += activation
        GeneOutputType.Inhibit -> cell.inhibit(output.chem1, activation)
        GeneOutputType.Enzyme -> if (activation.sign > 0) cell.enzymes.add(Pair(output.chem1, output.chem2))
        GeneOutputType.Sticky -> cell.isStickyTemp = true
        // Mitosis is a charge-accumulating EVENT: activation is a rate of division progress;
        // [CytoBiologyCore.act] fires the split + resets the charge at threshold.
        GeneOutputType.Mitosis -> cell.divideCharge += activation
        // Secrete: produce chem1 at rate = activation (a Collector turns light → energy).
        GeneOutputType.Secrete -> cell.transfers[output.chem1] = (cell.transfers[output.chem1] ?: ZERO) + activation
        GeneOutputType.Meiosis, GeneOutputType.Reinforce -> Unit
    }
}

/** Division charge a Mitosis gene must accrue to split (resets to 0 after). A counter — only added /
 *  compared, never multiplied — so it may exceed 1.0; rescaled ÷10 from the old 200 (activation, which
 *  feeds it, is now ÷10 smaller). */
val DIVIDE_THRESHOLD = Frac(20, 1)

/** Stem mitosis gate: charge accrues only while energy exceeds this (0.5 = a half-full surplus). */
val STEM_MITOSIS_ENERGY_GATE = Frac(1, 2)

fun runGenes(cell: CellWork, delta: Frac) {
    for (gene in cell.genome) gene.evaluate(cell, delta)
}

// Preset genomes — immutable, shared by every cell spawned as a given type.
private val MUSCLE_GENES = listOf(
    Gene(listOf(GeneInput(GeneInputType.Chem, "e", Frac(1, 1))), GeneOutput(GeneOutputType.Contract, "", "", ZERO)),
)
private val NOT_GENES = listOf(
    Gene(listOf(GeneInput(GeneInputType.Chem, "e", Frac(-1, 1))), GeneOutput(GeneOutputType.Contract, "", "", Frac(1, 1))),
)
private val JUMP_GENES = listOf(
    Gene(emptyList(), GeneOutput(GeneOutputType.Contract, "", "", Frac(1, 1))),
)
private val TOUCH_GENES = listOf(
    Gene(listOf(GeneInput(GeneInputType.Touch, "", Frac(1, 1))), GeneOutput(GeneOutputType.Enzyme, "e", "n", ZERO)),
)
// Stem: divide on an energy surplus. activation = energy − gate, so charge accrues only while
// energy > gate; the split halves energy → division is self-bounding by the energy economy.
private val STEM_GENES = listOf(
    Gene(listOf(GeneInput(GeneInputType.Chem, "energy", Frac(1, 1))), GeneOutput(GeneOutputType.Mitosis, "", "", -STEM_MITOSIS_ENERGY_GATE)),
)
// Collector: turn environmental light into energy (no free lunch — output scales with local light).
private val COLLECTOR_GENES = listOf(
    Gene(listOf(GeneInput(GeneInputType.Light, "", Frac(1, 1))), GeneOutput(GeneOutputType.Secrete, "energy", "", ZERO)),
)

/** The authored preset genome for a legacy cell type — seeds a freshly-spawned cell; afterwards the
 *  genome lives on the cell and is inherited on division, so it can diverge. */
fun genomeForType(type: CellType): List<Gene> = when (type) {
    CellType.Muscle -> MUSCLE_GENES
    CellType.Not -> NOT_GENES
    CellType.Jump -> JUMP_GENES
    CellType.Touch -> TOUCH_GENES
    CellType.Stem -> STEM_GENES
    CellType.Collector -> COLLECTOR_GENES
    else -> emptyList()
}
