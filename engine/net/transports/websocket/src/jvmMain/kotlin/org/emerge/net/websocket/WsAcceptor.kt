package org.emerge.net.websocket

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import org.emerge.net.api.Pipe
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer as JWsServer

/**
 * JVM WebSocket server that produces [Pipe] instances for each connected client.
 *
 * Mirrors the [org.emerge.net.tcp.Tcp.Listener] contract: call [accept] to block until
 * a new client connects, then use the returned [Pipe] with [LockstepHost].
 */
class WsAcceptor(port: Int) : AutoCloseable {
    private val ready = LinkedBlockingQueue<Pipe>()

    private val server = object : JWsServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
            val pipe = WsServerPipe(conn)
            conn.setAttachment(pipe)
            ready.put(pipe)
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            val pipe = conn.getAttachment<WsServerPipe>()
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            pipe.enqueue(bytes)
        }

        override fun onMessage(conn: WebSocket?, message: String?) {
            // Text frames ignored; we only use binary.
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            conn.getAttachment<WsServerPipe>()?.markClosed()
        }

        override fun onError(conn: WebSocket?, ex: Exception?) {
            conn?.getAttachment<WsServerPipe>()?.markClosed()
        }

        override fun onStart() {}
    }

    init {
        server.isReuseAddr = true
        server.start()
    }

    fun accept(): Pipe = ready.take()

    override fun close() {
        server.stop()
    }
}

/**
 * Server-side [Pipe] wrapping a single WebSocket connection managed by [WsAcceptor].
 */
internal class WsServerPipe(private val conn: WebSocket) : Pipe {
    private val inbox = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var closed = false

    fun enqueue(bytes: ByteArray) {
        inbox.add(bytes)
    }

    fun markClosed() {
        closed = true
    }

    override fun send(packet: ByteArray) {
        if (closed) return
        conn.send(packet)
    }

    override fun receive(): ByteArray? = inbox.poll()

    override fun isOpen(): Boolean = !closed && conn.isOpen

    override fun close() {
        closed = true
        runCatching { conn.close() }
    }
}
