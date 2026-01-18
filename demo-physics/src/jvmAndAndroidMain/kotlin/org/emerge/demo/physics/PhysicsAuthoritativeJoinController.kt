package org.emerge.demo.physics

import kotlin.concurrent.thread
import org.emerge.net.api.DelegatingPipe
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.AuthoritativeClient
import org.emerge.sim.sync.auth.StateCodec

class PhysicsAuthoritativeJoinController(
    private val hostIp: String,
    private val port: Int,
    private val cfg: PhysicsDemoConfig = PhysicsDemoConfig(),
) {
    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    private val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec
    private val initial: PhysicsState = createDefaultInitialState(cfg)

    private val remote = DelegatingPipe()
    private val client = AuthoritativeClient(
        pipe = remote,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        onDisconnected = { reason ->
            netStatus = "net: disconnected ($reason)"
        },
    )

    @Volatile private var reconnecting: Boolean = false
    @Volatile private var netStatus: String = "net: init"

    init {
        startConnectLoop()
    }

    private fun startConnectLoop() {
        thread(isDaemon = true, name = "net-connect") {
            var attempt = 0
            while (true) {
                attempt += 1
                netStatus = "net: connecting to $hostIp:$port (try $attempt)"
                try {
                    remote.setDelegate(Tcp.connect(host = hostIp, port = port))
                    netStatus = "net: connected (handshake)"
                    client.resetConnection("connect")
                    client.startHandshake(force = true)
                    break
                } catch (t: Throwable) {
                    val msg = t.message?.take(60) ?: ""
                    netStatus = "net: connect failed: ${t.javaClass.simpleName} $msg"
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    private fun startReconnect() {
        reconnecting = true
        thread(isDaemon = true, name = "net-reconnect") {
            var attempt = 0
            while (true) {
                attempt += 1
                netStatus = "net: reconnecting $hostIp:$port (try $attempt)"
                try {
                    remote.setDelegate(Tcp.connect(host = hostIp, port = port))
                    netStatus = "net: reconnected (handshake)"
                    client.resetConnection("reconnect")
                    client.startHandshake(force = true)
                    reconnecting = false
                    break
                } catch (t: Throwable) {
                    val msg = t.message?.take(60) ?: ""
                    netStatus = "net: reconnect failed: ${t.javaClass.simpleName} $msg"
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        reconnecting = false
                        break
                    }
                }
            }
        }
    }

    fun tick(localInput: PhysicsInput): AuthoritativeDemoFrame {
        client.poll()
        client.sendInput(localInput)

        if (client.connectionState == AuthoritativeClient.ConnectionState.DISCONNECTED && !reconnecting) {
            startReconnect()
        }

        val state: PhysicsState? = client.state
        val myId: PlayerId? = client.playerId
        val tick = client.tick.value

        return AuthoritativeDemoFrame(
            state = state,
            myId = myId,
            tick = tick,
            status = netStatus,
        )
    }
}

