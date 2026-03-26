package org.emerge.sim.sync.lockstep

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec

/**
 * Deterministic lockstep host.
 *
 * Each tick the host collects all players' inputs, broadcasts the full input set to every client,
 * then advances its own simulation. Clients run the same reducer locally with the received inputs,
 * keeping all machines in sync without transmitting world-state snapshots every frame.
 *
 * Full state is only sent on join (Welcome) and on player join/leave (Resync) so that all clients
 * incorporate the state mutation from the join/leave policy.
 *
 * Threading: [acceptClient], [pollNetwork], [setLocalInput], and [step] must all be called from
 * the same thread (the game-loop thread). Connection acceptance (blocking on TCP) should happen on
 * a separate thread; once the Hello handshake is complete, pass the pipe to [acceptClient] on the
 * main thread.
 */
class LockstepHost<C, S, I>(
    cfg: C,
    initialState: S,
    reducer: SimReducer<C, S, I>,
    private val inputCodec: Codec<I>,
    private val stateCodec: StateCodec<S>,
    private val joinPolicy: (S, PlayerId) -> Unit,
    private val leavePolicy: (S, PlayerId) -> Unit = { _, _ -> },
) {
    private val stepper = TickStepper(cfg = cfg, initialState = initialState, reducer = reducer)

    private val clientsById = LinkedHashMap<PlayerId, Pipe>()
    private val lastInputById = LinkedHashMap<PlayerId, I>()

    private var nextPlayerId: Int = 1

    val tick: Tick get() = stepper.tick
    val state: S get() = stepper.state

    /**
     * Finalize a client that has already completed the Hello handshake.
     *
     * Applies the join policy, sends a Welcome (with full state) to the new client, and sends a
     * Resync to all existing clients so they pick up the state mutation.
     */
    fun acceptClient(pipe: Pipe): PlayerId {
        val pid = PlayerId(nextPlayerId++)
        joinPolicy(stepper.state, pid)

        val stateBytes = stateCodec.encode(stepper.state)

        pipe.send(
            LockstepProtocol.encodeWelcome(
                LockstepProtocol.Welcome(playerId = pid, tick = tick, stateBytes = stateBytes),
            ),
        )

        broadcastResync(stateBytes)

        clientsById[pid] = pipe
        return pid
    }

    fun pollNetwork() {
        val disconnected = ArrayList<PlayerId>()
        for ((pid, pipe) in clientsById) {
            if (!pipe.isOpen()) {
                disconnected += pid
                continue
            }
            while (true) {
                val pkt = pipe.receive() ?: break
                val msg = LockstepProtocol.decodeInput(pkt) ?: continue
                if (msg.playerId != pid) continue
                lastInputById[pid] = inputCodec.decode(msg.payload)
            }
            if (!pipe.isOpen()) {
                disconnected += pid
            }
        }
        if (disconnected.isNotEmpty()) {
            for (pid in disconnected) {
                clientsById.remove(pid)
                lastInputById.remove(pid)
                leavePolicy(stepper.state, pid)
            }
            broadcastResync(stateCodec.encode(stepper.state))
        }
    }

    fun setLocalInput(playerId: PlayerId, input: I) {
        lastInputById[playerId] = input
    }

    /**
     * Broadcast all collected inputs to every client, then advance the local simulation by one tick.
     */
    fun step() {
        val inputs = LinkedHashMap<PlayerId, I>(lastInputById)

        val encodedInputs = LinkedHashMap<PlayerId, ByteArray>(inputs.size)
        for ((pid, input) in inputs) {
            encodedInputs[pid] = inputCodec.encode(input)
        }
        val tickInputs = LockstepProtocol.TickInputs(tick = tick, inputs = encodedInputs)
        val encoded = LockstepProtocol.encodeTickInputs(tickInputs)
        for ((_, pipe) in clientsById) {
            pipe.send(encoded)
        }

        stepper.step(inputs)
    }

    private fun broadcastResync(stateBytes: ByteArray) {
        if (clientsById.isEmpty()) return
        val resync = LockstepProtocol.Resync(tick = tick, stateBytes = stateBytes)
        val encoded = LockstepProtocol.encodeResync(resync)
        for ((_, pipe) in clientsById) {
            pipe.send(encoded)
        }
    }

    companion object {
        /**
         * Returns true if [packet] is a valid Hello handshake message.
         * Useful for accept threads that need to complete the Hello exchange before handing
         * the pipe to [acceptClient] on the main thread.
         */
        fun isHello(packet: ByteArray): Boolean = LockstepProtocol.decodeHello(packet) != null
    }
}
