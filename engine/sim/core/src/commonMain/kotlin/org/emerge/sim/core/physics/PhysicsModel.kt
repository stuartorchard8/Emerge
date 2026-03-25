package org.emerge.sim.core.physics

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ControlIntentComponent
import org.emerge.sim.core.physics.components.DamageComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.HomePlanetComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.components.PlanetComponent
import org.emerge.sim.core.physics.components.PlayerOwnedComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.physics.primitives.Norm
import kotlin.collections.get
import kotlin.text.get

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 32),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
    val gravityNumerator: Frac = Frac(1,16),
    val shipCollisionDamageThreshold: Frac = Frac(1, 1024 * 8),
    val shipCollisionDamageScale: Frac = Frac(1, 1),
    val shipMaxDamage: Frac = Frac(1, 256),
    val shipRespawnTicks: Int = 60 * 5,
)

data class RespawnRocketSpec(
    val mass: UInt,
    val radius: Frac,
    val bounce: Frac,
    val rough: Frac,
    val shape: BodyShape,
)

data class PlayerRespawnState(
    val ticksRemaining: Int,
    val deathPos: Coord2,
    val teamId: TeamId,
    val rocket: RespawnRocketSpec,
)

data class CrashImpactAudioEvent(
    val entityId: EntityId,
    val pos: Coord2,
    val damageRaw: Long,
    val destroyed: Boolean,
)

data class PhysicsSnapshot(
    val world: EcsWorld = EcsWorld.EMPTY,
    val playerEntities: Map<PlayerId, EntityId> = emptyMap(),
    val transforms: ComponentTable<TransformComponent> = ComponentTable.empty(),
    val motions: ComponentTable<MotionComponent> = ComponentTable.empty(),
    val colliders: ComponentTable<ColliderComponent> = ComponentTable.empty(),
    val materials: ComponentTable<MaterialComponent> = ComponentTable.empty(),
    val controls: ComponentTable<ControlIntentComponent> = ComponentTable.empty(),
    val renderShapes: ComponentTable<RenderShapeComponent> = ComponentTable.empty(),
    val bgRenderShapes: ComponentTable<RenderShapeComponent> = ComponentTable.empty(),
    val playerOwned: ComponentTable<PlayerOwnedComponent> = ComponentTable.empty(),
    val teams: ComponentTable<TeamComponent> = ComponentTable.empty(),
    val planets: ComponentTable<PlanetComponent> = ComponentTable.empty(),
    val homePlanets: ComponentTable<HomePlanetComponent> = ComponentTable.empty(),
    val forceFields: ComponentTable<ForceFieldComponent> = ComponentTable.empty(),
    val landings: ComponentTable<LandingAttachmentComponent> = ComponentTable.empty(),
    val particles: ComponentTable<ParticleComponent> = ComponentTable.empty(),
    val damages: ComponentTable<DamageComponent> = ComponentTable.empty(),
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
            ?: Coord2.zero

    fun homePlanetEntity(teamId: TeamId): EntityId? =
        homePlanets.entries().firstOrNull { it.value.teamId == teamId }?.key

    fun planetEntities(): Set<EntityId> =
        planets.keys()
}

