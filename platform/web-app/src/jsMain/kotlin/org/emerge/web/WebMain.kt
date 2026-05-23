package org.emerge.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.emerge.demo.scavengers.GameMode
import org.emerge.demo.scavengers.ScavengersCodecs
import org.emerge.demo.scavengers.ScavengersConfig
import org.emerge.demo.scavengers.ScavengersFrame
import org.emerge.demo.scavengers.ScavengersReducer
import org.emerge.demo.scavengers.audio.CrashAudioSystem
import org.emerge.demo.scavengers.createDefaultInitialState
import org.emerge.net.websocket.WebSocketPipe
import org.emerge.render.torus.GPU
import org.emerge.render.torus.ScreenRenderer
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.primitives.Vec2
import org.emerge.sim.sync.lockstep.ThinClient
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.url.URL
import kotlin.time.Duration.Companion.seconds

fun main() {
    val canvas = document.getElementById("canvas") as HTMLCanvasElement
    val gl = canvas.getContext("webgl2") ?: error("WebGL2 not supported")
    GPU.init(gl)

    fun syncCanvasSize() {
        val dpr = window.devicePixelRatio
        canvas.width = (window.innerWidth * dpr).toInt()
        canvas.height = (window.innerHeight * dpr).toInt()
    }

    syncCanvasSize()

    val renderer = ScreenRenderer(Vec2(1f, 1f))
    renderer.setResolution(Vec2(canvas.width.toFloat(), canvas.height.toFloat()))
    val input = WebInputHandler()

    window.addEventListener("resize", {
        syncCanvasSize()
        renderer.setResolution(Vec2(canvas.width.toFloat(), canvas.height.toFloat()))
    })

    val crashAudio = CrashAudioSystem(WebCrashAudioEngine())

    val params = URL(window.location.href).searchParams
    val mode = params.get("mode") ?: "local"

    when (mode) {
        "join" -> {
            val host = params.get("host") ?: "ws://localhost:7778"
            startJoinMode(host, renderer, input, crashAudio)
        }
        else -> startLocalMode(renderer, input, crashAudio)
    }
}

private fun startLocalMode(renderer: ScreenRenderer, input: WebInputHandler, crashAudio: CrashAudioSystem) {
    val cfg = ScavengersConfig()
    var state = createDefaultInitialState(GameMode.PVP)
    val reducer = ScavengersReducer()
    val myId = PlayerId(0)
    var tick = 0L

    fun frame(ts: Double) {
        val physicsInput = input.poll(renderer)
        state = reducer.reduce(cfg, state, mapOf(myId to physicsInput))
        tick++
        crashAudio.onFrame(ScavengersFrame(state, myId, tick, ""))
        renderer.draw(state.core, myId)
        window.requestAnimationFrame(::frame)
    }
    window.requestAnimationFrame(::frame)
}

private fun startJoinMode(wsUrl: String, renderer: ScreenRenderer, input: WebInputHandler, crashAudio: CrashAudioSystem) {
    val initialState = createDefaultInitialState(GameMode.PVP)

    val pipe = org.emerge.net.api.DelegatingPipe()
    val client = ThinClient(
        initialState = initialState,
        pipe = pipe,
        inputCodec = ScavengersCodecs.inputCodec,
        stateCodec = ScavengersCodecs.stateCodec,
        thinEventsApplier = { state, payload ->
            val events = ScavengersCodecs.crashImpactAudioEventsCodec.decode(payload)
            state.copy(crashImpactAudioEvents = events)
        },
        handshakeTimeout = 15.seconds,
        inactivityTimeout = 30.seconds,
        onDisconnected = { reason ->
            console.log("Disconnected: $reason")
        },
    )

    fun connect() {
        console.log("Connecting to $wsUrl ...")
        pipe.setDelegate(WebSocketPipe(wsUrl))
        client.resetConnection("reconnect")
        client.startHandshake(force = true)
    }

    connect()

    var reconnectScheduled = false

    fun frame(ts: Double) {
        val physicsInput = input.poll(renderer)
        client.poll()
        client.sendInput(physicsInput)
        crashAudio.onFrame(ScavengersFrame(client.state, client.playerId, client.tick.value, ""))
        renderer.draw(client.state.core, client.playerId)

        if (client.connectionState == ThinClient.ConnectionState.DISCONNECTED && !reconnectScheduled) {
            reconnectScheduled = true
            window.setTimeout({
                reconnectScheduled = false
                connect()
            }, 2000)
        }

        window.requestAnimationFrame(::frame)
    }
    window.requestAnimationFrame(::frame)
}
