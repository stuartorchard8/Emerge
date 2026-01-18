package org.emerge.sim.sync

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper

/**
 * Lockstep host:
 * - Receives one InputFrame per player per tick.
 * - When all inputs for current tick are present, broadcasts a TickBundleFrame.
 * - Advances local simulation.
 *
 * This keeps the sim deterministic while leaving the transport + input encoding modular.
 */
class LockstepHost<S, I>(
    initialState: S,
    reducer: (S, Map<PlayerId, I>) -> S,
    private val inputCodec: Codec<I>,
    private val peers: Map<PlayerId, Pipe>,
) {
    private val stepper = TickStepper(initialState = initialState, reducer = { s, inputs -> reducer(s, inputs) })

    private val pendingInputs: MutableMap<PlayerId, ByteArray> = LinkedHashMap()

    val tick: Tick get() = stepper.tick
    val state: S get() = stepper.state

    fun poll() {
        // receive inputs for the current tick
        for ((playerId, pipe) in peers) {
            while (true) {
                val pkt = pipe.receive() ?: break
                val frame = Frames.decodeInput(pkt) ?: continue
                if (frame.tick != tick) continue
                if (frame.player != playerId) continue
                pendingInputs[playerId] = frame.payload
            }
        }

        if (pendingInputs.size != peers.size) return

        // bundle + broadcast
        val bundle = TickBundleFrame(tick = tick, inputs = LinkedHashMap(pendingInputs))
        val encoded = Frames.encodeBundle(bundle)
        for ((_, pipe) in peers) pipe.send(encoded)

        // advance local sim
        val decodedInputs: Map<PlayerId, I> = pendingInputs.mapValues { (_, payload) -> inputCodec.decode(payload) }
        stepper.step(decodedInputs)
        pendingInputs.clear()
    }
}

