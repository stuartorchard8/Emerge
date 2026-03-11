package org.emerge.sim.core.physics

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 128),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
    val gravityNumerator: Int = 1 shl 17,
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
    val playerOwned: ComponentTable<PlayerOwnedComponent> = ComponentTable.empty(),
    val teams: ComponentTable<TeamComponent> = ComponentTable.empty(),
    val planets: ComponentTable<PlanetComponent> = ComponentTable.empty(),
    val homePlanets: ComponentTable<HomePlanetComponent> = ComponentTable.empty(),
    val forceFields: ComponentTable<ForceFieldComponent> = ComponentTable.empty(),
    val landings: ComponentTable<LandingAttachmentComponent> = ComponentTable.empty(),
) {
    fun spawnBody(
        playerId: PlayerId?,
        pos: Frac2,
        vel: Frac2,
        ang: Frac,
        angVel: Frac,
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
        pos: Frac2,
        vel: Frac2,
        ang: Frac,
        angVel: Frac,
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
            playerOwned = nextPlayerOwned,
            teams = teams.remove(entityId),
            planets = planets.remove(entityId),
            homePlanets = homePlanets.remove(entityId),
            forceFields = forceFields.remove(entityId),
            landings = landings.remove(entityId),
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
        val nextPlayerEntities = LinkedHashMap(playerEntities).apply { remove(playerId) }
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
            playerOwned = playerOwned.remove(entityId),
            teams = teams.remove(entityId),
            planets = planets.remove(entityId),
            homePlanets = homePlanets.remove(entityId),
            forceFields = forceFields.remove(entityId),
            landings = ComponentTable.fromMap(nextLandings).remove(entityId),
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

    fun playerAngle(playerId: PlayerId): Frac? = playerTransform(playerId)?.ang

    fun playerAngularVelocity(playerId: PlayerId): Frac? = playerMotion(playerId)?.angVel

    fun homePlanetEntity(teamId: TeamId): EntityId? =
        homePlanets.entries().firstOrNull { it.value.teamId == teamId }?.key

    fun planetEntities(): List<EntityId> =
        world.entities.filter { planets.contains(it) }

    fun renderBodies(): List<PhysicsRenderBody> {
        val out = ArrayList<PhysicsRenderBody>(world.entities.size * 2)
        for (entityId in world.entities) {
            val transform = transforms[entityId] ?: continue
            val collider = colliders[entityId] ?: continue
            val renderShape = renderShapes[entityId] ?: continue
            val owned = playerOwned[entityId]
            out += PhysicsRenderBody(
                entityId = entityId,
                playerId = owned?.playerId,
                pos = transform.pos,
                ang = transform.ang,
                radius = collider.radius,
                shape = renderShape.shape,
            )
            val forceField = forceFields[entityId]
            if (forceField != null && renderShape.shape == BodyShape.CIRCLE) {
                out += PhysicsRenderBody(
                    entityId = entityId,
                    playerId = owned?.playerId,
                    pos = transform.pos,
                    ang = transform.ang,
                    radius = collider.radius + forceField.depth,
                    shape = renderShape.shape,
                    alpha = forceField.alpha.toFloat(),
                )
            }
        }
        return out
    }
}

data class PhysicsInput(val thrust: Int, val turn: Int) {
    companion object {
        val ZERO = PhysicsInput(0, 0)
    }
}
