package org.emerge.net.websocket

import org.emerge.net.api.Pipe
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

/**
 * Browser WebSocket [Pipe] implementation.
 *
 * Each WebSocket message maps 1:1 to a [Pipe] packet (binary frames).
 * Incoming messages are buffered in a queue for non-blocking [receive] calls.
 */
class WebSocketPipe(url: String) : Pipe {
    private val ws = WebSocket(url)
    private val inbox = ArrayDeque<ByteArray>()
    private val pendingOutbox = ArrayDeque<ByteArray>()
    private var open = false
    private var closed = false

    init {
        ws.asDynamic().binaryType = "arraybuffer"

        ws.onopen = { _: Event ->
            open = true
            while (pendingOutbox.isNotEmpty()) {
                val pkt = pendingOutbox.removeFirst()
                sendRaw(pkt)
            }
        }
        ws.onclose = { _: Event ->
            open = false
            closed = true
        }
        ws.onerror = { _: Event ->
            open = false
            closed = true
        }
        ws.onmessage = { event: dynamic ->
            val data: ArrayBuffer = event.data
            val view = Int8Array(data)
            val bytes = ByteArray(view.length) { view[it] }
            inbox.addLast(bytes)
        }
    }

    override fun send(packet: ByteArray) {
        if (closed) return
        if (!open) {
            pendingOutbox.addLast(packet.copyOf())
            return
        }
        sendRaw(packet)
    }

    private fun sendRaw(packet: ByteArray) {
        val buf: ArrayBuffer = packet.asDynamic().buffer
        ws.send(buf)
    }

    override fun receive(): ByteArray? = inbox.removeFirstOrNull()

    override fun isOpen(): Boolean = !closed

    override fun close() {
        open = false
        ws.close()
    }
}
