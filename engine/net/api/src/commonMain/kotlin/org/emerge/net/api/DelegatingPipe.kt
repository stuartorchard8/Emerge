package org.emerge.net.api

/**
 * Simple Pipe wrapper that can be "hot-swapped" (e.g., connect/reconnect) while callers keep using
 * the same Pipe instance.
 */
class DelegatingPipe : Pipe {
    private val delegate = AtomicRef<Pipe?>(null)

    fun setDelegate(pipe: Pipe) {
        val previous = delegate.get()
        delegate.set(pipe)
        previous?.close()
    }

    override fun send(packet: ByteArray) {
        delegate.get()?.send(packet)
    }

    override fun receive(): ByteArray? = delegate.get()?.receive()

    override fun isOpen(): Boolean = delegate.get()?.isOpen() == true

    override fun close() {
        val current = delegate.get()
        delegate.set(null)
        current?.close()
    }
}

