package org.emerge.sim.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import org.emerge.net.loopback.Loopback
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.demo.DemoInput
import org.emerge.sim.core.demo.DemoReducer
import org.emerge.sim.core.demo.DemoState
import org.emerge.sim.core.demo.Vec2i

class LockstepLoopbackTest {
    private object DemoInputCodec : Codec<DemoInput> {
        override fun encode(value: DemoInput): ByteArray =
            byteArrayOf(value.move.x.toByte(), value.move.y.toByte())

        override fun decode(bytes: ByteArray): DemoInput {
            require(bytes.size == 2)
            return DemoInput(Vec2i(bytes[0].toInt(), bytes[1].toInt()))
        }
    }

    @Test
    fun hostAndClientsConverge() {
        val (c0, h0) = Loopback.createPair()
        val (c1, h1) = Loopback.createPair()

        val peers = mapOf(PlayerId(0) to h0, PlayerId(1) to h1)
        val initial = DemoState(positions = mapOf(PlayerId(0) to Vec2i(0, 0), PlayerId(1) to Vec2i(0, 0)))

        val host = LockstepHost(
            initialState = initial,
            reducer = { s, inputs -> DemoReducer.reduce(s, inputs) },
            inputCodec = DemoInputCodec,
            peers = peers,
        )

        val client0 = LockstepClient(
            playerId = PlayerId(0),
            initialState = initial,
            reducer = { s, inputs -> DemoReducer.reduce(s, inputs) },
            inputCodec = DemoInputCodec,
            pipe = c0,
        )
        val client1 = LockstepClient(
            playerId = PlayerId(1),
            initialState = initial,
            reducer = { s, inputs -> DemoReducer.reduce(s, inputs) },
            inputCodec = DemoInputCodec,
            pipe = c1,
        )

        repeat(5) { t ->
            client0.sendLocalInput(DemoInput(Vec2i(1, 0)))
            client1.sendLocalInput(DemoInput(Vec2i(0, 1)))
            host.poll()
            client0.poll()
            client1.poll()

            assertEquals(t + 1, host.tick.value.toInt())
            assertEquals(host.state, client0.state)
            assertEquals(host.state, client1.state)
        }
    }
}

