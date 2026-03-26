package org.emerge.sim.sync

interface StateCodec<S> {
    fun encode(state: S): ByteArray
    fun decode(bytes: ByteArray): S
}
