package org.emerge.sim.core.physics

import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld

data class PhysicsConfig(
    val thrustFactorInv: Int = Int.MAX_VALUE / (1024 * 128),
    val turnFactorInv: Int = Int.MAX_VALUE / (1024 * 512),
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
            landings = landings.remove(entityId),
        )
    }

    fun playerTransform(playerId: PlayerId): TransformComponent? {
        val entityId = playerEntities[playerId] ?: return null
        return transforms[entityId]
    }

    fun playerAngle(playerId: PlayerId): Frac? = playerTransform(playerId)?.ang

    fun renderBodies(): List<PhysicsRenderBody> {
        val out = ArrayList<PhysicsRenderBody>(world.entities.size)
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
        }
        return out
    }
}

data class PhysicsInput(val thrust: Int, val turn: Int) {
    companion object {
        val ZERO = PhysicsInput(0, 0)
    }
}
