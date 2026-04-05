package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsSnapshot
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.CrashImpactAudioEvent
import org.emerge.sim.core.physics.PlayerRespawnState
import org.emerge.sim.core.physics.RespawnRocketSpec
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.components.ColliderComponent
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
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec

/**
 * Shared demo codecs for the deterministic physics sample.
 *
 * This keeps Android + desktop using the exact same wire format without duplicating logic.
 */
object PhysicsNetCodecs {
    private const val STATE_HEADER_INT_COUNT = 6
    private const val STATE_ENTITY_INT_COUNT = 28
    private const val STATE_RESPAWN_INT_COUNT = 11
    private const val STATE_CRASH_AUDIO_EVENT_INT_COUNT = 5
    private const val STATE_INT_BYTES = 4
    private const val MAX_STATE_ENTITIES = 2048
    private const val MAX_STATE_CRASH_AUDIO_EVENTS = 4096

    val inputCodec: Codec<PhysicsInput> =
        object : Codec<PhysicsInput> {
            override fun encode(value: PhysicsInput): ByteArray {
                val w = ByteWriter()
                w.writeInt(value.thrust)
                w.writeInt(value.turn)
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): PhysicsInput {
                val c = ByteCursor(bytes)
                val thrust = c.readInt()
                val turn = c.readInt()
                return PhysicsInput(thrust, turn)
            }
        }

