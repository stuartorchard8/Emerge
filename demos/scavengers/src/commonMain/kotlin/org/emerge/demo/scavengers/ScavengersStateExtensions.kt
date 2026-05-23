package org.emerge.demo.scavengers

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.model.PhysicsBuilder
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.model.removeEntityWithLandingCascade
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2

/**
 * Removes a player's rocket (if any) and clears any pending respawn for that player.
 * Used by the lockstep host's leave policy.
 *
 * Implemented as a one-shot [PhysicsBuilder] pass so the landing cascade and cross-table
 * tombstoning stay consistent with per-tick removals.
 */
fun ScavengersState.removePlayerRocket(playerId: PlayerId): ScavengersState {
    val builder = PhysicsBuilder(core)
    val entityId = core.playerEntities[playerId]
    if (entityId != null) {
        builder.removeEntityWithLandingCascade(entityId)
    }
    return copy(
        core = builder.build(),
        pendingRespawns = pendingRespawns - playerId,
    )
}

/**
 * Returns the entity id of the home planet assigned to [teamId], or null if none is
 * currently assigned. Scavengers-only: home planets are a per-game concept.
 */
fun PhysicsState.homePlanetEntity(teamId: TeamId): EntityId? =
    components.getTable<HomePlanetComponent>().entries()
        .firstOrNull { it.value.teamId == teamId }?.key

// --- Player-keyed query helpers ------------------------------------------
//
// Replace the typed convenience methods removed from PhysicsState in Move 5.
// These stay in Scavengers because they're how Scavengers reads player state for
// rendering/input handling; engine code never needed them.

fun PhysicsState.playerTransform(playerId: PlayerId): TransformComponent? {
    val entityId = playerEntities[playerId] ?: return null
    return components.getTable<TransformComponent>()[entityId]
}

fun PhysicsState.playerMotion(playerId: PlayerId): MotionComponent? {
    val entityId = playerEntities[playerId] ?: return null
    return components.getTable<MotionComponent>()[entityId]
}

fun PhysicsState.playerAngle(playerId: PlayerId): Coord? = playerTransform(playerId)?.ang

fun PhysicsState.playerAngularVelocity(playerId: PlayerId): Coord? = playerMotion(playerId)?.angVel

/**
 * View focus position for a player. Falls back to the recorded [PlayerRespawnState.deathPos]
 * while the player is respawning (so the camera doesn't snap to origin between death and
 * the rocket reappearing on a planet).
 */
fun ScavengersState.playerViewFocus(playerId: PlayerId): Coord2 =
    core.playerTransform(playerId)?.pos
        ?: pendingRespawns[playerId]?.deathPos
        ?: Coord2.zero
