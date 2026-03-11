package org.emerge.net.loopback

import org.emerge.net.api.Pipe

/**
 * In-memory, single-process transport useful for tests and demos.
 */
object Loopback {
    fun createPair(): Pair<Pipe, Pipe> {
        val a = Queue()
        val b = Queue()
        val p1 = LoopbackPipe(incoming = a, outgoing = b)
        val p2 = LoopbackPipe(incoming = b, outgoing = a)
        return p1 to p2
    }

    private class Queue {
        private val q = ArrayDeque<ByteArray>()

        fun push(bytes: ByteArray) {
            // copy to avoid accidental shared mutation by callers
            q.addLast(bytes.copyOf())
        }

        fun pop(): ByteArray? = if (q.isEmpty()) null else q.removeFirst()
    }

    private class LoopbackPipe(
        private val incoming: Queue,
        private val outgoing: Queue,
    ) : Pipe {
        override fun send(packet: ByteArray) {
            outgoing.push(packet)
        }

        override fun receive(): ByteArray? = incoming.pop()

        override fun isOpen(): Boolean = true
    }
}

