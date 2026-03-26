package org.emerge.sim.sync.lockstep

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.SimReducer
import org.emerge.sim.core.Tick
import org.emerge.sim.core.TickStepper
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Deterministic lockstep client.
 *
 * Connects to a [LockstepHost], sends its own input each tick, and advances the local simulation
 * whenever a TickInputs bundle arrives from the host. The simulation is never replaced wholesale
 * except on the initial Welcome or an explicit Resync (player join/leave).
 */
class LockstepClient<C, S, I>(
    cfg: C,
    initialState: S,
    reducer: SimReducer<C, S, I>,
    private val pipe: Pipe,
    private val inputCodec: Codec<I>,
    private val stateCodec: StateCodec<S>,
    private val handshakeTimeout: Duration = 5.seconds,
    private val inactivityTimeout: Duration = 5.seconds,
    private val onDisconnected: ((reason: String) -> Unit)? = null,
) {
    enum class ConnectionState {
        DISCONNECTED,
        HANDSHAKING,
        CONNECTED,
    }

    private val stepper = TickStepper(cfg = cfg, initialState = initialState, reducer = reducer)

    @kotlin.concurrent.Volatile
    var playerId: PlayerId? = null
        private set

    val tick: Tick get() = stepper.tick
    val state: S get() = stepper.state

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
        pipe.send(LockstepProtocol.encodeHello(LockstepProtocol.Hello(clientMode = ClientMode.LOCKSTEP)))
        handshakeSentAt = timeSource.markNow()
        connectionState = ConnectionState.HANDSHAKING
    }

    /**
     * Drain all pending packets from the host.
     *
     * - Welcome → reset simulation state to the host's state (initial join).
     * - Resync  → reset simulation state (player join/leave caused a state mutation).
     * - TickInputs → decode the input bundle and step the local simulation.
     *
     * Multiple TickInputs in a single poll call are each applied in order, allowing the client
     * to catch up if it falls behind.
     */
    fun poll() {
        while (true) {
            val pkt = pipe.receive() ?: break
            lastPacketAt = timeSource.markNow()

            val msg = LockstepProtocol.decode(pkt) ?: continue
            when (msg) {
                is LockstepProtocol.Welcome -> handleWelcome(msg)
                is LockstepProtocol.Resync -> handleResync(msg)
                is LockstepProtocol.TickInputs -> handleTickInputs(msg)
                else -> {}
            }
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

    private fun handleWelcome(msg: LockstepProtocol.Welcome) {
        playerId = msg.playerId
        val decoded = try {
            stateCodec.decode(msg.stateBytes)
        } catch (t: Throwable) {
            disconnect("invalid welcome state: ${t::class.simpleName}")
            return
        }
        stepper.reset(decoded, msg.tick)
        connectionState = ConnectionState.CONNECTED
    }

    private fun handleResync(msg: LockstepProtocol.Resync) {
        val decoded = try {
            stateCodec.decode(msg.stateBytes)
        } catch (t: Throwable) {
            disconnect("invalid resync state: ${t::class.simpleName}")
            return
        }
        stepper.reset(decoded, msg.tick)
    }

    private fun handleTickInputs(msg: LockstepProtocol.TickInputs) {
        if (connectionState != ConnectionState.CONNECTED) return
        val inputs = LinkedHashMap<PlayerId, I>(msg.inputs.size)
        for ((pid, bytes) in msg.inputs) {
            inputs[pid] = inputCodec.decode(bytes)
        }
        stepper.step(inputs)
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
