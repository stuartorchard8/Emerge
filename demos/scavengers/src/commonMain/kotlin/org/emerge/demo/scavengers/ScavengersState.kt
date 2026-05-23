package org.emerge.demo.scavengers

import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.model.PhysicsState

/**
 * Scavengers-specific game state. Wraps the engine [PhysicsState] with the
 * game-specific lifecycle accumulators (respawn queue, audio events) that the
 * engine no longer carries after Move 5.
 *
 * Persistent across ticks. The reducer reads it at the start of each tick and
 * produces a new instance with updated [core], [pendingRespawns], and
 * [crashImpactAudioEvents].
 */
data class ScavengersState(
    val core: PhysicsState = PhysicsState(),
    val pendingRespawns: Map<PlayerId, PlayerRespawnState> = emptyMap(),
    val crashImpactAudioEvents: List<CrashImpactAudioEvent> = emptyList(),
)
