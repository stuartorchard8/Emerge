package org.emerge.demo.physics

import kotlin.concurrent.thread
import org.emerge.net.tcp.Tcp
import org.emerge.sim.codec.physics.PhysicsNetCodecs
import org.emerge.sim.core.physics.PhysicsConfig
import org.emerge.sim.core.physics.PhysicsReducer
import org.emerge.sim.core.physics.PhysicsState
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
class PhysicsHeadlessHostController(
    private val port: Int,
    private val cfg: PhysicsConfig = PhysicsConfig(),
    private val gameMode: GameMode = GameMode.PVP,
) : PhysicsController() {
    private val reducer = PhysicsReducer()
    private val inputCodec: Codec<PhysicsInput> = PhysicsNetCodecs.inputCodec
    private val stateCodec: StateCodec<PhysicsState> = PhysicsNetCodecs.stateCodec

    private val initial: PhysicsState = createDefaultInitialState(gameMode, spawnHostPlayer = false)

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

    @Volatile var netStatus: String = "headless host listening :$port"
        private set

    init {
        startAcceptLoop()
    }

    private fun startAcceptLoop() {
        thread(isDaemon = true, name = "net-accept-loop") {
            try {
                val listener = Tcp.listen(port = port, backlog = 8)
                while (true) {
                    val pipe = listener.accept()
                    val mode = awaitHello(pipe)
                    if (mode != null) {
                        synchronized(readyClientsLock) {
                            readyClients.add(ReadyClient(pipe, mode))
                        }
                    }
                }
            } catch (t: Throwable) {
                netStatus = "accept failed: ${t.javaClass.simpleName}"
            }
        }
    }

    override fun tick(localInput: PhysicsInput): PhysicsFrame {
        processReadyClients()
        host.pollNetwork()
        host.step()
        return PhysicsFrame(
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
