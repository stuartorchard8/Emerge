package org.emerge.demo.scavengers

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.clearRespawn
import org.emerge.sim.core.physics.model.removeEntityWithLandingCascade

/**
 * Removes a player's rocket (if any) and clears any pending respawn for that player.
 * Used by the lockstep host's leave policy.
 *
 * Implemented as a one-shot [PhysicsBuilder] pass so the landing cascade and cross-table
 * tombstoning stay consistent with per-tick removals.
 */
fun PhysicsState.removePlayerRocket(playerId: PlayerId): PhysicsState {
    val builder = PhysicsBuilder(this)
    builder.clearRespawn(playerId)
    val entityId = playerEntities[playerId]
    if (entityId != null) {
        builder.removeEntityWithLandingCascade(entityId)
    }
    return builder.build()
}

/**
 * Returns the entity id of the home planet assigned to [teamId], or null if none is
 * currently assigned. Scavengers-only: home planets are a per-game concept.
 */
fun PhysicsState.homePlanetEntity(teamId: TeamId): EntityId? =
    components.getTable<HomePlanetComponent>().entries()
        .firstOrNull { it.value.teamId == teamId }?.key
