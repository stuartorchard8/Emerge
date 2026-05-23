package org.emerge.demo.scavengers

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.codec.physics.ImpulseCodec
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.TeamId
import org.emerge.sim.core.physics.primitives.BodyShape
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec

private const val MAX_STATE_CRASH_AUDIO_EVENTS = 4096

/**
 * Wire codec for [ScavengersState]. Layout:
 *
 *   coreBytesSize (Int)
 *   coreBytes     ([PhysicsNetCodecs.stateCodec] payload)
 *   respawnCount  (Int)
 *   respawn entries
 *   crashEventCount (Int)
 *   crash event entries
 *
 * Built on top of the demo's [PhysicsNetCodecs.stateCodec] so the engine codec stays
 * agnostic about respawn/audio events (which are Scavengers-only concepts).
 */
fun scavengersStateCodec(physicsNetCodecs: PhysicsNetCodecs): StateCodec<ScavengersState> =
    object : StateCodec<ScavengersState> {
        override fun encode(state: ScavengersState): ByteArray {
            val w = ByteWriter()
            val coreBytes = physicsNetCodecs.stateCodec.encode(state.core)
            w.writeInt(coreBytes.size)
            w.writeBytes(coreBytes)

            w.writeInt(state.pendingRespawns.size)
            for ((playerId, respawn) in state.pendingRespawns) {
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

            w.writeInt(state.crashImpactAudioEvents.size)
            for (event in state.crashImpactAudioEvents) {
                w.writeInt(event.entityId.value)
                w.writeInt(event.pos.x.raw)
                w.writeInt(event.pos.y.raw)
                w.writeInt(event.damageRaw)
                w.writeInt(if (event.destroyed) 1 else 0)
            }
            return w.toByteArray()
        }

        override fun decode(bytes: ByteArray): ScavengersState {
            val c = ByteCursor(bytes)
            val coreBytesSize = c.readInt()
            require(coreBytesSize >= 0) { "Invalid core payload size: $coreBytesSize" }
            val coreBytes = c.readBytes(coreBytesSize)
            val core = physicsNetCodecs.stateCodec.decode(coreBytes)

            val respawnCount = c.readInt()
            require(respawnCount >= 0) { "Invalid respawn count: $respawnCount" }
            val pendingRespawns = LinkedHashMap<PlayerId, PlayerRespawnState>(respawnCount)
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
                pendingRespawns[playerId] = PlayerRespawnState(
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

            val crashAudioEventCount = c.readInt()
            require(crashAudioEventCount in 0..MAX_STATE_CRASH_AUDIO_EVENTS) {
                "Invalid crash audio event count: $crashAudioEventCount"
            }
            val crashImpactAudioEvents = ArrayList<CrashImpactAudioEvent>(crashAudioEventCount)
            repeat(crashAudioEventCount) {
                val entityId = EntityId(c.readInt())
                val x = c.readInt()
                val y = c.readInt()
                val damageRaw = c.readInt()
                val destroyedRaw = c.readInt()
                crashImpactAudioEvents += CrashImpactAudioEvent(
                    entityId = entityId,
                    pos = Coord2.raw(x, y),
                    damageRaw = damageRaw,
                    destroyed = destroyedRaw != 0,
                )
            }

            return ScavengersState(
                core = core,
                pendingRespawns = pendingRespawns,
                crashImpactAudioEvents = crashImpactAudioEvents,
            )
        }
    }

/**
 * Semi-thin state codec for [ScavengersState]. Encodes only the impulse+damage tables
 * (via the engine-side [ImpulseCodec]) so semi-thin clients can replay deltas through
 * [ScavengersReducer.patchState]. Wrapper state (respawns, audio events) is empty in
 * the decoded delta; `patchState` only consumes the inner [PhysicsState] fields anyway.
 */
val scavengersImpulseCodec: StateCodec<ScavengersState> = object : StateCodec<ScavengersState> {
    override fun encode(state: ScavengersState): ByteArray = ImpulseCodec.encode(state.core)
    override fun decode(bytes: ByteArray): ScavengersState =
        ScavengersState(core = ImpulseCodec.decode(bytes))
}

/**
 * Standalone codec for a list of [CrashImpactAudioEvent], used by Scavengers' THIN
 * client path to ship just the per-tick audio events without a full state snapshot.
 */
val crashImpactAudioEventsCodec: Codec<List<CrashImpactAudioEvent>> =
    object : Codec<List<CrashImpactAudioEvent>> {
        private val STATE_CRASH_AUDIO_EVENT_INT_COUNT = 5
        private val STATE_INT_BYTES = 4

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
                out += CrashImpactAudioEvent(
                    entityId = entityId,
                    pos = Coord2.raw(x, y),
                    damageRaw = damageRaw,
                    destroyed = destroyedRaw != 0,
                )
            }
            return out
        }
    }
