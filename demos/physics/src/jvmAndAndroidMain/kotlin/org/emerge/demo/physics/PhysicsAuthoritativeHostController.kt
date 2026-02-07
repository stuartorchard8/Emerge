package org.emerge.demo.physics

import kotlin.concurrent.thread
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsInput
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.auth.AuthoritativeHost
import org.emerge.sim.sync.auth.StateCodec

class PhysicsAuthoritativeHostController(
    private val port: Int,
    cfg: PhysicsConfig = PhysicsConfig(),
    acceptRemoteClients: Boolean = true,
) : PhysicsAuthoritativeController() {
    private val reducer = PhysicsReducer()
    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    private val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    private val initial: PhysicsState = createDefaultInitialState(cfg)

    private val host = AuthoritativeHost(
        initialState = initial,
        reducer = { s, inputs -> reducer.reduce(s, inputs) },
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        joinPolicy = defaultJoinPolicy(cfg),
    )

    @Volatile private var netStatus: String =
        if (acceptRemoteClients) "net: host listening :$port" else "net: host-only (no join)"

    init {
        if (acceptRemoteClients) {
            startAcceptLoop()
        }
    }

    private fun startAcceptLoop() {
        thread(isDaemon = true, name = "net-accept-loop") {
            try {
                val listener = Tcp.listen(port = port, backlog = 8)
                while (true) {
                    val pipe = listener.accept()
                    host.acceptClient(pipe)
                    netStatus = "net: client joined"
                }
            } catch (t: Throwable) {
                netStatus = "net: accept failed: ${t.javaClass.simpleName}"
            }
        }
    }

    override fun tick(localInput: PhysicsInput): PhysicsFrame {
        host.pollNetwork()
        host.setLocalInput(PlayerId(0), localInput)
        host.step()
        return PhysicsFrame(
            state = host.state,
            myId = PlayerId(0),
            tick = host.tick.value,
            status = netStatus,
        )
    }
}

