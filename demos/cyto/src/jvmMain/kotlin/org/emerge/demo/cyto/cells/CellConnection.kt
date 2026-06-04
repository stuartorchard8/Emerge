package org.emerge.demo.cyto.cells

import com.badlogic.gdx.physics.box2d.joints.DistanceJoint

data class CellConnection (
  val cellA: Cell,
  val cellB: Cell,
  val joint: DistanceJoint,
  var damage: Float = 0f,
)
