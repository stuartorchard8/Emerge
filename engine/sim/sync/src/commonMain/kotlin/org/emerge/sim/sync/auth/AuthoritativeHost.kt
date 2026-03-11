package org.emerge.sim.sync.auth

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.platform.sleepMillis

/**
 * Server-authoritative simulation host.
 *
 * - Runs the sim continuously (does not stall waiting for client inputs).
 * - Clients can join at any time. On join, host assigns a PlayerId and can mutate state (spawn body).
 * - Host broadcasts periodic snapshots.
 */
class AuthoritativeHost<C, S, I>(
    cfg: C,
    initialState: S,
    reducer: (C, S, Map<PlayerId, I>) -> S,
    private val inputCodec: Codec<I>,
    private val stateCodec: StateCodec<S>,
    private val joinPolicy: (S, PlayerId) -> S,
    private val leavePolicy: (S, PlayerId) -> S = { state, _ -> state },
    private val snapshotEveryTicks: Int = 1,
) {
    private val stepper = TickStepper(cfg = cfg, initialState = initialState, reducer = { c, s, inputs -> reducer(c, s, inputs) })

    private val clientsById = LinkedHashMap<PlayerId, Pipe>()
    private val lastInputById = LinkedHashMap<PlayerId, I>()

    private var nextPlayerId: Int = 1 // reserve 0 for local host player by convention

    val tick: Tick get() = stepper.tick
    val state: S get() = stepper.state

    /**
     * Add a remote client connection. This will block until the client sends HELLO, then sends WELCOME.
     */
    fun acceptClient(pipe: Pipe): PlayerId? {
        // wait for hello
        var pkt: ByteArray?
        do {
            pkt = pipe.receive()
            if (pkt == null) {
                // Avoid a hot spin; Pipe is polling-based.
                sleepMillis(1L)
            }
        } while (pkt == null)

        val hello = AuthProtocol.decodeHello(pkt) ?: return null
        @Suppress("UNUSED_VARIABLE")
        val _unused = hello

        val pid = PlayerId(nextPlayerId++)

        // mutate state to add player-controlled entity
        stepper.replaceState(joinPolicy(stepper.state, pid))

        clientsById[pid] = pipe

        // send welcome with snapshot
        val welcome = AuthProtocol.Welcome(
            playerId = pid,
            tick = tick,
            stateBytes = stateCodec.encode(stepper.state),
        )
        pipe.send(AuthProtocol.encodeWelcome(welcome))
        return pid
    }

    /**
     * Polls all client pipes for new inputs and updates last-known input per player.
     */
    fun pollNetwork() {
        val disconnected = ArrayList<PlayerId>()
        for ((pid, pipe) in clientsById) {
            if (!pipe.isOpen()) {
                disconnected += pid
                continue
            }
            while (true) {
                val pkt = pipe.receive() ?: break
                val msg = AuthProtocol.decodeInput(pkt) ?: continue
                if (msg.playerId != pid) continue
                lastInputById[pid] = inputCodec.decode(msg.payload)
            }
            if (!pipe.isOpen()) {
                disconnected += pid
            }
        }
        for (pid in disconnected) {
            clientsById.remove(pid)
            lastInputById.remove(pid)
            stepper.replaceState(leavePolicy(stepper.state, pid))
        }
    }

    /**
     * Set/override host-local player input (conventionally PlayerId(0)).
     */
    fun setLocalInput(playerId: PlayerId, input: I) {
        lastInputById[playerId] = input
    }

    /**
     * Advance simulation by one tick, then broadcast snapshots if configured.
     */
    fun step() {
        val inputs = LinkedHashMap<PlayerId, I>(lastInputById)
        stepper.step(inputs)

        if (snapshotEveryTicks <= 0) return
        if ((tick.value % snapshotEveryTicks.toLong()) != 0L) return

        val snap = AuthProtocol.Snapshot(tick = tick, stateBytes = stateCodec.encode(stepper.state))
        val encoded = AuthProtocol.encodeSnapshot(snap)
        for ((_, pipe) in clientsById) {
            pipe.send(encoded)
        }
    }
}

