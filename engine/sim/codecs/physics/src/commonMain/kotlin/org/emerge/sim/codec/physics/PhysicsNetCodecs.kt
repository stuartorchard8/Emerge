package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.Frac2
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.components.ColliderComponent
import org.emerge.sim.core.physics.components.ControlIntentComponent
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
import org.emerge.sim.sync.auth.StateCodec

/**
 * Shared demo codecs for the deterministic physics sample.
 *
 * This keeps Android + desktop using the exact same wire format without duplicating logic.
 */
object PhysicsNetCodecs {
    private const val STATE_HEADER_INT_COUNT = 2
    private const val STATE_ENTITY_INT_COUNT = 25
    private const val STATE_INT_BYTES = 4
    private const val MAX_STATE_ENTITIES = 2048

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
                w.writeInt(state.world.nextEntityValue)
                w.writeInt(state.world.entities.size)
                for (entityId in state.world.entities) {
                    val transform = state.transforms[entityId] ?: continue
                    val motion = state.motions[entityId] ?: continue
                    val collider = state.colliders[entityId] ?: continue
                    val material = state.materials[entityId] ?: continue
                    val renderShape = state.renderShapes[entityId] ?: continue
                    val planet = state.planets[entityId]
                    val homePlanet = state.homePlanets[entityId]
                    val team = state.teams[entityId]
                    val forceField = state.forceFields[entityId]
                    val playerId = state.playerOwned[entityId]?.playerId
                    val landing = state.landings[entityId]
                    val particle = state.particles[entityId]
                    w.writeInt(entityId.value)
                    w.writeInt(playerId?.value ?: -1)
                    w.writeInt(transform.pos.x.raw)
                    w.writeInt(transform.pos.y.raw)
                    w.writeInt(motion.vel.x.raw)
                    w.writeInt(motion.vel.y.raw)
                    w.writeInt(transform.ang.raw)
                    w.writeInt(motion.angVel.raw)
                    w.writeInt(material.mass.toInt())
                    w.writeInt(collider.radius.raw.toInt())
                    w.writeInt(material.bounce.raw.toInt())
                    w.writeInt(material.rough.raw.toInt())
                    w.writeInt(renderShape.shape.wireValue)
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
                }
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): PhysicsState {
                val c = ByteCursor(bytes)
                val nextEntityValue = c.readInt()
                val n = c.readInt()
                require(nextEntityValue >= 0) { "Invalid nextEntityValue: $nextEntityValue" }
                require(n in 0..MAX_STATE_ENTITIES) { "Invalid entity count: $n" }
                val expectedSize = (STATE_HEADER_INT_COUNT + (n * STATE_ENTITY_INT_COUNT)) * STATE_INT_BYTES
                require(bytes.size == expectedSize) {
                    "Invalid state payload size: expected $expectedSize bytes for $n entities, got ${bytes.size}"
                }
                val entities = ArrayList<EntityId>(n)
                val playerEntities = LinkedHashMap<PlayerId, EntityId>()
                val transforms = LinkedHashMap<EntityId, TransformComponent>(n)
                val motions = LinkedHashMap<EntityId, MotionComponent>(n)
                val colliders = LinkedHashMap<EntityId, ColliderComponent>(n)
                val materials = LinkedHashMap<EntityId, MaterialComponent>(n)
                val controls = LinkedHashMap<EntityId, ControlIntentComponent>()
                val renderShapes = LinkedHashMap<EntityId, RenderShapeComponent>(n)
                val playerOwned = LinkedHashMap<EntityId, PlayerOwnedComponent>()
                val teams = LinkedHashMap<EntityId, TeamComponent>()
                val planets = LinkedHashMap<EntityId, PlanetComponent>()
                val homePlanets = LinkedHashMap<EntityId, HomePlanetComponent>()
                val forceFields = LinkedHashMap<EntityId, ForceFieldComponent>()
                val landings = LinkedHashMap<EntityId, LandingAttachmentComponent>()
                val particles = LinkedHashMap<EntityId, ParticleComponent>()
                repeat(n) {
                    val entityId = EntityId(c.readInt())
                    val playerIdRaw = c.readInt()
                    val px = c.readInt()
                    val py = c.readInt()
                    val vx = c.readInt()
                    val vy = c.readInt()
                    val a = c.readInt()
                    val av = c.readInt()
                    val m = c.readInt()
                    val rad = c.readInt()
                    val b = c.readInt()
                    val r = c.readInt()
                    val shape = BodyShape.fromWireValue(c.readInt())
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
                    val playerId = if (playerIdRaw >= 0) PlayerId(playerIdRaw) else null
                    entities += entityId
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
                    materials[entityId] =
                        MaterialComponent(
                            mass = m.toUInt(),
                            bounce = Frac(b.toLong()),
                            rough = Frac(r.toLong()),
                        )
                    renderShapes[entityId] =
                        RenderShapeComponent(shape = shape)
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
                        controls[entityId] = ControlIntentComponent.ZERO
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
                }
                return PhysicsState(
                    world = EcsWorld(
                        entities = entities,
                        nextEntityValue = nextEntityValue,
                    ),
                    playerEntities = playerEntities,
                    transforms = ComponentTable.fromMap(transforms),
                    motions = ComponentTable.fromMap(motions),
                    colliders = ComponentTable.fromMap(colliders),
                    materials = ComponentTable.fromMap(materials),
                    controls = ComponentTable.fromMap(controls),
                    renderShapes = ComponentTable.fromMap(renderShapes),
                    playerOwned = ComponentTable.fromMap(playerOwned),
                    teams = ComponentTable.fromMap(teams),
                    planets = ComponentTable.fromMap(planets),
                    homePlanets = ComponentTable.fromMap(homePlanets),
                    forceFields = ComponentTable.fromMap(forceFields),
                    landings = ComponentTable.fromMap(landings),
                    particles = ComponentTable.fromMap(particles),
                )
            }
        }
}

