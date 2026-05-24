@file:OptIn(BypassesStagedView::class)

package org.emerge.demo.scavengers

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.BypassesStagedView
import org.emerge.sim.core.ecs.withLock
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.sim.SimBuilder

/**
 * Per-frame Scavengers state carried alongside the engine
 * [org.emerge.sim.core.sim.SimFrameScratch] on the same [SimBuilder].
 * Holds the respawn queue, audio event accumulator, and start-of-frame player→entity
 * index that the engine state no longer carries.
 *
 * Seeded by [seedScavengersScratch] from the start-of-frame [ScavengersState]; mutated in
 * place during the tick. The reducer reads the final values via [scavengersScratch] after
 * the pipeline runs to fold them into a new [ScavengersState].
 *
 * Like the engine PRNG seed, the respawn queue is shared across forks in an isolated
 * phase: reads and writes route through the ROOT builder's scratch so per-demo damage
 * enqueues and respawn drains are globally visible. Under sequential fork execution
 * this is bit-identical to the old in-place behaviour.
 */
class ScavengersFrameScratch(
    initialPendingRespawns: Map<PlayerId, PlayerRespawnState>,
    val playerEntities: Map<PlayerId, EntityId>,
) {
    val pendingRespawns: MutableMap<PlayerId, PlayerRespawnState> =
        LinkedHashMap(initialPendingRespawns)

    val crashImpactAudioEvents: MutableList<CrashImpactAudioEvent> = ArrayList()
}

/**
 * Seeds (idempotently) a [ScavengersFrameScratch] on this builder. Called by the reducer
 * before the pipeline runs so Scavengers systems can find a fully-initialised scratch
 * via [scavengersScratch]. The closure captures the start-of-frame respawn queue and
 * player→entity index so the scratch can be seeded even though the underlying engine
 * state no longer carries them.
 */
fun SimBuilder.seedScavengersScratch(
    initialPendingRespawns: Map<PlayerId, PlayerRespawnState>,
    playerEntities: Map<PlayerId, EntityId>,
): ScavengersFrameScratch =
    scratch(
        factory = { _ -> ScavengersFrameScratch(initialPendingRespawns, playerEntities) },
        // Finalizer is a no-op: the reducer pulls accumulator state out directly and
        // folds it into the new ScavengersState, bypassing the engine state.
        finalize = { _ -> this },
    )

/**
 * Start-of-frame player→entity index. Read by Scavengers systems via the builder rather
 * than by reaching back into the engine [org.emerge.sim.core.sim.SimState],
 * which no longer carries this map. Routes through the root builder's scratch so forks
 * see the same map.
 */
val SimBuilder.playerEntities: Map<PlayerId, EntityId>
    get() = scavengersScratch().playerEntities

/**
 * Returns the Scavengers scratch registered on this builder (or its root). If the
 * reducer forgot to call [seedScavengersScratch] first, the factory throws so we get
 * an early, loud failure rather than a silent reset of the respawn queue.
 */
fun SimBuilder.scavengersScratch(): ScavengersFrameScratch {
    var b: SimBuilder = this
    while (true) {
        val p = b.parent ?: return b.scratch(
            factory = { _ ->
                error(
                    "ScavengersFrameScratch not registered on builder; the Scavengers " +
                        "reducer must call seedScavengersScratch() before running systems.",
                )
            },
            finalize = { _ -> this },
        )
        b = p
    }
}

// --- Respawn queue -------------------------------------------------------

/**
 * Snapshot view of the current pending respawns as last written this frame. The
 * returned map is an immutable copy taken while holding [rootLock], so callers
 * can safely iterate it even if other forks are mutating the underlying queue
 * concurrently. Mutations must go through [queueRespawn] / [clearRespawn] /
 * [updateRespawn] which also acquire [rootLock].
 */
val SimBuilder.pendingRespawns: Map<PlayerId, PlayerRespawnState>
    get() = rootLock.withLock { scavengersScratch().pendingRespawns.toMap() }

/**
 * Captures the entity's current transform/material/collider/renderShape/team into a
 * [PlayerRespawnState] and enqueues it under [playerId]. If any required component is
 * missing the entity is instead removed outright — matching the legacy behaviour.
 */
fun SimBuilder.queueRespawn(playerId: PlayerId, ticksRemaining: Int) {
    val entityId = playerEntities[playerId] ?: return
    val transform = getComponent<TransformComponent>(entityId)
    val material = getComponent<MaterialComponent>(entityId)
    val collider = getComponent<ColliderComponent>(entityId)
    val renderShape = getComponent<RenderShapeComponent>(entityId)
    val teamId = getComponent<TeamComponent>(entityId)?.teamId
    if (transform == null || material == null || collider == null ||
        renderShape == null || teamId == null
    ) {
        removeEntity(entityId)
        return
    }
    val entry = PlayerRespawnState(
        ticksRemaining = ticksRemaining,
        deathPos = transform.pos,
        teamId = teamId,
        entityId = entityId,
        rocket = RespawnRocketSpec(
            mass = material.mass,
            radius = collider.radius,
            bounce = material.bounce,
            rough = material.rough,
            shape = renderShape.shape,
        ),
    )
    rootLock.withLock { scavengersScratch().pendingRespawns[playerId] = entry }
}

/** Removes [playerId] from the pending respawn queue, if present. */
fun SimBuilder.clearRespawn(playerId: PlayerId) {
    rootLock.withLock { scavengersScratch().pendingRespawns.remove(playerId) }
}

/**
 * Applies [block] to the existing respawn entry for [playerId], if any. A null return
 * from [block] removes the entry; a non-null return replaces it. No-op if no entry
 * exists. Read/compute/write happens under [rootLock], so concurrent forks can't
 * lose each other's updates.
 */
fun SimBuilder.updateRespawn(
    playerId: PlayerId,
    block: (PlayerRespawnState) -> PlayerRespawnState?,
) {
    rootLock.withLock {
        val scratch = scavengersScratch()
        val current = scratch.pendingRespawns[playerId] ?: return@withLock
        val next = block(current)
        if (next == null) scratch.pendingRespawns.remove(playerId)
        else scratch.pendingRespawns[playerId] = next
    }
}

// --- Audio events --------------------------------------------------------

/**
 * Appends a crash audio event to the frame's accumulator. The reducer reads
 * the accumulator after the pipeline runs and folds it into
 * [ScavengersState.crashImpactAudioEvents].
 *
 * Routes through the root builder's scratch, so emits from forks land on the shared
 * list deterministically (write-log replay handles fork merge ordering at phase
 * barriers).
 */
fun SimBuilder.emitCrashAudio(event: CrashImpactAudioEvent) {
    rootLock.withLock { scavengersScratch().crashImpactAudioEvents.add(event) }
}
