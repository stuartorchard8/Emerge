package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.ecs.EcsWorld
import org.emerge.sim.core.physics.model.*
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import org.emerge.sim.sync.ecs.registry

/**
 * Shared demo codecs for the deterministic physics sample.
 *
 * This keeps Android + desktop using the exact same wire format without duplicating logic.
 */
object PhysicsNetCodecs {
    private const val STATE_HEADER_INT_COUNT = 6
    private const val STATE_ENTITY_INT_COUNT = 27
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
                with(state.raw) {
                    val serializableEntities =
                        motions.keys().filter { entityId -> renderShapes[entityId] != null }
                    w.writeInt(serializableEntities.size)
                    w.writeInt(pendingRespawns.size)
                    w.writeInt(crashImpactAudioEvents.size)
                    w.writeLong(randomSeed)
                    w.writeInt(world.lastEntityValue)
                    for (entityId in serializableEntities) {
                        w.writeInt(entityId.value)
                        for (encoder in registry) {
                            encoder.encode(w, components, entityId)
                        }
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
                val pendingRespawns = LinkedHashMap<PlayerId, PlayerRespawnState>()
                val crashImpactAudioEvents = ArrayList<CrashImpactAudioEvent>(crashAudioEventCount)

                var state = PhysicsSnapshot()

                repeat(n) {
                    val entityId = EntityId(c.readInt())
                    entities += entityId.value
                    state = state.copy(
                        components = state.components.update {
                            for (codec in registry) {
                                val component = codec.decode(c)
                                if (component != null) {
                                    setRaw(entityId, component)
                                }
                            }
                        }
                    )
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
                val mutableState = state.copy(
                    world = EcsWorld(
                        entities = entities,
                        lastEntityValue = lastEntityValue,
                    ),
                    pendingRespawns = pendingRespawns,
                    crashImpactAudioEvents = crashImpactAudioEvents,
                    randomSeed = randomSeed,
                ).rebuildIndexes().mutable
                mutableState.raw.world.lastEntityValue = lastEntityValue
                return mutableState
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

