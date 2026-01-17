package org.emerge.sim.sync.auth

import org.emerge.net.api.Pipe
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick
import org.emerge.sim.sync.Codec

/**
 * Server-authoritative client:
 * - Connects, sends HELLO, waits for WELCOME (assigned PlayerId + initial snapshot).
 * - Sends inputs continuously (no tick coupling).
 * - Applies latest snapshots from host.
 */
class AuthoritativeClient<S, I>(
    private val pipe: Pipe,
    private val inputCodec: Codec<I>,
    private val stateCodec: StateCodec<S>,
) {
    var playerId: PlayerId? = null
        private set

    var tick: Tick = Tick(0)
        private set

    var state: S? = null
        private set

    private var connected: Boolean = false

    fun startHandshake() {
        if (connected) return
        pipe.send(AuthProtocol.encodeHello(AuthProtocol.Hello()))
        connected = true
    }

    fun poll() {
        while (true) {
            val pkt = pipe.receive() ?: break
            val welcome = AuthProtocol.decodeWelcome(pkt)
            if (welcome != null) {
                playerId = welcome.playerId
                tick = welcome.tick
                state = stateCodec.decode(welcome.stateBytes)
                continue
            }
            val snap = AuthProtocol.decodeSnapshot(pkt)
            if (snap != null) {
                tick = snap.tick
                state = stateCodec.decode(snap.stateBytes)
                continue
            }
        }
    }

    fun sendInput(input: I) {
        val pid = playerId ?: return
        val payload = inputCodec.encode(input)
        pipe.send(AuthProtocol.encodeInput(AuthProtocol.InputMsg(pid, payload)))
    }
}

