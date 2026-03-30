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
    private val impulseCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.impulseCodec

    private val initial: PhysicsState = createDefaultInitialState(gameMode)

    private val host = LockstepHost(
        cfg = cfg,
        initialState = initial,
        reducer = reducer,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        semiThinStateCodec = impulseCodec,
        joinPolicy = defaultJoinPolicy(gameMode),
        leavePolicy = { state, playerId -> state.removePlayerRocket(playerId) },
        thinEventsEncoder = { state -> PhysicsNetCodecs.crashImpactAudioEventsCodec.encode(state.raw.crashImpactAudioEvents) },
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
                println("[host-tcp] listening on :$port")
                while (true) {
                    println("[host-tcp] waiting for connection ...")
                    val pipe = listener.accept()
                    println("[host-tcp] connection accepted, awaiting hello ...")
                    enqueueAfterHello(pipe)
                }
            } catch (t: Throwable) {
                println("[host-tcp] accept loop failed: ${t::class.simpleName}: ${t.message}")
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
        val mode = awaitHello(pipe)
        if (mode == null) {
            println("[host] awaitHello returned null (pipe closed before hello)")
            return
        }
        println("[host] hello received, mode=$mode")
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
            var polls = 0
            while (pipe.isOpen()) {
                val pkt = pipe.receive()
                if (pkt != null) {
                    println("[host] received hello packet (${pkt.size} bytes) after $polls polls")
                    return LockstepHost.parseHello(pkt)
                }
                polls++
                if (polls % 5000 == 0) {
                    println("[host] still waiting for hello (${polls} polls, pipe.isOpen=${pipe.isOpen()})")
                }
                Thread.sleep(1L)
            }
            println("[host] pipe closed while awaiting hello (after $polls polls)")
            return null
        }
    }
}
