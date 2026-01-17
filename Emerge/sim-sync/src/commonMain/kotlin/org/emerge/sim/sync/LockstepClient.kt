package org.emerge.sim.sync

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper

/**
 * Lockstep client:
 * - Sends its input for the current tick to the host.
 * - Waits for host TickBundleFrame(tick) then advances local simulation.
 */
class LockstepClient<S, I>(
    private val playerId: PlayerId,
    initialState: S,
    reducer: (S, Map<PlayerId, I>) -> S,
    private val inputCodec: Codec<I>,
    private val pipe: Pipe,
) {
    private val stepper = TickStepper(initialState = initialState, reducer = { s, inputs -> reducer(s, inputs) })
    private var sentForTick: Tick? = null

    val tick: Tick get() = stepper.tick
    val state: S get() = stepper.state

    fun sendLocalInput(input: I) {
        if (sentForTick == tick) return
        val payload = inputCodec.encode(input)
        val frame = InputFrame(tick = tick, player = playerId, payload = payload)
        pipe.send(Frames.encodeInput(frame))
        sentForTick = tick
    }

    fun poll() {
        while (true) {
            val pkt = pipe.receive() ?: break
            val bundle = Frames.decodeBundle(pkt) ?: continue
            if (bundle.tick != tick) continue

            val decoded: Map<PlayerId, I> = bundle.inputs.mapValues { (_, payload) -> inputCodec.decode(payload) }
            stepper.step(decoded)
            sentForTick = null
        }
    }
}

