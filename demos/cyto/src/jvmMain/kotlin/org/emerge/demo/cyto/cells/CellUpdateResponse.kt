package org.emerge.demo.cyto.cells

import ktx.collections.GdxArray

data class CellUpdateResponse(
  val newJoints: GdxArray<JointCreationData>,
  val disconnects: GdxArray<CellConnection>?,
  val destroy: Boolean = false,
  val divide: Boolean = false,
)