    val stateCodec: StateCodec<PhysicsState> =
        object : StateCodec<PhysicsState> {
            override fun encode(state: PhysicsState): ByteArray {
                val w = ByteWriter()
                with (state.raw) {
                    val serializableEntities =
                        motions.keys().filter { entityId ->
                            ((renderShapes[entityId] != null) || (bgRenderShapes[entityId] != null))
                        }
                    w.writeInt(serializableEntities.size)
                    w.writeInt(pendingRespawns.size)
                    w.writeInt(crashImpactAudioEvents.size)
                    w.writeLong(state.randomSeed)
                    w.writeInt(world.lastEntityValue)
                    for (entityId in serializableEntities) {
                        val transform = transforms[entityId] ?: continue
                        val motion = motions[entityId] ?: continue
                        val collider = colliders[entityId] ?: continue
                        val material = materials[entityId]
                        val renderShape = renderShapes[entityId]
                        val bgRenderShape = bgRenderShapes[entityId]
                        val planet = planets[entityId]
                        val homePlanet = homePlanets[entityId]
                        val team = teams[entityId]
                        val forceField = forceFields[entityId]
                        val playerId = playerOwned[entityId]?.playerId
                        val landing = landings[entityId]
                        val particle = particles[entityId]
                        val damage = damages[entityId]
                        w.writeInt(entityId.value)
                        w.writeInt(playerId?.value ?: -1)
                        w.writeInt(transform.pos.x.raw)
                        w.writeInt(transform.pos.y.raw)
                        w.writeInt(motion.vel.x.raw)
                        w.writeInt(motion.vel.y.raw)
                        w.writeInt(transform.ang.raw)
                        w.writeInt(motion.angVel.raw)
                        w.writeInt(collider.radius.raw.toInt())

                        w.writeInt(material?.mass?.toInt() ?: -1)
                        w.writeInt(material?.bounce?.raw?.toInt() ?: -1)
                        w.writeInt(material?.rough?.raw?.toInt() ?: -1)

                        w.writeInt(renderShape?.shape?.wireValue ?: -1)
                        w.writeInt(bgRenderShape?.shape?.wireValue ?: -1)
                        w.writeInt(planet?.seed ?: -1)
                        w.writeInt(homePlanet?.teamId?.value ?: -1)
                        w.writeInt(team?.teamId?.value ?: -1)
                        w.writeInt(forceField?.depth?.raw?.toInt() ?: 0)
                        w.writeInt(forceField?.strength?.raw?.toInt() ?: 0)
                        w.writeInt(forceField?.alpha?.raw?.toInt() ?: 0)
                        w.writeInt(landing?.parentEntityId?.value ?: -1)
                        w.writeInt(landing?.relativePos?.x?.raw?.toInt() ?: 0)
                        w.writeInt(landing?.relativePos?.y?.raw?.toInt() ?: 0)
                        w.writeInt(landing?.relativeAng?.raw?.toInt() ?: 0)
                        w.writeInt(particle?.life ?: 0)
                        w.writeInt(particle?.lifeTime ?: 1)
                        w.writeInt(damage?.accumulated?.raw?.toInt() ?: 0)
                        w.writeInt(damage?.next?.raw?.toInt() ?: 0)
                    }
                    for ((playerId, respawn) in pendingRespawns) {
                        w.writeInt(playerId.value)
                        w.writeInt(respawn.ticksRemaining)
                        w.writeInt(respawn.teamId.value)
                        w.writeInt(respawn.entityId.value)
                        w.writeInt(respawn.deathPos.x.raw)
                        w.writeInt(respawn.deathPos.y.raw)
                        w.writeInt(respawn.rocket.mass.toInt())
                        w.writeInt(respawn.rocket.radius.raw.toInt())
                        w.writeInt(respawn.rocket.bounce.raw.toInt())
                        w.writeInt(respawn.rocket.rough.raw.toInt())
                        w.writeInt(respawn.rocket.shape.wireValue)
                    }
                    for (event in crashImpactAudioEvents) {
                        w.writeInt(event.entityId.value)
                        w.writeInt(event.pos.x.raw)
                        w.writeInt(event.pos.y.raw)
                        w.writeInt(event.damageRaw)
                        w.writeInt(if (event.destroyed) 1 else 0)
                    }
                }
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): PhysicsState {
                val c = ByteCursor(bytes)
                val n = c.readInt()
                require(n in 0..MAX_STATE_ENTITIES) { "Invalid entity count: $n" }
                val respawnCount = c.readInt()
                require(respawnCount >= 0) { "Invalid respawn count: $respawnCount" }
                val crashAudioEventCount = c.readInt()
                require(crashAudioEventCount in 0..MAX_STATE_CRASH_AUDIO_EVENTS) {
                    "Invalid crash audio event count: $crashAudioEventCount"
                }
                val randomSeed = c.readLong()
                val lastEntityValue = c.readInt()
                val expectedSize =
                    (
                        STATE_HEADER_INT_COUNT +
                            (n * STATE_ENTITY_INT_COUNT) +
                            (respawnCount * STATE_RESPAWN_INT_COUNT) +
                            (crashAudioEventCount * STATE_CRASH_AUDIO_EVENT_INT_COUNT)
                        ) * STATE_INT_BYTES
                require(bytes.size == expectedSize) {
                    "Invalid state payload size: expected $expectedSize bytes for $n entities + $respawnCount respawns + $crashAudioEventCount crash events, got ${bytes.size}"
                }
                val entities = mutableSetOf<Int>()
                val playerEntities = LinkedHashMap<PlayerId, EntityId>()
                val transforms = LinkedHashMap<EntityId, TransformComponent>(n)
                val motions = LinkedHashMap<EntityId, MotionComponent>(n)
                val colliders = LinkedHashMap<EntityId, ColliderComponent>(n)
                val materials = LinkedHashMap<EntityId, MaterialComponent>(n)
                val renderShapes = LinkedHashMap<EntityId, RenderShapeComponent>()
                val bgRenderShapes = LinkedHashMap<EntityId, RenderShapeComponent>()
                val playerOwned = LinkedHashMap<EntityId, PlayerOwnedComponent>()
                val teams = LinkedHashMap<EntityId, TeamComponent>()
                val planets = LinkedHashMap<EntityId, PlanetComponent>()
                val homePlanets = LinkedHashMap<EntityId, HomePlanetComponent>()
                val forceFields = LinkedHashMap<EntityId, ForceFieldComponent>()
                val landings = LinkedHashMap<EntityId, LandingAttachmentComponent>()
                val particles = LinkedHashMap<EntityId, ParticleComponent>()
                val damages = LinkedHashMap<EntityId, DamageComponent>()
                val pendingRespawns = LinkedHashMap<PlayerId, PlayerRespawnState>()
                val crashImpactAudioEvents = ArrayList<CrashImpactAudioEvent>(crashAudioEventCount)
                repeat(n) {
                    val entityId = EntityId(c.readInt())
                    val playerIdRaw = c.readInt()
                    val px = c.readInt()
                    val py = c.readInt()
                    val vx = c.readInt()
                    val vy = c.readInt()
                    val a = c.readInt()
                    val av = c.readInt()
                    val rad = c.readInt()
                    val m = c.readInt()
                    val b = c.readInt()
                    val r = c.readInt()
                    val shapeRaw = c.readInt()
                    val bgShapeRaw = c.readInt()
                    val planetSeed = c.readInt()
                    val homePlanetTeamIdRaw = c.readInt()
                    val teamIdRaw = c.readInt()
                    val forceFieldRadiusScaleRaw = c.readInt()
                    val forceFieldStrengthRaw = c.readInt()
                    val forceFieldAlphaRaw = c.readInt()
                    val landingParentIdRaw = c.readInt()
                    val landingDx = c.readInt()
                    val landingDy = c.readInt()
                    val landingAng = c.readInt()
                    val particleLife = c.readInt()
                    val particleLifetime = c.readInt()
                    val oldDamageRaw = c.readInt()
                    val newDamageRaw = c.readInt()
                    val playerId = if (playerIdRaw >= 0) PlayerId(playerIdRaw) else null
                    entities += entityId.value
                    transforms[entityId] =
                        TransformComponent(
                            pos = Coord2.raw(px, py),
                            ang = Coord(a),
                        )
                    motions[entityId] =
                        MotionComponent(
                            vel = Coord2.raw(vx, vy),
                            angVel = Coord(av),
                        )
                    colliders[entityId] =
                        ColliderComponent(radius = Frac(rad.toLong()))
                    if (m > 0 && b > 0 && r > 0) {
                        materials[entityId] =
                            MaterialComponent(
                                mass = m.toUInt(),
                                bounce = Frac(b.toLong()),
                                rough = Frac(r.toLong()),
                            )
                    }
                    if (shapeRaw >= 0) {
                        renderShapes[entityId] =
                            RenderShapeComponent(shape = BodyShape.fromWireValue(shapeRaw))
                    }
                    if (bgShapeRaw >= 0) {
                        bgRenderShapes[entityId] =
                            RenderShapeComponent(shape = BodyShape.fromWireValue(bgShapeRaw))
                    }
                    if (planetSeed >= 0) {
                        planets[entityId] =
                            PlanetComponent(seed = planetSeed)
                    }
                    if (homePlanetTeamIdRaw >= 0) {
                        homePlanets[entityId] =
                            HomePlanetComponent(
                                teamId = TeamId(homePlanetTeamIdRaw),
                            )
                    }
                    if (teamIdRaw >= 0) {
                        teams[entityId] =
                            TeamComponent(
                                teamId = TeamId(teamIdRaw),
                            )
                    }
                    if (forceFieldRadiusScaleRaw > 0) {
                        forceFields[entityId] =
                            ForceFieldComponent(
                                depth = Frac(forceFieldRadiusScaleRaw.toLong()),
                                strength = Frac(forceFieldStrengthRaw.toLong()),
                                alpha = Frac(forceFieldAlphaRaw.toLong()),
                            )
                    }
                    if (playerId != null) {
                        playerEntities[playerId] = entityId
                        playerOwned[entityId] =
                            PlayerOwnedComponent(playerId)
                    }
                    if (landingParentIdRaw >= 0) {
                        landings[entityId] =
                            LandingAttachmentComponent(
                                parentEntityId = EntityId(landingParentIdRaw),
                                relativePos = Frac2.raw(landingDx, landingDy),
                                relativeAng = Frac(landingAng.toLong()),
                            )
                    }
                    if (particleLife > 0) {
                        particles[entityId] =
                            ParticleComponent(
                                life = particleLife,
                                lifeTime = particleLifetime,
                            )
                    }
                    if (oldDamageRaw > 0 || newDamageRaw > 0) {
                        damages[entityId] = DamageComponent(
                            Frac(oldDamageRaw.toLong()),
                            Frac(0),
                            Frac(newDamageRaw.toLong()),
                        )
                    }
                }
                repeat(respawnCount) {
                    val playerId = PlayerId(c.readInt())
                    val ticksRemaining = c.readInt()
                    val teamIdRaw = c.readInt()
                    val entityIdRaw = c.readInt()
                    val deathPosX = c.readInt()
                    val deathPosY = c.readInt()
                    val massRaw = c.readInt()
                    val radiusRaw = c.readInt()
                    val bounceRaw = c.readInt()
                    val roughRaw = c.readInt()
                    val shapeRaw = c.readInt()
                    pendingRespawns[playerId] =
                        PlayerRespawnState(
                            ticksRemaining = ticksRemaining,
                            deathPos = Coord2.raw(deathPosX, deathPosY),
                            teamId = TeamId(teamIdRaw),
                            entityId = EntityId(entityIdRaw),
                            rocket = RespawnRocketSpec(
                                mass = massRaw.toUInt(),
                                radius = Frac(radiusRaw.toLong()),
                                bounce = Frac(bounceRaw.toLong()),
                                rough = Frac(roughRaw.toLong()),
                                shape = BodyShape.fromWireValue(shapeRaw),
                            ),
                        )
                }
                repeat(crashAudioEventCount) {
                    val entityId = EntityId(c.readInt())
                    val x = c.readInt()
                    val y = c.readInt()
                    val damageRaw = c.readInt()
                    val destroyedRaw = c.readInt()
                    crashImpactAudioEvents +=
                        CrashImpactAudioEvent(
                            entityId = entityId,
                            pos = Coord2.raw(x, y),
                            damageRaw = damageRaw,
                            destroyed = destroyedRaw != 0,
                        )
                }
                val state = PhysicsSnapshot(
                    world = EcsWorld(
                        entities = entities,
                        lastEntityValue = lastEntityValue,
                    ),
                    playerEntities = playerEntities,
                    transforms = ComponentTable.fromMap(transforms),
                    motions = ComponentTable.fromMap(motions),
                    colliders = ComponentTable.fromMap(colliders),
                    materials = ComponentTable.fromMap(materials),
                    renderShapes = ComponentTable.fromMap(renderShapes),
                    bgRenderShapes = ComponentTable.fromMap(bgRenderShapes),
                    playerOwned = ComponentTable.fromMap(playerOwned),
                    teams = ComponentTable.fromMap(teams),
                    planets = ComponentTable.fromMap(planets),
                    homePlanets = ComponentTable.fromMap(homePlanets),
                    forceFields = ComponentTable.fromMap(forceFields),
                    landings = ComponentTable.fromMap(landings),
                    particles = ComponentTable.fromMap(particles),
                    damages = ComponentTable.fromMap(damages),
                    pendingRespawns = pendingRespawns,
                    crashImpactAudioEvents = crashImpactAudioEvents,
                ).mutable
                state.randomSeed = randomSeed
                state.raw.world.lastEntityValue = lastEntityValue
                return state
            }
        }

