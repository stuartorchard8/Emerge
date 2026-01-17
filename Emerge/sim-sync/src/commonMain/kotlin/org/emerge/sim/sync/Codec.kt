package org.emerge.sim.sync

/**
 * Minimal byte codec (no kotlinx.serialization).
 */
interface Codec<T> {
    fun encode(value: T): ByteArray
    fun decode(bytes: ByteArray): T
}

