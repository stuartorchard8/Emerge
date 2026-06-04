package org.emerge.demo.cyto.cells

// A+B <=> C
data class ChemicalReaction(
  val catalyst: Pair<String, String>,
  var chemA: Float = 0f,
  var chemB: Float = 0f,
  var chemC: Float = 0f,
)
