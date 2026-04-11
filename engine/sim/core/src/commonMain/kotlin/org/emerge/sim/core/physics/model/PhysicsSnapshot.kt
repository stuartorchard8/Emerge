package org.emerge.sim.core.physics.model

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.components.*
import org.emerge.sim.core.physics.primitives.Contact
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2

data class PhysicsSnapshot(
    val world: EcsWorld = EcsWorld.EMPTY,
    
    // Player-keyed
    val playerEntities: Map<PlayerId, EntityId> = emptyMap(),
    val pendingRespawns: Map<PlayerId, PlayerRespawnState> = emptyMap(),

    // Entity-keyed
    val transforms: ComponentTable<TransformComponent> = ComponentTable.empty(),
    val motions: ComponentTable<MotionComponent> = ComponentTable.empty(),
    val impulses: ComponentTable<ImpulseComponent> = ComponentTable.empty(),
    val colliders: ComponentTable<ColliderComponent> = ComponentTable.empty(),
    val materials: ComponentTable<MaterialComponent> = ComponentTable.empty(),
    val renderShapes: ComponentTable<RenderShapeComponent> = ComponentTable.empty(),
    val playerOwned: ComponentTable<PlayerOwnedComponent> = ComponentTable.empty(),
    val teams: ComponentTable<TeamComponent> = ComponentTable.empty(),
    val planets: ComponentTable<PlanetComponent> = ComponentTable.empty(),
    val homePlanets: ComponentTable<HomePlanetComponent> = ComponentTable.empty(),
    val forceFields: ComponentTable<ForceFieldComponent> = ComponentTable.empty(),
    val landings: ComponentTable<LandingAttachmentComponent> = ComponentTable.empty(),
    val particles: ComponentTable<ParticleComponent> = ComponentTable.empty(),
    val damages: ComponentTable<DamageComponent> = ComponentTable.empty(),

    // Events
    val contacts: List<Contact> = emptyList(),
    val crashImpactAudioEvents: List<CrashImpactAudioEvent> = emptyList(),
) {
    val mutable get() = PhysicsState(this)

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

    fun homePlanetEntity(teamId: TeamId): EntityId? =
        homePlanets.entries().firstOrNull { it.value.teamId == teamId }?.key

    fun planetEntities(): Set<EntityId> =
        planets.keys()
}