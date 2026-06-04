package org.emerge.demo.cyto.cells

import com.badlogic.gdx.math.Vector2

/**
 * Plain serialisable snapshot of a cell. Ported from Cyto's `db.CellData`; the
 * SQLDelight backing is dropped in favour of [org.emerge.demo.cyto.CytoSaveCodec].
 */
data class CellData(
  val position: Vector2,
  val linearVelocity: Vector2 = Vector2.Zero,
  val chemicals: Map<String, Float> = mapOf(
    Pair("energy", 1f),
  ),
  val direction: Float = 0f,
  val spin: Float = 0f,
  val radius: Float = 0f,
  val type: CellType = CellType.Blank,
  val id: Long? = null,
)
