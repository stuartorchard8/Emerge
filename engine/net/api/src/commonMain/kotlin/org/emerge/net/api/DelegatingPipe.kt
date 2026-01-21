package org.emerge.net.api

/**
 * Simple Pipe wrapper that can be "hot-swapped" (e.g., connect/reconnect) while callers keep using
 * the same Pipe instance.
 */
class DelegatingPipe : Pipe {
    // NOTE: Common (multiplatform) code cannot use kotlin.jvm.Volatile.
    // This is "good enough" for the demo reconnect loop; if you need stronger guarantees,
    // make this expect/actual and use an atomic/volatile on JVM/Android.
    private var delegate: Pipe? = null

    fun setDelegate(pipe: Pipe) {
        delegate = pipe
    }

    override fun send(packet: ByteArray) {
        delegate?.send(packet)
    }

    override fun receive(): ByteArray? = delegate?.receive()
}

