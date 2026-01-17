package org.emerge.net.codec

/**
 * Minimal growing buffer writer for deterministic protocol framing.
 */
class ByteWriter(initialCapacity: Int = 64) {
    private var buf: ByteArray = ByteArray(maxOf(1, initialCapacity))
    private var size: Int = 0

    fun writeByte(v: Byte) {
        ensure(1)
        buf[size++] = v
    }

    fun writeInt(v: Int) {
        ensure(4)
        buf[size++] = (v ushr 24).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun writeLong(v: Long) {
        ensure(8)
        buf[size++] = (v ushr 56).toByte()
        buf[size++] = (v ushr 48).toByte()
        buf[size++] = (v ushr 40).toByte()
        buf[size++] = (v ushr 32).toByte()
        buf[size++] = (v ushr 24).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun writeBytes(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buf, destinationOffset = size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensure(additional: Int) {
        val needed = size + additional
        if (needed <= buf.size) return
        var newCap = buf.size
        while (newCap < needed) newCap = newCap * 2
        buf = buf.copyOf(newCap)
    }
}

