package org.emerge.net.api

/**
 * Minimal, dependency-free transport abstraction.
 *
 * - Non-blocking receive (polling) so common code can run without coroutines.
 * - Message boundaries preserved (packet-like), so higher layers can frame their own protocol.
 */
interface Pipe {
    fun send(packet: ByteArray)
    fun receive(): ByteArray?
    fun isOpen(): Boolean = true
}

