package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.CircleBody
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.Frac2
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
            override fun encode(value: PhysicsInput): ByteArray {
                val w = ByteWriter()
                w.writeInt(value.ax)
                w.writeInt(value.ay)
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): PhysicsInput {
                val c = ByteCursor(bytes)
                val ax = c.readInt()
                val ay = c.readInt()
                return PhysicsInput(ax, ay)
            }
        }

    val stateCodec: StateCodec<PhysicsState> =
        object : StateCodec<PhysicsState> {
            override fun encode(state: PhysicsState): ByteArray {
                val w = ByteWriter()
                w.writeInt(state.bodies.size)
                for ((pid, body) in state.bodies) {
                    w.writeInt(pid.value)
                    w.writeInt(body.pos.x)
                    w.writeInt(body.pos.y)
                    w.writeInt(body.vel.x)
                    w.writeInt(body.vel.y)
                    w.writeInt(body.ang.raw)
                    w.writeInt(body.angVel.raw)
                    w.writeInt(body.radius)
                }
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): PhysicsState {
                val c = ByteCursor(bytes)
                val n = c.readInt()
                val bodies = LinkedHashMap<PlayerId, CircleBody>(n)
                repeat(n) {
                    val pid = PlayerId(c.readInt())
                    val px = c.readInt()
                    val py = c.readInt()
                    val vx = c.readInt()
                    val vy = c.readInt()
                    val a = c.readInt()
                    val av = c.readInt()
                    val r = c.readInt()
                    bodies[pid] = CircleBody(
                        pid,
                        Frac2(px, py),
                        Frac2(vx, vy),
                        Frac(a),
                        Frac(av),
                        r,
                    )
                }
                return PhysicsState(bodies = bodies)
            }
        }
}

