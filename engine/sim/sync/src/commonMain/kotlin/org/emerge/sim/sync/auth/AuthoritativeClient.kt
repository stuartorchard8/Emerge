package org.emerge.sim.sync.auth

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.sync.Codec
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.seconds

/**
 * Server-authoritative client:
 * - Connects, sends HELLO, waits for WELCOME (assigned PlayerId + initial snapshot).
 * - Sends inputs continuously (no tick coupling).
 * - Applies latest snapshots from host.
 */
class AuthoritativeClient<S, I>(
    initialState: S,
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

    var playerId: PlayerId? = null
        private set

    var tick: Tick = Tick(0)
        private set

    var state: S = initialState
        private set

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    var lastDisconnectReason: String? = null
        private set

    private var lastPacketAt: TimeMark? = null
    private var handshakeSentAt: TimeMark? = null

    private val timeSource: TimeSource = TimeSource.Monotonic

    fun startHandshake(force: Boolean = false) {
        if (!force && connectionState != ConnectionState.DISCONNECTED) return
        pipe.send(AuthProtocol.encodeHello(AuthProtocol.Hello()))
        handshakeSentAt = timeSource.markNow()
        connectionState = ConnectionState.HANDSHAKING
    }

    fun poll() {
        while (true) {
            val pkt = pipe.receive() ?: break
            lastPacketAt = timeSource.markNow()
            val welcome = AuthProtocol.decodeWelcome(pkt)
            if (welcome != null) {
                playerId = welcome.playerId
                tick = welcome.tick
                val decodedState =
                    try {
                        stateCodec.decode(welcome.stateBytes)
                    } catch (t: Throwable) {
                        disconnect("invalid welcome state: ${t.javaClass.simpleName}")
                        continue
                    }
                state = decodedState
                connectionState = ConnectionState.CONNECTED
                continue
            }
            val snap = AuthProtocol.decodeSnapshot(pkt)
            if (snap != null) {
                tick = snap.tick
                val decodedState =
                    try {
                        stateCodec.decode(snap.stateBytes)
                    } catch (t: Throwable) {
                        disconnect("invalid snapshot: ${t.javaClass.simpleName}")
                        continue
                    }
                state = decodedState
                if (playerId != null) connectionState = ConnectionState.CONNECTED
                continue
            }
        }

        checkTimeouts()
    }

    fun sendInput(input: I) {
        if (connectionState != ConnectionState.CONNECTED) return
        val pid = playerId ?: return
        val payload = inputCodec.encode(input)
        pipe.send(AuthProtocol.encodeInput(AuthProtocol.InputMsg(pid, payload)))
    }

    /**
     * Clears handshake + assigned id so the caller can reconnect and re-handshake on the same [Pipe]
     * (e.g. a delegating pipe that swaps TCP connections underneath).
     */
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

