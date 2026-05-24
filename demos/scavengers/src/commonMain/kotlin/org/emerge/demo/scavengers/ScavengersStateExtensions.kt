package org.emerge.demo.scavengers

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import org.emerge.sim.core.sim.removeEntityWithLandingCascade
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2

/**
 * Removes a player's rocket (if any) and clears any pending respawn for that player.
 * Used by the lockstep host's leave policy.
 *
 * Implemented as a one-shot [SimBuilder] pass so the landing cascade and cross-table
 * tombstoning stay consistent with per-tick removals.
 */
fun ScavengersState.removePlayerRocket(playerId: PlayerId): ScavengersState {
    val builder = SimBuilder(core)
    val entityId = playerEntities[playerId]
    if (entityId != null) {
        builder.removeEntityWithLandingCascade(entityId)
    }
    val nextCore = builder.build()
    return copy(
        core = nextCore,
        playerEntities = nextCore.computePlayerEntities(),
        pendingRespawns = pendingRespawns - playerId,
    )
}

/**
 * Returns the entity id of the home planet assigned to [teamId], or null if none is
 * currently assigned. Scavengers-only: home planets are a per-game concept.
 */
fun SimState.homePlanetEntity(teamId: TeamId): EntityId? =
    components.getTable<HomePlanetComponent>().entries()
        .firstOrNull { it.value.teamId == teamId }?.key

// --- Player-keyed query helpers ------------------------------------------
//
// Replace the typed convenience methods removed from SimState in Move 5.
// These stay in Scavengers because they're how Scavengers reads player state for
// rendering/input handling; engine code never needed them.

fun ScavengersState.playerTransform(playerId: PlayerId): TransformComponent? {
    val entityId = playerEntities[playerId] ?: return null
    return core.components.getTable<TransformComponent>()[entityId]
}

fun ScavengersState.playerMotion(playerId: PlayerId): MotionComponent? {
    val entityId = playerEntities[playerId] ?: return null
    return core.components.getTable<MotionComponent>()[entityId]
}

fun ScavengersState.playerAngle(playerId: PlayerId): Coord? = playerTransform(playerId)?.ang

fun ScavengersState.playerAngularVelocity(playerId: PlayerId): Coord? = playerMotion(playerId)?.angVel

/**
 * View focus position for a player. Falls back to the recorded [PlayerRespawnState.deathPos]
 * while the player is respawning (so the camera doesn't snap to origin between death and
 * the rocket reappearing on a planet).
 */
fun ScavengersState.playerViewFocus(playerId: PlayerId): Coord2 =
    playerTransform(playerId)?.pos
        ?: pendingRespawns[playerId]?.deathPos
        ?: Coord2.zero

/**
 * Camera-anchor position to pass to [org.emerge.render.torus.ScreenRenderer.draw]. Centred on
 * the player's current position, holding the [PlayerRespawnState.deathPos] during respawn so
 * the camera doesn't snap to origin. Returns the world origin when there's no local player
 * (e.g. headless host).
 */
fun ScavengersState.rendererFocus(playerId: PlayerId?): org.emerge.sim.core.physics.primitives.Vec2 {
    if (playerId == null) return org.emerge.sim.core.physics.primitives.Vec2(0f, 0f)
    val c = playerViewFocus(playerId)
    return org.emerge.sim.core.physics.primitives.Vec2(c.x.toFloat(), c.y.toFloat())
}
