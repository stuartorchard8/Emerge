package org.emerge.demo.physics

import kotlin.concurrent.thread
import org.emerge.net.tcp.Tcp
import org.emerge.net.websocket.WsAcceptor
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import org.emerge.sim.sync.lockstep.ClientMode
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

    private data class ReadyClient(val pipe: Pipe, val mode: ClientMode)

    private val readyClients = ArrayList<ReadyClient>()
    private val readyClientsLock = Any()

    @Volatile private var netStatus: String =
        if (acceptRemoteClients) "net: host listening :$port (tcp) :${port + 1} (ws)" else "net: host-only (no join)"

    init {
        if (acceptRemoteClients) {
            startTcpAcceptLoop()
            startWsAcceptLoop()
        }
    }

    private fun startTcpAcceptLoop() {
        thread(isDaemon = true, name = "net-tcp-accept") {
            try {
                val listener = Tcp.listen(port = port, backlog = 8)
                while (true) {
                    val pipe = listener.accept()
                    enqueueAfterHello(pipe)
                }
            } catch (t: Throwable) {
                netStatus = "net: tcp accept failed: ${t::class.simpleName}"
            }
        }
    }

    private fun startWsAcceptLoop() {
        thread(isDaemon = true, name = "net-ws-accept") {
            try {
                val ws = WsAcceptor(port + 1)
                while (true) {
                    val pipe = ws.accept()
                    enqueueAfterHello(pipe)
                }
            } catch (t: Throwable) {
                netStatus = "net: ws accept failed: ${t::class.simpleName}"
            }
        }
    }

    private fun enqueueAfterHello(pipe: Pipe) {
        val mode = awaitHello(pipe) ?: return
        synchronized(readyClientsLock) {
            readyClients.add(ReadyClient(pipe, mode))
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
        val snapshot: List<ReadyClient>
        synchronized(readyClientsLock) {
            if (readyClients.isEmpty()) return
            snapshot = ArrayList(readyClients)
            readyClients.clear()
        }
        for ((pipe, mode) in snapshot) {
            host.acceptClient(pipe, mode)
            netStatus = "net: client joined (${mode.name.lowercase()})"
        }
    }

    companion object {
        private fun awaitHello(pipe: Pipe): ClientMode? {
            while (pipe.isOpen()) {
                val pkt = pipe.receive()
                if (pkt != null) {
                    return LockstepHost.parseHello(pkt)
                }
                Thread.sleep(1L)
            }
            return null
        }
    }
}
