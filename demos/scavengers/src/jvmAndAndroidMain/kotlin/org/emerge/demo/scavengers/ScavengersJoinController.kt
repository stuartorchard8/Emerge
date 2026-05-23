package org.emerge.demo.scavengers

import kotlin.concurrent.thread
import org.emerge.net.api.DelegatingPipe
import org.emerge.net.tcp.Tcp
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.ecs.ParallelExecutor
import org.emerge.sim.core.physics.model.PhysicsConfig
import org.emerge.sim.core.physics.primitives.PhysicsInput
import org.emerge.sim.sync.Codec
import org.emerge.sim.sync.StateCodec
import org.emerge.sim.sync.lockstep.LockstepClient
import kotlin.time.Duration.Companion.seconds

class ScavengersJoinController(
    private val hostIp: String,
    private val port: Int,
) : ScavengersController() {
    private val cfg = PhysicsConfig()
    private val executor = ParallelExecutor()
    private val reducer = ScavengersReducer(executor)
    private val inputCodec: Codec<PhysicsInput> = ScavengersCodecs.inputCodec
    private val stateCodec: StateCodec<ScavengersState> = ScavengersCodecs.stateCodec

    private val remote = DelegatingPipe()
    private val client = LockstepClient(
        cfg = cfg,
        initialState = createDefaultInitialState(),
        reducer = reducer,
        pipe = remote,
        inputCodec = inputCodec,
        stateCodec = stateCodec,
        handshakeTimeout = 15.seconds,
        inactivityTimeout = 20.seconds,
        onDisconnected = { reason ->
            netStatus = "net: disconnected ($reason)"
        },
    )

    @Volatile private var netStatus: String = "net: init"
    private var lastLoggedStatus: String = ""

    init {
        startConnectLoop()
    }

    private fun startConnectLoop() {
        thread(isDaemon = true, name = "net-connect") {
            var attempt = 0
            while (true) {
                if (client.connectionState != LockstepClient.ConnectionState.DISCONNECTED) {
                    try {
                        Thread.sleep(50L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }

                attempt += 1
                netStatus = "net: connecting to $hostIp:$port (try $attempt)"
                println("[join] resolving $hostIp ...")
                try {
                    val resolved = java.net.InetAddress.getByName(hostIp)
                    println("[join] resolved to ${resolved.hostAddress}")
                    println("[join] TCP connect to $hostIp:$port (timeout 10s) ...")
                    val t0 = System.currentTimeMillis()
                    val pipe = Tcp.connect(host = hostIp, port = port)
                    println("[join] TCP connected in ${System.currentTimeMillis() - t0}ms")
                    remote.setDelegate(pipe)
                    netStatus = "net: connected (handshake)"
                    println("[join] sending handshake hello ...")
                    client.resetConnection("connect")
                    client.startHandshake(force = true)
                    println("[join] handshake sent, state=${client.connectionState}")
                } catch (t: Throwable) {
                    val msg = t.message?.take(80) ?: ""
                    netStatus = "net: connect failed: ${t.javaClass.simpleName} $msg"
                    println("[join] FAILED: ${t.javaClass.name}: $msg")
                    t.printStackTrace()
                    try {
                        Thread.sleep(2000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    override fun tick(localInput: PhysicsInput): ScavengersFrame {
        client.poll()
        client.sendInput(localInput)

        val currentStatus = "conn=${client.connectionState} pid=${client.playerId} tick=${client.tick.value} net=$netStatus"
        if (currentStatus != lastLoggedStatus) {
            println("[join-tick] $currentStatus")
            lastLoggedStatus = currentStatus
        }

        val state: ScavengersState = client.state
        val myId: PlayerId? = client.playerId
        val tick = client.tick.value

        return ScavengersFrame(
            state = state,
            myId = myId,
            tick = tick,
            status = netStatus,
        )
    }
}
