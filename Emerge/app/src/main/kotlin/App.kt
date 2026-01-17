package org.example.app

import org.emerge.net.loopback.Loopback
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.demo.DemoInput
import org.emerge.sim.core.demo.DemoReducer
import org.emerge.sim.core.demo.DemoState
import org.emerge.sim.core.demo.Vec2i
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.LockstepClient
import org.emerge.sim.sync.LockstepHost

fun main() {
    // Tiny single-process demo that exercises the architecture:
    // - :sim-core (deterministic reducer)
    // - :sim-sync (lockstep)
    // - :net-loopback (transport)

    val (c0, h0) = Loopback.createPair()
    val (c1, h1) = Loopback.createPair()

    val initial = DemoState(
        positions = mapOf(
            PlayerId(0) to Vec2i(0, 0),
            PlayerId(1) to Vec2i(0, 0),
        ),
    )

    val inputCodec = object : Codec<DemoInput> {
        override fun encode(value: DemoInput): ByteArray =
            byteArrayOf(value.move.x.toByte(), value.move.y.toByte())

        override fun decode(bytes: ByteArray): DemoInput {
            require(bytes.size == 2)
            return DemoInput(Vec2i(bytes[0].toInt(), bytes[1].toInt()))
        }
    }

    val host = LockstepHost(
        initialState = initial,
        reducer = { s, inputs -> DemoReducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        peers = mapOf(PlayerId(0) to h0, PlayerId(1) to h1),
    )

    val client0 = LockstepClient(
        playerId = PlayerId(0),
        initialState = initial,
        reducer = { s, inputs -> DemoReducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        pipe = c0,
    )
    val client1 = LockstepClient(
        playerId = PlayerId(1),
        initialState = initial,
        reducer = { s, inputs -> DemoReducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        pipe = c1,
    )

    repeat(10) { _ ->
        client0.sendLocalInput(DemoInput(Vec2i(1, 0)))
        client1.sendLocalInput(DemoInput(Vec2i(0, 1)))
        host.poll()
        client0.poll()
        client1.poll()

        println("tick=${host.tick.value} hostState=${host.state}")
    }
}
