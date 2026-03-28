package org.emerge.sim.sync.lockstep

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Thin (server-authoritative) client.
 *
 * Defers the entire simulation to the host. The client sends its inputs and receives periodic
 * full-state snapshots — no local physics stepping required. This allows low-end devices to
 * participate at the cost of higher bandwidth and one extra round-trip of perceived latency.
 */
class ThinClient<S, I>(
    initialState: S,
    private val pipe: Pipe,
    private val inputCodec: Codec<I>,
    private val stateCodec: StateCodec<S>,
    private val thinEventsApplier: ((S, ByteArray) -> S)? = null,
    private val handshakeTimeout: Duration = 5.seconds,
    private val inactivityTimeout: Duration = 5.seconds,
    private val onDisconnected: ((reason: String) -> Unit)? = null,
) {
    enum class ConnectionState {
        DISCONNECTED,
        HANDSHAKING,
        CONNECTED,
    }

    @kotlin.concurrent.Volatile
    var playerId: PlayerId? = null
        private set

    @kotlin.concurrent.Volatile
    var tick: Tick = Tick(0)
        private set

    @kotlin.concurrent.Volatile
    var state: S = initialState
        private set

    @kotlin.concurrent.Volatile
    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @kotlin.concurrent.Volatile
    var lastDisconnectReason: String? = null
        private set

    private var lastPacketAt: TimeMark? = null
    private var handshakeSentAt: TimeMark? = null

    private val timeSource: TimeSource = TimeSource.Monotonic

    fun startHandshake(force: Boolean = false) {
        if (!force && connectionState != ConnectionState.DISCONNECTED) return
        pipe.send(LockstepProtocol.encodeHello(LockstepProtocol.Hello(clientMode = ClientMode.THIN)))
        handshakeSentAt = timeSource.markNow()
        connectionState = ConnectionState.HANDSHAKING
    }

    /**
     * Drain all pending packets from the host.
     *
     * - Welcome → replace state (initial join).
     * - Resync  → replace state (periodic snapshot or player join/leave).
     * - TickInputs are ignored — the thin client does not run the simulation.
     */
    fun poll() {
        var latestWelcome: LockstepProtocol.Welcome? = null
        var latestResync: LockstepProtocol.Resync? = null
        var latestThinEvents: LockstepProtocol.ThinEvents? = null
        while (true) {
            val pkt = pipe.receive() ?: break
            lastPacketAt = timeSource.markNow()

            val msg = LockstepProtocol.decode(pkt) ?: continue
            when (msg) {
                is LockstepProtocol.Welcome -> latestWelcome = msg
                is LockstepProtocol.Resync -> latestResync = msg
                is LockstepProtocol.ThinEvents -> latestThinEvents = msg
                else -> {}
            }
        }

        if (latestWelcome != null) {
            val welcome = latestWelcome
            playerId = welcome.playerId
            val decoded = try {
                stateCodec.decode(welcome.stateBytes)
            } catch (t: Throwable) {
                disconnect("invalid welcome state: ${t::class.simpleName}")
                checkTimeouts()
                return
            }
            tick = welcome.tick
            state = decoded
            connectionState = ConnectionState.CONNECTED
        }

        if (latestResync != null) {
            val resync = latestResync
            val decoded = try {
                stateCodec.decode(resync.stateBytes)
            } catch (t: Throwable) {
                disconnect("invalid resync state: ${t::class.simpleName}")
                checkTimeouts()
                return
            }
            tick = resync.tick
            state = decoded
            if (playerId != null) connectionState = ConnectionState.CONNECTED
        }

        if (latestThinEvents != null && thinEventsApplier != null) {
            val thinEvents = latestThinEvents
            tick = thinEvents.tick
            state = thinEventsApplier.invoke(state, thinEvents.payload)
            if (playerId != null) connectionState = ConnectionState.CONNECTED
        }

        checkTimeouts()
    }

    fun sendInput(input: I) {
        if (connectionState != ConnectionState.CONNECTED) return
        val pid = playerId ?: return
        val payload = inputCodec.encode(input)
        pipe.send(LockstepProtocol.encodeInput(LockstepProtocol.InputMsg(pid, payload)))
    }

    fun resetConnection(reason: String = "reset") {
        playerId = null
        connectionState = ConnectionState.DISCONNECTED
        lastDisconnectReason = reason
        handshakeSentAt = null
        lastPacketAt = null
    }

    private fun checkTimeouts() {
        when (connectionState) {
            ConnectionState.DISCONNECTED -> return
            ConnectionState.HANDSHAKING -> {
                val sentAt = handshakeSentAt ?: return
                if (sentAt.elapsedNow() > handshakeTimeout) {
                    disconnect("handshake timeout")
                }
            }
            ConnectionState.CONNECTED -> {
                val last = lastPacketAt ?: return
                if (last.elapsedNow() > inactivityTimeout) {
                    disconnect("inactivity timeout")
                }
            }
        }
    }

    private fun disconnect(reason: String) {
        playerId = null
        connectionState = ConnectionState.DISCONNECTED
        lastDisconnectReason = reason
        handshakeSentAt = null
        lastPacketAt = null
        onDisconnected?.invoke(reason)
    }
}
