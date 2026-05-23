package org.emerge.demo.scavengers

import kotlin.concurrent.thread
import org.emerge.net.tcp.Tcp
import org.emerge.net.websocket.WsAcceptor
import org.emerge.sim.core.ecs.ParallelExecutor

import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import org.emerge.sim.sync.lockstep.ClientMode
import org.emerge.sim.sync.lockstep.LockstepHost
import org.emerge.net.api.Pipe

/**
 * Headless host: runs the simulation and accepts remote clients, but has no local player and
 * produces no rendering output. Useful as a dedicated server.
 */
class ScavengersHeadlessHostController(
    private val port: Int,
    private val cfg: ScavengersConfig = ScavengersConfig(),
    gameMode: GameMode = GameMode.PVP,
) : ScavengersController() {
    private val executor = ParallelExecutor()
    private val reducer = ScavengersReducer(executor)
    private val inputCodec: Codec<PhysicsInput> = ScavengersCodecs.inputCodec
    private val stateCodec: StateCodec<ScavengersState> = ScavengersCodecs.stateCodec
    private val impulseCodec: StateCodec<ScavengersState> = scavengersImpulseCodec

    private val initial: ScavengersState = createDefaultInitialState(gameMode, spawnHostPlayer = false)

    private val host = LockstepHost(
        cfg = cfg,
        initialState = initial,
        reducer = reducer,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        semiThinStateCodec = impulseCodec,
        joinPolicy = defaultJoinPolicy(gameMode),
        leavePolicy = { state, playerId -> state.removePlayerRocket(playerId) },
        thinEventsEncoder = { state -> ScavengersCodecs.crashImpactAudioEventsCodec.encode(state.crashImpactAudioEvents) },
    )

    private data class ReadyClient(val pipe: Pipe, val mode: ClientMode)

    private val readyClients = ArrayList<ReadyClient>()
    private val readyClientsLock = Any()

    @Volatile var netStatus: String = "headless host listening :$port (tcp) :${port + 1} (ws)"
        private set

    init {
        startTcpAcceptLoop()
        startWsAcceptLoop()
    }

    private fun startTcpAcceptLoop() {
        thread(isDaemon = true, name = "net-tcp-accept") {
            try {
                val listener = Tcp.listen(port = port, backlog = 8)
                println("[headless-tcp] listening on :$port")
                while (true) {
                    println("[headless-tcp] waiting for connection ...")
                    val pipe = listener.accept()
                    println("[headless-tcp] connection accepted, awaiting hello ...")
                    enqueueAfterHello(pipe)
                }
            } catch (t: Throwable) {
                println("[headless-tcp] accept loop failed: ${t::class.simpleName}: ${t.message}")
                netStatus = "tcp accept failed: ${t::class.simpleName}"
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
                netStatus = "ws accept failed: ${t::class.simpleName}"
            }
        }
    }

    private fun enqueueAfterHello(pipe: Pipe) {
        val mode = awaitHello(pipe)
        if (mode == null) {
            println("[headless] awaitHello returned null (pipe closed before hello)")
            return
        }
        println("[headless] hello received, mode=$mode")
        synchronized(readyClientsLock) {
            readyClients.add(ReadyClient(pipe, mode))
        }
    }

    override fun tick(localInput: PhysicsInput): ScavengersFrame {
        processReadyClients()
        host.pollNetwork()
        host.step()
        return ScavengersFrame(
            state = host.state,
            myId = null,
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
            netStatus = "client joined (${mode.name.lowercase()}), clients: ${host.clientCount}"
        }
    }

    companion object {
        private fun awaitHello(pipe: Pipe): ClientMode? {
            var polls = 0
            while (pipe.isOpen()) {
                val pkt = pipe.receive()
                if (pkt != null) {
                    println("[headless] received hello packet (${pkt.size} bytes) after $polls polls")
                    return LockstepHost.parseHello(pkt)
                }
                polls++
                if (polls % 5000 == 0) {
                    println("[headless] still waiting for hello ($polls polls, pipe.isOpen=${pipe.isOpen()})")
                }
                Thread.sleep(1L)
            }
            println("[headless] pipe closed while awaiting hello (after $polls polls)")
            return null
        }
    }
}
