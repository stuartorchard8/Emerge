package org.emerge.net.tcp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import org.emerge.net.api.Pipe

/**
 * Minimal TCP Pipe implementation shared by desktop JVM + Android (same code).
 * Protocol: Int32 length (big-endian) + payload bytes.
 */
class TcpPipe internal constructor(private val socket: Socket) : Pipe, AutoCloseable {
    private val out = DataOutputStream(socket.getOutputStream())
    private val incoming = ConcurrentLinkedQueue<ByteArray>()
    private val outgoing = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var closed = false

    init {
        val input = DataInputStream(socket.getInputStream())
        thread(isDaemon = true, name = "TcpPipe-reader") {
            try {
                while (!closed) {
                    val len = input.readInt()
                    if (len <= 0) continue
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

        thread(isDaemon = true, name = "TcpPipe-writer") {
            try {
                while (!closed) {
                    val pkt = outgoing.take()
                    if (closed) break
                    synchronized(out) {
                        out.writeInt(pkt.size)
                        out.write(pkt)
                        out.flush()
                    }
                }
            } catch (_: InterruptedException) {
                // exit
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
        // Enqueue so callers can safely call send() from the main thread on Android.
        outgoing.offer(packet.copyOf())
    }

    override fun receive(): ByteArray? = incoming.poll()

    override fun close() {
        closed = true
        // Unblock writer thread.
        outgoing.offer(ByteArray(0))
        runCatching { socket.close() }
    }
}

