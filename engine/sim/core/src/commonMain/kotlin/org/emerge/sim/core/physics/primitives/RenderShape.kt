package org.emerge.sim.core.physics.primitives

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId

data class RenderShape(
    val entityId: EntityId,
    val playerId: PlayerId?,
    val pos: Coord2,
    val ang: Coord,
    val radius: Frac,
    val shape: BodyShape,
    val alpha: Float = 1f,
)
