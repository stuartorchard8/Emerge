package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentStore
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2

data class PhysicsState(
    val world: EcsWorld = EcsWorld.EMPTY,
    
    // Player-keyed
    val playerEntities: Map<PlayerId, EntityId> = emptyMap(),
    val pendingRespawns: Map<PlayerId, PlayerRespawnState> = emptyMap(),    // Replace with ComponentTable<SpawnQueuedComponent>

    val components: ComponentStore = ComponentStore(),

    // Events
    val contacts: List<Contact> = emptyList(),
    val crashImpactAudioEvents: List<CrashImpactAudioEvent> = emptyList(),

    /**
     * Deterministic PRNG state carried across ticks.
     * Must be kept in sync across all lockstep peers — never seed from platform Random.
     * Serialized alongside the snapshot for Welcome/Resync.
     */
    val randomSeed: Long = 0,
) {
    val transforms get() = components.getTable<TransformComponent>()
    val motions get() = components.getTable<MotionComponent>()
    val impulses get() = components.getTable<ImpulseComponent>()
    val colliders get() = components.getTable<ColliderComponent>()
    val materials get() = components.getTable<MaterialComponent>()
    val renderShapes get() = components.getTable<RenderShapeComponent>()
    val playerOwned get() = components.getTable<PlayerOwnedComponent>()
    val teams get() = components.getTable<TeamComponent>()
    val planets get() = components.getTable<PlanetComponent>()
    val forceFields get() = components.getTable<ForceFieldComponent>()
    val landings get() = components.getTable<LandingAttachmentComponent>()
    val particles get() = components.getTable<ParticleComponent>()
    val damages get() = components.getTable<DamageComponent>()

    fun playerTransform(playerId: PlayerId): TransformComponent? {
        val entityId = playerEntities[playerId] ?: return null
        return transforms[entityId]
    }

    fun playerMotion(playerId: PlayerId): MotionComponent? {
        val entityId = playerEntities[playerId] ?: return null
        return motions[entityId]
    }

    fun playerAngle(playerId: PlayerId): Coord? = playerTransform(playerId)?.ang

    fun playerAngularVelocity(playerId: PlayerId): Coord? = playerMotion(playerId)?.angVel

    fun playerViewFocus(playerId: PlayerId): Coord2 =
        playerTransform(playerId)?.pos
            ?: pendingRespawns[playerId]?.deathPos
            ?: Coord2.zero

    fun planetEntities(): Set<EntityId> =
        planets.keys()

    fun rebuildIndexes(): PhysicsState {
        val playerOwnedTable = components.getTable<PlayerOwnedComponent>()

        // Use the power of the map to build the reverse index in one pass
        val newPlayerEntities = playerOwnedTable.entries().associate { (id, comp) ->
            comp.playerId to id
        }

        return copy(playerEntities = newPlayerEntities)
    }
}