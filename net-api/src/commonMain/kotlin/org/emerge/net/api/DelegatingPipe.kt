package org.emerge.net.api

/**
 * Simple Pipe wrapper that can be "hot-swapped" (e.g., connect/reconnect) while callers keep using
 * the same Pipe instance.
 */
class DelegatingPipe : Pipe {
    @Volatile
    private var delegate: Pipe? = null

    fun setDelegate(pipe: Pipe) {
        delegate = pipe
    }

    override fun send(packet: ByteArray) {
        delegate?.send(packet)
    }

    override fun receive(): ByteArray? = delegate?.receive()
}