data class PhysicsState(
    var raw: PhysicsSnapshot,
) {
    fun spawnBody(
        playerId: PlayerId?,
        pos: Coord2,
        vel: Coord2,
        ang: Coord,
        angVel: Coord,
        mass: UInt,
        radius: Frac,
        bounce: Frac,
        rough: Frac,
        shape: BodyShape,
    ): EntityId {
        val entityId = raw.world.createEntity()
        putBody(
            entityId = entityId,
            playerId = playerId,
            pos = pos,
            vel = vel,
            ang = ang,
            angVel = angVel,
            mass = mass,
            radius = radius,
            bounce = bounce,
            rough = rough,
            shape = shape,
        )
        return entityId
    }

    fun putBody(
        entityId: EntityId,
        playerId: PlayerId?,
        pos: Coord2,
        vel: Coord2,
        ang: Coord,
        angVel: Coord,
        mass: UInt,
        radius: Frac,
        bounce: Frac,
        rough: Frac,
        shape: BodyShape,
    ) {
        raw.world.ensureEntity(entityId)
        val nextPlayerEntities =
            if (playerId == null) {
                raw.playerEntities.filterValues { it != entityId }
            } else {
                LinkedHashMap(raw.playerEntities).apply { put(playerId, entityId) }
            }
        val nextPlayerOwned =
            if (playerId == null) {
                raw.playerOwned.remove(entityId)
            } else {
                raw.playerOwned.put(entityId, PlayerOwnedComponent(playerId))
            }
        val nextControls =
            if (playerId == null) {
                raw.controls.remove(entityId)
            } else {
                raw.controls.put(entityId, ControlIntentComponent.ZERO)
            }
        raw = raw.copy(
            playerEntities = nextPlayerEntities,
            transforms = raw.transforms.put(entityId, TransformComponent(pos = pos, ang = ang)),
            motions = raw.motions.put(entityId, MotionComponent(vel = vel, angVel = angVel)),
            colliders = raw.colliders.put(entityId, ColliderComponent(radius = radius)),
            materials = raw.materials.put(entityId, MaterialComponent(mass = mass, bounce = bounce, rough = rough)),
            controls = nextControls,
            renderShapes = raw.renderShapes.put(entityId, RenderShapeComponent(shape = shape)),
            bgRenderShapes = raw.bgRenderShapes.remove(entityId),
            playerOwned = nextPlayerOwned,
            teams = raw.teams.remove(entityId),
            planets = raw.planets.remove(entityId),
            homePlanets = raw.homePlanets.remove(entityId),
            forceFields = raw.forceFields.remove(entityId),
            landings = raw.landings.remove(entityId),
            particles = raw.particles.remove(entityId),
            damages = raw.damages.remove(entityId),
            pendingRespawns = if (playerId == null) raw.pendingRespawns else raw.pendingRespawns - playerId,
        )
    }

    fun spawnParticle(
        pos: Coord2,
        vel: Coord2,
        radius: Frac,
        shape: BodyShape,
        lifetime: Int,
        teamId: TeamId,
    ): EntityId {
        val entityId = raw.world.createEntity()
        putParticle(
            entityId = entityId,
            pos = pos,
            vel = vel,
            radius = radius,
            shape = shape,
            lifetime = lifetime,
            teamId = teamId,
        )
        return entityId
    }

    fun putParticle(
        entityId: EntityId,
        pos: Coord2,
        vel: Coord2,
        radius: Frac,
        shape: BodyShape,
        lifetime: Int,
        teamId: TeamId,
    ) {
        raw.world.ensureEntity(entityId)
        raw = raw.copy(
            playerEntities = raw.playerEntities.filterValues { it != entityId },
            transforms = raw.transforms.put(entityId, TransformComponent(pos = pos, ang = Coord(0))),
            motions = raw.motions.put(entityId, MotionComponent(vel = vel, angVel = Coord(0))),
            colliders = raw.colliders.put(entityId, ColliderComponent(radius = radius)),    // TODO don't store radius in collider
            materials = raw.materials.remove(entityId),
            controls = raw.controls.remove(entityId),
            renderShapes = raw.renderShapes.remove(entityId),
            bgRenderShapes = raw.bgRenderShapes.put(entityId, RenderShapeComponent(shape = shape)),
            playerOwned = raw.playerOwned.remove(entityId),
            teams = raw.teams.put(entityId, TeamComponent(teamId)),
            planets = raw.planets.remove(entityId),
            homePlanets = raw.homePlanets.remove(entityId),
            forceFields = raw.forceFields.remove(entityId),
            landings = raw.landings.remove(entityId),
            particles = raw.particles.put(entityId, ParticleComponent(lifetime, lifetime)),
            damages = raw.damages.remove(entityId),
        )
    }

    fun markPlanet(entityId: EntityId, seed: Int = entityId.value) {
        raw = raw.copy(planets = raw.planets.put(entityId, PlanetComponent(seed = seed)))
    }

    fun assignHomePlanet(
        entityId: EntityId,
        teamId: TeamId,
    ) {
        var nextHomePlanets = raw.homePlanets
        for ((existingEntityId, homePlanet) in raw.homePlanets.entries()) {
            if (homePlanet.teamId == teamId && existingEntityId != entityId) {
                nextHomePlanets = nextHomePlanets.remove(existingEntityId)
            }
        }
        raw = raw.copy(homePlanets = nextHomePlanets.put(entityId, HomePlanetComponent(teamId)))
    }

    fun setForceField(
        entityId: EntityId,
        depth: Frac,
        strength: Frac,
        alpha: Frac,
    ) {
        raw = raw.copy(
            forceFields = raw.forceFields.put(
                entityId,
                ForceFieldComponent(
                    depth = depth,
                    strength = strength,
                    alpha = alpha,
                ),
            ),
        )
    }

    fun clearForceField(entityId: EntityId) {
        raw = raw.copy(forceFields = raw.forceFields.remove(entityId))
    }

    fun setTeam(entityId: EntityId, teamId: TeamId) {
        raw = raw.copy(teams = raw.teams.put(entityId, TeamComponent(teamId)))
    }

    fun clearTeam(entityId: EntityId) {
        raw = raw.copy(teams = raw.teams.remove(entityId))
    }

    fun removePlayerRocket(playerId: PlayerId) {
        raw = raw.copy(pendingRespawns = raw.pendingRespawns - playerId)
        val entityId = raw.playerEntities[playerId] ?: return
        removeEntity(entityId)
    }

    fun removeEntity(entityId: EntityId) {
        raw.world.removeEntity(entityId)
        val nextPlayerEntities = LinkedHashMap(raw.playerEntities.filterValues { it != entityId })
        val nextLandings = LinkedHashMap(raw.landings.asMap())
        for ((attachedEntityId, landing) in raw.landings.entries()) {
            if (landing.parentEntityId == entityId) {
                nextLandings.remove(attachedEntityId)
            }
        }
        raw.world.removeEntity(entityId)
        raw = raw.copy(
            playerEntities = nextPlayerEntities,
            transforms = raw.transforms.remove(entityId),
            motions = raw.motions.remove(entityId),
            colliders = raw.colliders.remove(entityId),
            materials = raw.materials.remove(entityId),
            controls = raw.controls.remove(entityId),
            renderShapes = raw.renderShapes.remove(entityId),
            bgRenderShapes = raw.bgRenderShapes.remove(entityId),
            playerOwned = raw.playerOwned.remove(entityId),
            teams = raw.teams.remove(entityId),
            planets = raw.planets.remove(entityId),
            homePlanets = raw.homePlanets.remove(entityId),
            forceFields = raw.forceFields.remove(entityId),
            landings = ComponentTable.fromMap(nextLandings).remove(entityId),
            particles = raw.particles.remove(entityId),
            damages = raw.damages.remove(entityId),
        )
    }

    fun queuePlayerRespawn(playerId: PlayerId, ticksRemaining: Int) {
        val entityId = raw.playerEntities[playerId] ?: return
        val transform = raw.transforms[entityId] ?: return removeEntity(entityId)
        val material = raw.materials[entityId] ?: return removeEntity(entityId)
        val collider = raw.colliders[entityId] ?: return removeEntity(entityId)
        val renderShape = raw.renderShapes[entityId] ?: return removeEntity(entityId)
        val teamId = raw.teams[entityId]?.teamId ?: return removeEntity(entityId)
        removeEntity(entityId)
        val nextRespawns = LinkedHashMap(raw.pendingRespawns)
        nextRespawns[playerId] =
            PlayerRespawnState(
                ticksRemaining = ticksRemaining,
                deathPos = transform.pos,
                teamId = teamId,
                rocket = RespawnRocketSpec(
                    mass = material.mass,
                    radius = collider.radius,
                    bounce = material.bounce,
                    rough = material.rough,
                    shape = renderShape.shape,
                ),
            )
        raw = raw.copy(pendingRespawns = nextRespawns)
    }

    fun advanceRespawns() {
        if (raw.pendingRespawns.isEmpty()) return
        val nextRespawns = LinkedHashMap(raw.pendingRespawns)
        for ((playerId, respawn) in raw.pendingRespawns) {
            val nextTicks = (respawn.ticksRemaining - 1).coerceAtLeast(0)
            val updatedRespawn = respawn.copy(ticksRemaining = nextTicks)
            if (nextTicks > 0) {
                nextRespawns[playerId] = updatedRespawn
                continue
            }
            val respawned = tryRespawnPlayer(playerId, updatedRespawn)
            if (respawned) {
                nextRespawns.remove(playerId)
            } else {
                nextRespawns[playerId] = updatedRespawn
            }
        }
        raw = raw.copy(pendingRespawns = nextRespawns)
    }

    private fun tryRespawnPlayer(playerId: PlayerId, respawn: PlayerRespawnState): Boolean {
        val teamId = respawn.teamId
        val homePlanetId = raw.homePlanetEntity(teamId) ?: return false
        val planetTransform = raw.transforms[homePlanetId] ?: return false
        val planetMotion = raw.motions[homePlanetId] ?: return false
        val planetCollider = raw.colliders[homePlanetId] ?: return false
        val localAngle = Coord(playerId.value, Int.MAX_VALUE)
        val localNormal = Norm.fromAngle(localAngle)
        val relativePos = localNormal * (planetCollider.radius + respawn.rocket.radius)
        val worldPos = planetTransform.pos + rotateByAngle(relativePos, planetTransform.ang)
        val worldAng = Coord(planetTransform.ang.raw + localAngle.raw)
        val entityId = spawnBody(
            playerId = playerId,
            pos = worldPos,
            vel = planetMotion.vel,
            ang = worldAng,
            angVel = planetMotion.angVel,
            mass = respawn.rocket.mass,
            radius = respawn.rocket.radius,
            bounce = respawn.rocket.bounce,
            rough = respawn.rocket.rough,
            shape = respawn.rocket.shape,
        )
        setTeam(
            entityId = entityId,
            teamId = teamId,
        )
        raw = raw.copy(
            motions = raw.motions.put(
                entityId,
                MotionComponent(
                    vel = planetMotion.vel,
                    angVel = planetMotion.angVel,
                ),
            ),
            landings = raw.landings.put(
                entityId,
                LandingAttachmentComponent(
                    parentEntityId = homePlanetId,
                    relativePos = relativePos,
                    relativeAng = Frac(localAngle.raw.toLong()),
                ),
            ),
        )
        return true
    }

    private fun rotateByAngle(v: Frac2, angle: Coord): Frac2 {
        val rotation = Norm.fromAngle(angle)
        return Frac2(
            x = v.x * rotation.x - v.y * rotation.y,
            y = v.x * rotation.y + v.y * rotation.x,
        )
    }
}

