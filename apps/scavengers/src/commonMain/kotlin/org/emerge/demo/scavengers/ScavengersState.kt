package org.emerge.demo.scavengers

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.sim.SimState

/**
 * Scavengers-specific game state. Wraps the engine [SimState] with the
 * game-specific lifecycle accumulators (respawn queue, audio events) and the
 * player→entity index that the engine no longer carries.
 *
 * Persistent across ticks. The reducer reads it at the start of each tick and
 * produces a new instance with refreshed [core], [playerEntities],
 * [pendingRespawns], and [crashImpactAudioEvents].
 */
data class ScavengersState(
    val core: SimState = SimState(),
    val playerEntities: Map<PlayerId, EntityId> = emptyMap(),
    val pendingRespawns: Map<PlayerId, PlayerRespawnState> = emptyMap(),
    val crashImpactAudioEvents: List<CrashImpactAudioEvent> = emptyList(),
)

/**
 * Rebuilds the player→entity index from the authoritative [PlayerOwnedComponent] table.
 * Called by the reducer and codec after producing a fresh [SimState] so callers can
 * resolve the player's rocket entity without scanning components.
 */
fun SimState.computePlayerEntities(): Map<PlayerId, EntityId> =
    components.getTable<PlayerOwnedComponent>()
        .entries()
        .associate { (id, comp) -> comp.playerId to id }
