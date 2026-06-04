package org.emerge.demo.cyto

import org.emerge.demo.cyto.environment.CellWorld

/**
 * One frame's worth of renderable state. Phase A is an imperative Box2D sim, so the
 * frame just hands the live [CellWorld] to the renderer (which reads cell positions,
 * radii, types, and membrane-neighbour data each draw) alongside the tick counter.
 * This differs from the functional snapshot frames of the other demos — see the port
 * plan; Phase B folds Cyto into the ECS reducer shape.
 */
class CytoFrame(
  val world: CellWorld,
  val tick: Long,
)
