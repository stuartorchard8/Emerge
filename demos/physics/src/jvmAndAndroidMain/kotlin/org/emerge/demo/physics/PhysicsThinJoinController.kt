package org.emerge.demo.physics

import kotlin.concurrent.thread
import org.emerge.net.api.DelegatingPipe
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.model.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import org.emerge.sim.sync.lockstep.ThinClient
import kotlin.time.Duration.Companion.seconds

class PhysicsThinJoinController(
    private val hostIp: String,
    private val port: Int,
) : PhysicsController() {
    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    private val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    private val remote = DelegatingPipe()
    private val client = ThinClient(
        initialState = createDefaultInitialState(),
        pipe = remote,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        thinEventsApplier = { state, payload ->
            val events = PhysicsNetCodecs.crashImpactAudioEventsCodec.decode(payload)
            state.copy(crashImpactAudioEvents = events)
        },
        handshakeTimeout = 15.seconds,
        inactivityTimeout = 20.seconds,
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
                if (client.connectionState != ThinClient.ConnectionState.DISCONNECTED) {
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
                    client.startHandshake(force = true)
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
