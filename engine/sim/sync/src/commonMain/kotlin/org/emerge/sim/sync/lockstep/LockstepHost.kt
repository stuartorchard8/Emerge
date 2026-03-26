package org.emerge.sim.sync.lockstep

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec

/**
 * Hybrid lockstep / authoritative host.
 *
 * Supports two client modes simultaneously:
 *
 * - **Lockstep** clients run the simulation locally and only receive the per-tick input bundle
 *   (~17 bytes per player). They are the cheapest to serve.
 * - **Thin** clients defer simulation entirely to the host and receive periodic full-state
 *   snapshots. This is heavier on bandwidth but allows low-end devices to participate.
 *
 * Full state is always sent on join (Welcome) and on player join/leave (Resync to all clients).
 *
 * Threading: [acceptClient], [pollNetwork], [setLocalInput], and [step] must all be called from
 * the same thread (the game-loop thread). Connection acceptance (blocking on TCP) should happen on
 * a separate thread; once the Hello handshake is complete, pass the pipe and parsed [ClientMode] to
 * [acceptClient] on the main thread.
 */
class LockstepHost<C, S, I>(
    cfg: C,
    initialState: S,
    reducer: SimReducer<C, S, I>,
    private val inputCodec: Codec<I>,
    private val stateCodec: StateCodec<S>,
    private val joinPolicy: (S, PlayerId) -> Unit,
    private val leavePolicy: (S, PlayerId) -> Unit = { _, _ -> },
    private val thinSnapshotEveryTicks: Int = 1,
) {
    private data class ClientEntry(val pipe: Pipe, val mode: ClientMode)

    private val stepper = TickStepper(cfg = cfg, initialState = initialState, reducer = reducer)

    private val clientsById = LinkedHashMap<PlayerId, ClientEntry>()
    private val lastInputById = LinkedHashMap<PlayerId, I>()

    private var nextPlayerId: Int = 1

    val tick: Tick get() = stepper.tick
    val state: S get() = stepper.state
    val clientCount: Int get() = clientsById.size

    /**
     * Finalize a client that has already completed the Hello handshake.
     *
     * Applies the join policy, sends a Welcome (with full state) to the new client, and sends a
     * Resync to all existing clients so they pick up the state mutation.
     */
    fun acceptClient(pipe: Pipe, mode: ClientMode = ClientMode.LOCKSTEP): PlayerId {
        val pid = PlayerId(nextPlayerId++)
        joinPolicy(stepper.state, pid)

        val stateBytes = stateCodec.encode(stepper.state)

        pipe.send(
            LockstepProtocol.encodeWelcome(
                LockstepProtocol.Welcome(playerId = pid, tick = tick, stateBytes = stateBytes),
            ),
        )

        broadcastResync(stateBytes)

        clientsById[pid] = ClientEntry(pipe, mode)
        return pid
    }

    fun pollNetwork() {
        val disconnected = ArrayList<PlayerId>()
        for ((pid, entry) in clientsById) {
            if (!entry.pipe.isOpen()) {
                disconnected += pid
                continue
            }
            while (true) {
                val pkt = entry.pipe.receive() ?: break
                val msg = LockstepProtocol.decodeInput(pkt) ?: continue
                if (msg.playerId != pid) continue
                lastInputById[pid] = inputCodec.decode(msg.payload)
            }
            if (!entry.pipe.isOpen()) {
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
     * Broadcast to all connected clients, then advance the local simulation by one tick.
     *
     * - Lockstep clients receive a [LockstepProtocol.TickInputs] bundle.
     * - Thin clients receive a [LockstepProtocol.Resync] snapshot every [thinSnapshotEveryTicks] ticks.
     */
    fun step() {
        val inputs = LinkedHashMap<PlayerId, I>(lastInputById)

        val hasLockstepClients = clientsById.values.any { it.mode == ClientMode.LOCKSTEP }
        val hasThinClients = clientsById.values.any { it.mode == ClientMode.THIN }

        if (hasLockstepClients) {
            val encodedInputs = LinkedHashMap<PlayerId, ByteArray>(inputs.size)
            for ((pid, input) in inputs) {
                encodedInputs[pid] = inputCodec.encode(input)
            }
            val encoded = LockstepProtocol.encodeTickInputs(
                LockstepProtocol.TickInputs(tick = tick, inputs = encodedInputs),
            )
            for ((_, entry) in clientsById) {
                if (entry.mode == ClientMode.LOCKSTEP) {
                    entry.pipe.send(encoded)
                }
            }
        }

        stepper.step(inputs)

        if (hasThinClients && thinSnapshotEveryTicks > 0) {
            if ((tick.value % thinSnapshotEveryTicks.toLong()) == 0L) {
                val encoded = LockstepProtocol.encodeResync(
                    LockstepProtocol.Resync(tick = tick, stateBytes = stateCodec.encode(stepper.state)),
                )
                for ((_, entry) in clientsById) {
                    if (entry.mode == ClientMode.THIN) {
                        entry.pipe.send(encoded)
                    }
                }
            }
        }
    }

    private fun broadcastResync(stateBytes: ByteArray) {
        if (clientsById.isEmpty()) return
        val resync = LockstepProtocol.Resync(tick = tick, stateBytes = stateBytes)
        val encoded = LockstepProtocol.encodeResync(resync)
        for ((_, entry) in clientsById) {
            entry.pipe.send(encoded)
        }
    }

    companion object {
        /**
         * Returns true if [packet] is a valid Hello handshake message.
         */
        fun isHello(packet: ByteArray): Boolean = parseHello(packet) != null

        /**
         * Parses a Hello packet and returns the requested [ClientMode], or null if invalid.
         */
        fun parseHello(packet: ByteArray): ClientMode? =
            LockstepProtocol.decodeHello(packet)?.clientMode
    }
}
