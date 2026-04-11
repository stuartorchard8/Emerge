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
    val playerEntities: Map<PlayerId, EntityId> = emptyMap(),
    val transforms: ComponentTable<TransformComponent> = ComponentTable.Companion.empty(),
    val motions: ComponentTable<MotionComponent> = ComponentTable.Companion.empty(),
    val contacts: List<Contact> = emptyList(),
    val impulses: ComponentTable<ImpulseComponent> = ComponentTable.Companion.empty(),
    val discardImpulses: Boolean = false,
    val colliders: ComponentTable<ColliderComponent> = ComponentTable.Companion.empty(),
    val materials: ComponentTable<MaterialComponent> = ComponentTable.Companion.empty(),

    val renderShapes: ComponentTable<RenderShapeComponent> = ComponentTable.Companion.empty(),
    val playerOwned: ComponentTable<PlayerOwnedComponent> = ComponentTable.Companion.empty(),
    val teams: ComponentTable<TeamComponent> = ComponentTable.Companion.empty(),
    val planets: ComponentTable<PlanetComponent> = ComponentTable.Companion.empty(),
    val homePlanets: ComponentTable<HomePlanetComponent> = ComponentTable.Companion.empty(),
    val forceFields: ComponentTable<ForceFieldComponent> = ComponentTable.Companion.empty(),
    val landings: ComponentTable<LandingAttachmentComponent> = ComponentTable.Companion.empty(),
    val particles: ComponentTable<ParticleComponent> = ComponentTable.Companion.empty(),
    val damages: ComponentTable<DamageComponent> = ComponentTable.Companion.empty(),
    val pendingRespawns: Map<PlayerId, PlayerRespawnState> = emptyMap(),
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
            ?: Coord2.Companion.zero

    fun homePlanetEntity(teamId: TeamId): EntityId? =
        homePlanets.entries().firstOrNull { it.value.teamId == teamId }?.key

    fun planetEntities(): Set<EntityId> =
        planets.keys()
}