package org.emerge.net.api

/**
 * Simple Pipe wrapper that can be "hot-swapped" (e.g., connect/reconnect) while callers keep using
 * the same Pipe instance.
 */
class DelegatingPipe : Pipe {
    private val delegate = AtomicRef<Pipe?>(null)

    fun setDelegate(pipe: Pipe) {
        delegate.set(pipe)
    }

    override fun send(packet: ByteArray) {
        delegate.get()?.send(packet)
    }

    override fun receive(): ByteArray? = delegate.get()?.receive()

    override fun isOpen(): Boolean = delegate.get()?.isOpen() == true
}

