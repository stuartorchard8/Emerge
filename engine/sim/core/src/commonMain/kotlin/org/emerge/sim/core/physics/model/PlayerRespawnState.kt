package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.primitives.Coord2

data class PlayerRespawnState(
    val ticksRemaining: Int,
    val deathPos: Coord2,
    val teamId: TeamId,
    val entityId: EntityId,
    val rocket: RespawnRocketSpec,
)