package org.emerge.sim.core.physics

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ControlIntentComponent
import org.emerge.sim.core.physics.components.ForceFieldComponent
import org.emerge.sim.core.physics.components.HomePlanetComponent
import org.emerge.sim.core.physics.components.LandingAttachmentComponent
import org.emerge.sim.core.physics.components.MaterialComponent
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.ParticleComponent
import org.emerge.sim.core.physics.primitives.RenderShape
import org.emerge.sim.core.physics.components.PlanetComponent
import org.emerge.sim.core.physics.components.PlayerOwnedComponent
import org.emerge.sim.core.physics.components.RenderShapeComponent
import org.emerge.sim.core.physics.components.TeamComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.Frac2

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 32),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
    val gravityNumerator: Frac = Frac(1,16),
)

data class PhysicsState(
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
    ): Pair<PhysicsState, EntityId> {
        val (nextWorld, entityId) = world.createEntity()
        return putBody(
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
            worldOverride = nextWorld,
        ) to entityId
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
        worldOverride: EcsWorld = world.ensureEntity(entityId),
    ): PhysicsState {
        val nextPlayerEntities =
            if (playerId == null) {
                playerEntities.filterValues { it != entityId }
            } else {
                LinkedHashMap(playerEntities).apply { put(playerId, entityId) }
            }
        val nextPlayerOwned =
            if (playerId == null) {
                playerOwned.remove(entityId)
            } else {
                playerOwned.put(entityId, PlayerOwnedComponent(playerId))
            }
        val nextControls =
            if (playerId == null) {
                controls.remove(entityId)
            } else {
                controls.put(entityId, ControlIntentComponent.ZERO)
            }
        return copy(
            world = worldOverride,
            playerEntities = nextPlayerEntities,
            transforms = transforms.put(entityId, TransformComponent(pos = pos, ang = ang)),
            motions = motions.put(entityId, MotionComponent(vel = vel, angVel = angVel)),
            colliders = colliders.put(entityId, ColliderComponent(radius = radius)),
            materials = materials.put(entityId, MaterialComponent(mass = mass, bounce = bounce, rough = rough)),
            controls = nextControls,
            renderShapes = renderShapes.put(entityId, RenderShapeComponent(shape = shape)),
            bgRenderShapes = bgRenderShapes.remove(entityId),
            playerOwned = nextPlayerOwned,
            teams = teams.remove(entityId),
            planets = planets.remove(entityId),
            homePlanets = homePlanets.remove(entityId),
            forceFields = forceFields.remove(entityId),
            landings = landings.remove(entityId),
            particles = particles.remove(entityId),
        )
    }

    fun spawnParticle(
        pos: Coord2,
        vel: Coord2,
        radius: Frac,
        shape: BodyShape,
        lifetime: Int,
        teamId: TeamId,
    ): Pair<PhysicsState, EntityId> {
        val (nextWorld, entityId) = world.createEntity()
        return putParticle(
            entityId = entityId,
            pos = pos,
            vel = vel,
            radius = radius,
            shape = shape,
            lifetime = lifetime,
            teamId = teamId,
            worldOverride = nextWorld,
        ) to entityId
    }

    fun putParticle(
        entityId: EntityId,
        pos: Coord2,
        vel: Coord2,
        radius: Frac,
        shape: BodyShape,
        lifetime: Int,
        teamId: TeamId,
        worldOverride: EcsWorld = world.ensureEntity(entityId),
    ): PhysicsState {
        return copy(
            world = worldOverride,
            playerEntities = playerEntities.filterValues { it != entityId },
            transforms = transforms.put(entityId, TransformComponent(pos = pos, ang = Coord(0))),
            motions = motions.put(entityId, MotionComponent(vel = vel, angVel = Coord(0))),
            colliders = colliders.put(entityId, ColliderComponent(radius = radius)),
            materials = materials.remove(entityId),
            controls = controls.remove(entityId),
            renderShapes = renderShapes.remove(entityId),
            bgRenderShapes = bgRenderShapes.put(entityId, RenderShapeComponent(shape = shape)),
            playerOwned = playerOwned.remove(entityId),
            teams = teams.put(entityId, TeamComponent(teamId)),
            planets = planets.remove(entityId),
            homePlanets = homePlanets.remove(entityId),
            forceFields = forceFields.remove(entityId),
            landings = landings.remove(entityId),
            particles = particles.put(entityId, ParticleComponent(lifetime, lifetime)),
        )
    }

    fun markPlanet(entityId: EntityId, seed: Int = entityId.value): PhysicsState =
        copy(planets = planets.put(entityId, PlanetComponent(seed = seed)))

    fun assignHomePlanet(
        entityId: EntityId,
        teamId: TeamId,
    ): PhysicsState {
        var nextHomePlanets = homePlanets
        for ((existingEntityId, homePlanet) in homePlanets.entries()) {
            if (homePlanet.teamId == teamId && existingEntityId != entityId) {
                nextHomePlanets = nextHomePlanets.remove(existingEntityId)
            }
        }
        return copy(homePlanets = nextHomePlanets.put(entityId, HomePlanetComponent(teamId)))
    }

    fun setForceField(
        entityId: EntityId,
        depth: Frac,
        strength: Frac,
        alpha: Frac,
    ): PhysicsState =
        copy(
            forceFields = forceFields.put(
                entityId,
                ForceFieldComponent(
                    depth = depth,
                    strength = strength,
                    alpha = alpha,
                ),
            ),
        )

    fun clearForceField(entityId: EntityId): PhysicsState =
        copy(forceFields = forceFields.remove(entityId))

    fun setTeam(entityId: EntityId, teamId: TeamId): PhysicsState =
        copy(teams = teams.put(entityId, TeamComponent(teamId)))

    fun clearTeam(entityId: EntityId): PhysicsState =
        copy(teams = teams.remove(entityId))

    fun removePlayerRocket(playerId: PlayerId): PhysicsState {
        val entityId = playerEntities[playerId] ?: return this
        return removeEntity(entityId)
    }

    fun removeEntity(entityId: EntityId): PhysicsState {
        world.removeEntity(entityId)
        val nextPlayerEntities = LinkedHashMap(playerEntities.filterValues { it != entityId })
        val nextLandings = LinkedHashMap(landings.asMap())
        for ((attachedEntityId, landing) in landings.entries()) {
            if (landing.parentEntityId == entityId) {
                nextLandings.remove(attachedEntityId)
            }
        }
        return copy(
            world = world.removeEntity(entityId),
            playerEntities = nextPlayerEntities,
            transforms = transforms.remove(entityId),
            motions = motions.remove(entityId),
            colliders = colliders.remove(entityId),
            materials = materials.remove(entityId),
            controls = controls.remove(entityId),
            renderShapes = renderShapes.remove(entityId),
            bgRenderShapes = bgRenderShapes.remove(entityId),
            playerOwned = playerOwned.remove(entityId),
            teams = teams.remove(entityId),
            planets = planets.remove(entityId),
            homePlanets = homePlanets.remove(entityId),
            forceFields = forceFields.remove(entityId),
            landings = ComponentTable.fromMap(nextLandings).remove(entityId),
            particles = particles.remove(entityId),
        )
    }

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

    fun homePlanetEntity(teamId: TeamId): EntityId? =
        homePlanets.entries().firstOrNull { it.value.teamId == teamId }?.key

    fun planetEntities(): List<EntityId> =
        world.entities.filter { planets.contains(it) }
}

