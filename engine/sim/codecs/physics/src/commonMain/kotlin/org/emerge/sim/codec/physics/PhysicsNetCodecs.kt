package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Fx
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Vec2Fx
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.StateCodec

/**
 * Shared demo codecs for the deterministic physics sample.
 *
 * This keeps Android + desktop using the exact same wire format without duplicating logic.
 */
object PhysicsNetCodecs {
    val inputCodec: Codec<PhysicsInput> =
        object : Codec<PhysicsInput> {
            override fun encode(value: PhysicsInput): ByteArray =
                byteArrayOf(value.ax.toByte(), value.ay.toByte())

            override fun decode(bytes: ByteArray): PhysicsInput {
                require(bytes.size == 2) { "Expected 2 bytes, got ${bytes.size}" }
                return PhysicsInput(bytes[0].toInt(), bytes[1].toInt())
            }
        }

    val stateCodec: StateCodec<PhysicsState> =
        object : StateCodec<PhysicsState> {
            override fun encode(state: PhysicsState): ByteArray {
                val w = ByteWriter()
                w.writeInt(state.width.raw)
                w.writeInt(state.height.raw)
                w.writeInt(state.bodies.size)
                for ((pid, body) in state.bodies) {
                    w.writeInt(pid.value)
                    w.writeInt(body.pos.x.raw)
                    w.writeInt(body.pos.y.raw)
                    w.writeInt(body.vel.x.raw)
                    w.writeInt(body.vel.y.raw)
                    w.writeInt(body.radius.raw)
                }
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): PhysicsState {
                val c = ByteCursor(bytes)
                val width = Fx(c.readInt())
                val height = Fx(c.readInt())
                val n = c.readInt()
                val bodies = LinkedHashMap<PlayerId, CircleBody>(n)
                repeat(n) {
                    val pid = PlayerId(c.readInt())
                    val px = Fx(c.readInt())
                    val py = Fx(c.readInt())
                    val vx = Fx(c.readInt())
                    val vy = Fx(c.readInt())
                    val r = Fx(c.readInt())
                    bodies[pid] = CircleBody(pid, Vec2Fx(px, py), Vec2Fx(vx, vy), r)
                }
                return PhysicsState(width = width, height = height, bodies = bodies)
            }
        }
}

