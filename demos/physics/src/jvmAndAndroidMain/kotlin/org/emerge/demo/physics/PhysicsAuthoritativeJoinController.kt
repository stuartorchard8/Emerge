package org.emerge.demo.physics

import kotlin.concurrent.thread
import org.emerge.net.api.DelegatingPipe
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.AuthoritativeClient
import org.emerge.sim.sync.auth.StateCodec

class PhysicsAuthoritativeJoinController(
    private val hostIp: String,
    private val port: Int,
) : PhysicsAuthoritativeController() {
    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    private val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    private val remote = DelegatingPipe()
    private val client = AuthoritativeClient(
        initialState = createDefaultInitialState(),
        pipe = remote,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        onDisconnected = { reason ->
            netStatus = "net: disconnected ($reason)"
        },
    )

    @Volatile private var netStatus: String = "net: init"

    init {
        startConnectLoop()
    }

    private fun startConnectLoop() {
        thread(isDaemon = true, name = "net-connect") {
            var attempt = 0
            while (true) {
                // Important: only attempt (re)connect when the client is fully disconnected.
                // If we swap the underlying pipe while handshaking, we can miss the WELCOME packet.
                if (client.connectionState != AuthoritativeClient.ConnectionState.DISCONNECTED) {
                    try {
                        Thread.sleep(50L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                attempt += 1
                netStatus = "net: connecting to $hostIp:$port (try $attempt)"
                try {
                    remote.setDelegate(Tcp.connect(host = hostIp, port = port))
                    netStatus = "net: connected (handshake)"
                    client.resetConnection("connect")
                    client.startHandshake(force = true) // sets HANDSHAKING
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

    override fun tick(localInput: PhysicsInput): PhysicsFrame {
        client.poll()
        client.sendInput(localInput)

        val state: PhysicsState = client.state
        val myId: PlayerId? = client.playerId
        val tick = client.tick.value

        return PhysicsFrame(
            state = state,
            myId = myId,
            tick = tick,
            status = netStatus,
        )
    }
}

