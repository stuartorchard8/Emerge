package org.emerge.net.tcp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.emerge.net.api.Pipe

/**
 * Very small JVM TCP implementation:
 * - length-prefixed frames (Int32 big-endian length + payload)
 * - background reader thread pushes packets into an in-memory queue
 *
 * This is intentionally minimal; you can swap it out later for a more robust implementation.
 */
class TcpPipe(private val socket: Socket) : Pipe, AutoCloseable {
    private val out = DataOutputStream(socket.getOutputStream())
    private val incoming = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var closed = false

    init {
        val input = DataInputStream(socket.getInputStream())
        thread(isDaemon = true, name = "TcpPipe-reader") {
            try {
                while (!closed) {
                    val len = input.readInt()
                    if (len < 0) break
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    incoming.add(bytes)
                }
            } catch (_: Throwable) {
                // treat as disconnect
            } finally {
                closed = true
                runCatching { socket.close() }
            }
        }
    }

    override fun send(packet: ByteArray) {
        if (closed) return
        synchronized(out) {
            out.writeInt(packet.size)
            out.write(packet)
            out.flush()
        }
    }

    override fun receive(): ByteArray? = incoming.poll()

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }
}

