package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.BodyShape
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Frac2
import org.emerge.sim.core.ecs.ComponentTable
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.StateCodec

/**
 * Shared demo codecs for the deterministic physics sample.
 *
 * This keeps Android + desktop using the exact same wire format without duplicating logic.
 */
object PhysicsNetCodecs {
    private const val STATE_HEADER_INT_COUNT = 2
    private const val STATE_ENTITY_INT_COUNT = 23
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
                    w.writeInt(entityId.value)
                    w.writeInt(playerId?.value ?: -1)
                    w.writeInt(transform.pos.x.raw)
                    w.writeInt(transform.pos.y.raw)
                    w.writeInt(motion.vel.x.raw)
                    w.writeInt(motion.vel.y.raw)
                    w.writeInt(transform.ang.raw)
                    w.writeInt(motion.angVel.raw)
                    w.writeInt(material.mass.toInt())
                    w.writeInt(collider.radius.raw)
                    w.writeInt(material.bounce.raw)
                    w.writeInt(material.rough.raw)
                    w.writeInt(renderShape.shape.wireValue)
                    w.writeInt(planet?.seed ?: -1)
                    w.writeInt(homePlanet?.playerId?.value ?: -1)
                    w.writeInt(team?.teamId?.value ?: -1)
                    w.writeInt(forceField?.depth?.raw ?: 0)
                    w.writeInt(forceField?.strength?.raw ?: 0)
                    w.writeInt(forceField?.alpha?.raw ?: 0)
                    w.writeInt(landing?.parentEntityId?.value ?: -1)
                    w.writeInt(landing?.relativePos?.x?.raw ?: 0)
                    w.writeInt(landing?.relativePos?.y?.raw ?: 0)
                    w.writeInt(landing?.relativeAng?.raw ?: 0)
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
                var world = EcsWorld(nextEntityValue = nextEntityValue)
                var playerEntities = LinkedHashMap<PlayerId, EntityId>()
                var transforms = ComponentTable.empty<org.emerge.sim.core.physics.TransformComponent>()
                var motions = ComponentTable.empty<org.emerge.sim.core.physics.MotionComponent>()
                var colliders = ComponentTable.empty<org.emerge.sim.core.physics.ColliderComponent>()
                var materials = ComponentTable.empty<org.emerge.sim.core.physics.MaterialComponent>()
                var controls = ComponentTable.empty<org.emerge.sim.core.physics.ControlIntentComponent>()
                var renderShapes = ComponentTable.empty<org.emerge.sim.core.physics.RenderShapeComponent>()
                var playerOwned = ComponentTable.empty<org.emerge.sim.core.physics.PlayerOwnedComponent>()
                var teams = ComponentTable.empty<org.emerge.sim.core.physics.TeamComponent>()
                var planets = ComponentTable.empty<org.emerge.sim.core.physics.PlanetComponent>()
                var homePlanets = ComponentTable.empty<org.emerge.sim.core.physics.HomePlanetComponent>()
                var forceFields = ComponentTable.empty<org.emerge.sim.core.physics.ForceFieldComponent>()
                var landings = ComponentTable.empty<org.emerge.sim.core.physics.LandingAttachmentComponent>()
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
                    val homePlanetPlayerIdRaw = c.readInt()
                    val teamIdRaw = c.readInt()
                    val forceFieldRadiusScaleRaw = c.readInt()
                    val forceFieldStrengthRaw = c.readInt()
                    val forceFieldAlphaRaw = c.readInt()
                    val landingParentIdRaw = c.readInt()
                    val landingDx = c.readInt()
                    val landingDy = c.readInt()
                    val landingAng = c.readInt()
                    val playerId = if (playerIdRaw >= 0) PlayerId(playerIdRaw) else null
                    world = world.ensureEntity(entityId)
                    transforms = transforms.put(
                        entityId,
                        org.emerge.sim.core.physics.TransformComponent(
                            pos = Frac2.raw(px, py),
                            ang = Frac(a),
                        ),
                    )
                    motions = motions.put(
                        entityId,
                        org.emerge.sim.core.physics.MotionComponent(
                            vel = Frac2.raw(vx, vy),
                            angVel = Frac(av),
                        ),
                    )
                    colliders = colliders.put(
                        entityId,
                        org.emerge.sim.core.physics.ColliderComponent(radius = Frac(rad)),
                    )
                    materials = materials.put(
                        entityId,
                        org.emerge.sim.core.physics.MaterialComponent(
                            mass = m.toUInt(),
                            bounce = Frac(b),
                            rough = Frac(r),
                        ),
                    )
                    renderShapes = renderShapes.put(
                        entityId,
                        org.emerge.sim.core.physics.RenderShapeComponent(shape = shape),
                    )
                    if (planetSeed >= 0) {
                        planets = planets.put(
                            entityId,
                            org.emerge.sim.core.physics.PlanetComponent(seed = planetSeed),
                        )
                    }
                    if (homePlanetPlayerIdRaw >= 0) {
                        homePlanets = homePlanets.put(
                            entityId,
                            org.emerge.sim.core.physics.HomePlanetComponent(
                                playerId = PlayerId(homePlanetPlayerIdRaw),
                            ),
                        )
                    }
                    if (teamIdRaw >= 0) {
                        teams = teams.put(
                            entityId,
                            org.emerge.sim.core.physics.TeamComponent(
                                teamId = TeamId(teamIdRaw),
                            ),
                        )
                    }
                    if (forceFieldRadiusScaleRaw > 0) {
                        forceFields = forceFields.put(
                            entityId,
                            org.emerge.sim.core.physics.ForceFieldComponent(
                                depth = Frac(forceFieldRadiusScaleRaw),
                                strength = Frac(forceFieldStrengthRaw),
                                alpha = Frac(forceFieldAlphaRaw),
                            ),
                        )
                    }
                    if (playerId != null) {
                        playerEntities[playerId] = entityId
                        playerOwned = playerOwned.put(
                            entityId,
                            org.emerge.sim.core.physics.PlayerOwnedComponent(playerId),
                        )
                        controls = controls.put(
                            entityId,
                            org.emerge.sim.core.physics.ControlIntentComponent.ZERO,
                        )
                    }
                    if (landingParentIdRaw >= 0) {
                        landings = landings.put(
                            entityId,
                            org.emerge.sim.core.physics.LandingAttachmentComponent(
                                parentEntityId = EntityId(landingParentIdRaw),
                                relativePos = Frac2.raw(landingDx, landingDy),
                                relativeAng = Frac(landingAng),
                            ),
                        )
                    }
                }
                return PhysicsState(
                    world = world,
                    playerEntities = playerEntities,
                    transforms = transforms,
                    motions = motions,
                    colliders = colliders,
                    materials = materials,
                    controls = controls,
                    renderShapes = renderShapes,
                    playerOwned = playerOwned,
                    teams = teams,
                    planets = planets,
                    homePlanets = homePlanets,
                    forceFields = forceFields,
                    landings = landings,
                )
            }
        }
}

