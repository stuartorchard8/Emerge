package org.emerge.demo.physics

import kotlin.concurrent.thread
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import org.emerge.sim.sync.lockstep.LockstepHost
import org.emerge.net.api.Pipe

class PhysicsHostController(
    private val port: Int,
    private val cfg: PhysicsConfig = PhysicsConfig(),
    private val gameMode: GameMode = GameMode.PVP,
    acceptRemoteClients: Boolean = true,
) : PhysicsController() {
    private val reducer = PhysicsReducer()
    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    private val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    private val initial: PhysicsState = createDefaultInitialState(gameMode)

    private val host = LockstepHost(
        cfg = cfg,
        initialState = initial,
        reducer = reducer,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        joinPolicy = defaultJoinPolicy(gameMode),
        leavePolicy = { state, playerId -> state.removePlayerRocket(playerId) },
    )

    /**
     * Pipes that have completed the Hello handshake on the accept thread and are waiting to be
     * processed on the main game-loop thread.
     */
    private val readyClients = ArrayList<Pipe>()
    private val readyClientsLock = Any()

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
                    if (awaitHello(pipe)) {
                        synchronized(readyClientsLock) {
                            readyClients.add(pipe)
                        }
                    }
                }
            } catch (t: Throwable) {
                netStatus = "net: accept failed: ${t.javaClass.simpleName}"
            }
        }
    }

    override fun tick(localInput: PhysicsInput): PhysicsFrame {
        processReadyClients()
        host.pollNetwork()
        host.setLocalInput(PlayerId(0), PhysicsInput(localInput.thrust, localInput.turn))
        host.step()
        return PhysicsFrame(
            state = host.state,
            myId = PlayerId(0),
            tick = host.tick.value,
            status = netStatus,
        )
    }

    private fun processReadyClients() {
        val snapshot: List<Pipe>
        synchronized(readyClientsLock) {
            if (readyClients.isEmpty()) return
            snapshot = ArrayList(readyClients)
            readyClients.clear()
        }
        for (pipe in snapshot) {
            host.acceptClient(pipe)
            netStatus = "net: client joined"
        }
    }

    companion object {
        /**
         * Block until the remote end sends a valid Hello, or the pipe closes.
         */
        private fun awaitHello(pipe: Pipe): Boolean {
            while (pipe.isOpen()) {
                val pkt = pipe.receive()
                if (pkt != null) {
                    return LockstepHost.isHello(pkt)
                }
                Thread.sleep(1L)
            }
            return false
        }
    }
}
