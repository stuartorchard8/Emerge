package org.emerge.sim.codec.physics

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.Body
import org.emerge.sim.core.physics.Frac
import org.emerge.sim.core.physics.BodyShape
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
                w.writeInt(state.bodies.size)
                for ((pid, body) in state.bodies) {
                    w.writeInt(pid.value)
                    w.writeInt(body.pos.x.raw)
                    w.writeInt(body.pos.y.raw)
                    w.writeInt(body.vel.x.raw)
                    w.writeInt(body.vel.y.raw)
                    w.writeInt(body.ang.raw)
                    w.writeInt(body.angVel.raw)
                    w.writeInt(body.mass.toInt())
                    w.writeInt(body.radius.raw)
                    w.writeInt(body.bounce.raw)
                    w.writeInt(body.rough.raw)
                    w.writeInt(body.shape.wireValue)
                }
                return w.toByteArray()
            }

            override fun decode(bytes: ByteArray): PhysicsState {
                val c = ByteCursor(bytes)
                val n = c.readInt()
                val bodies = LinkedHashMap<PlayerId, Body>(n)
                repeat(n) {
                    val pid = PlayerId(c.readInt())
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
                    bodies[pid] = Body(
                        pid,
                        Frac2.raw(px, py),
                        Frac2.raw(vx, vy),
                        Frac(a),
                        Frac(av),
                        m.toUInt(),
                        Frac(rad),
                        Frac(b),
                        Frac(r),
                        shape,
                    )
                }
                return PhysicsState(bodies = bodies)
            }
        }
}

