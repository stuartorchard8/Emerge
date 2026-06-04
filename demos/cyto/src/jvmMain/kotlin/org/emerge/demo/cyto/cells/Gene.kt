package org.emerge.demo.cyto.cells

import kotlin.math.absoluteValue

// 0, C1, XX, XX, $     chem 1 -> contract
// 1, C1, XX, XX, #     chem 1 -> mitosis
// 2, C1, XX, XX, #     chem 1 -> meiosis
// 3, C1, C2, XX, $     chem 1 -> inhibit chem 2
// 4, XX, C2, C3, $     touch -> enzyme C2C3
// 5, C1, C2, C3, #     chem 1 -> enzyme C2C3
// 6, C1, XX, XX, #     chem 1 -> sticky
// 7, C1, XX, XX, $     chem 1 -> reinforce
//
// Absorb?

enum class GeneInputType(val dbIndex: Long, val color: Long) {
  Chem(
    0,
    0x2222C0FF,
  ),
  Touch(
    1,
    0x2222C0FF,
  ),
}

enum class GeneOutputType(val dbIndex: Long, val color: Long, val useThreshold: Boolean) {
  Contract(
    0,
    0xDD3333FF,
    false,
  ),
  Mitosis(
    1,
    0xFFFFFFFF,
    true,
  ),
  Meiosis(
    2,
    0xC022C0FF,
    true,
  ),
  Inhibit(
    3,
    0x606060FF,
    false,
  ),
  Enzyme(
    4,
    0xEFA040FF,
    false,
  ),
  Sticky(
    5,
    0x009900FF,
    true,
  ),
  Reinforce( // TODO This must be expensive
    6,
    0x40EFD0FF,
    false,
  ),
  ;
}

data class GeneInput(
  val type: GeneInputType,
  val chem: String,
  val weight: Float,
)

data class GeneOutput(
  val type: GeneOutputType,
  val chem1: String,
  val chem2: String,
  val bias: Float,
)

class Gene(
  private val inputs: List<GeneInput>,
  private val output: GeneOutput,
) {
  fun act(cell: Cell, delta: Float) {
    val activation = calculateActivation(cell, delta)

    when(output.type) {
      GeneOutputType.Contract -> cell.contraction += activation
      GeneOutputType.Mitosis -> TODO()
      GeneOutputType.Meiosis -> TODO()
      GeneOutputType.Inhibit -> cell.chemicalSuppression[output.chem1] = (cell.chemicalSuppression[output.chem1] ?: 0f) + activation
      GeneOutputType.Enzyme -> if (activation > 0) cell.enzymes.add(Pair(output.chem1, output.chem2))
      GeneOutputType.Sticky -> cell.isStickyTemp = true
      GeneOutputType.Reinforce -> TODO()
    }
  }

  private fun calculateActivation(cell: Cell, delta: Float) : Float {
    return inputs.fold(initial = output.bias) { acc, geneInput ->
      acc + when(geneInput.type) {
        GeneInputType.Chem -> {
          // Consume input chemicals - technically this can over-consume but d/w it's probably fine
          cell.chemicalTransfers[geneInput.chem] = (cell.chemicalTransfers[geneInput.chem] ?: 0f) - (delta*geneInput.weight.absoluteValue)
          (cell.chemicals[geneInput.chem] ?: 0f)*geneInput.weight
        }
        GeneInputType.Touch -> cell.touch*geneInput.weight
      }
    }
  }
}
