package org.emerge.net.codec

/**
 * Tiny byte cursor for encoding/decoding without external deps.
 * Big-endian, fixed-width primitives.
 */
class ByteCursor(private val bytes: ByteArray) {
    var pos: Int = 0
        private set

    fun remaining(): Int = bytes.size - pos

    fun readByte(): Byte {
        check(pos < bytes.size) { "readByte out of bounds" }
        return bytes[pos++]
    }

    fun readInt(): Int {
        val b0 = readByte().toInt() and 0xff
        val b1 = readByte().toInt() and 0xff
        val b2 = readByte().toInt() and 0xff
        val b3 = readByte().toInt() and 0xff
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    fun readLong(): Long {
        var v = 0L
        repeat(8) {
            v = (v shl 8) or (readByte().toLong() and 0xffL)
        }
        return v
    }

    fun readBytes(count: Int): ByteArray {
        require(count >= 0) { "count must be >= 0" }
        check(remaining() >= count) { "readBytes($count) out of bounds" }
        val out = bytes.copyOfRange(pos, pos + count)
        pos += count
        return out
    }
}