    val crashImpactAudioEventsCodec: Codec<List<CrashImpactAudioEvent>> =
        object : Codec<List<CrashImpactAudioEvent>> {
            override fun encode(value: List<CrashImpactAudioEvent>): ByteArray {
                val w = ByteWriter()
                w.writeInt(value.size)
                for (event in value) {
                    w.writeInt(event.entityId.value)
                    w.writeInt(event.pos.x.raw)
                    w.writeInt(event.pos.y.raw)
                    w.writeInt(event.damageRaw)
                    w.writeInt(if (event.destroyed) 1 else 0)
                }
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): List<CrashImpactAudioEvent> {
                val c = ByteCursor(bytes)
                val count = c.readInt()
                require(count in 0..MAX_STATE_CRASH_AUDIO_EVENTS) {
                    "Invalid crash audio event count: $count"
                }
                val expectedSize = (1 + count * STATE_CRASH_AUDIO_EVENT_INT_COUNT) * STATE_INT_BYTES
                require(bytes.size == expectedSize) {
                    "Invalid crash audio event payload size: expected $expectedSize bytes for $count events, got ${bytes.size}"
                }
                val out = ArrayList<CrashImpactAudioEvent>(count)
                repeat(count) {
                    val entityId = EntityId(c.readInt())
                    val x = c.readInt()
                    val y = c.readInt()
                    val damageRaw = c.readInt()
                    val destroyedRaw = c.readInt()
                    out +=
                        CrashImpactAudioEvent(
                            entityId = entityId,
                            pos = Coord2.raw(x, y),
                            damageRaw = damageRaw,
                            destroyed = destroyedRaw != 0,
                        )
                }
                return out
            }
        }
}

