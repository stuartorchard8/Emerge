package org.emerge.demo.cyto.environment

/**
 * Grid-bucketed chemical reservoir. Ported from Cyto; the debug-overlay drawing
 * (which depended on the bespoke UI toolkit) is dropped. The core add/remove ops
 * are kept verbatim so a later system can wire diffusion to/from the environment.
 */
class ResourceGrid(
  private val cellSize: Float = 100f,
  private val gridCells: MutableMap<Int, MutableMap<Int, MutableMap<String, Float>>> = mutableMapOf()
) {
  fun add(worldX: Float, worldY: Float, chemicals: Map<String, Float>) {
    val x = (worldX/cellSize).toInt()
    val y = (worldY/cellSize).toInt()

    val column = gridCells[x]
    if (column == null) {
      gridCells[x] = mutableMapOf(Pair(y, chemicals.toMutableMap()))
      return
    }

    val gridCell = column[y]
    if (gridCell == null) {
      column[y] = chemicals.toMutableMap()
    } else {
      for (chemical in chemicals) {
        gridCell.merge(chemical.key, chemical.value) { a, b -> a + b }
      }
    }
  }

  fun remove(worldX: Float, worldY: Float, amount: Float) : Map<String, Float>? {
    val x = (worldX/cellSize).toInt()
    val y = (worldY/cellSize).toInt()

    val gridCell = gridCells[x]?.get(y)
    if (gridCell == null) {
      return null
    } else {
      val gridCellTotal = gridCell.values.sum()
      if (gridCellTotal <= amount) {
        return gridCells[x]?.remove(y)
      }
      val removeRatio = amount/gridCellTotal
      val keepRatio = 1f-removeRatio
      val chemicalsToTake = gridCell.mapValues { it.value*removeRatio }
      gridCell.replaceAll { _, v -> v*keepRatio }
      return chemicalsToTake
    }
  }
}
