package org.emerge.demo.scavengers

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.primitives.Coord2

data class CrashImpactAudioEvent(
    val entityId: EntityId,
    val pos: Coord2,
    val damageRaw: Int,
    val destroyed: Boolean,
)
