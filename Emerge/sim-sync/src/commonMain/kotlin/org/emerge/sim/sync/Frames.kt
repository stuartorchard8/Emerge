package org.emerge.sim.sync

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick

data class InputFrame(
    val tick: Tick,
    val player: PlayerId,
    val payload: ByteArray,
)

data class TickBundleFrame(
    val tick: Tick,
    val inputs: Map<PlayerId, ByteArray>,
)

internal object Frames {
    /**
     * InputFrame wire format:
     * - tick: Long
     * - playerId: Int
     * - payloadLen: Int
     * - payload: ByteArray
     */
    fun encodeInput(frame: InputFrame): ByteArray {
        val w = ByteWriter()
        w.writeLong(frame.tick.value)
        w.writeInt(frame.player.value)
        w.writeInt(frame.payload.size)
        w.writeBytes(frame.payload)
        return w.toByteArray()
    }

    fun decodeInput(packet: ByteArray): InputFrame? {
        val c = ByteCursor(packet)
        if (c.remaining() < 8 + 4 + 4) return null
        val tick = Tick(c.readLong())
        val player = PlayerId(c.readInt())
        val len = c.readInt()
        if (len < 0 || c.remaining() != len) return null
        val payload = c.readBytes(len)
        return InputFrame(tick, player, payload)
    }

    /**
     * TickBundleFrame wire format:
     * - tick: Long
     * - count: Int
     * repeated count times:
     *   - playerId: Int
     *   - payloadLen: Int
     *   - payload: ByteArray
     */
    fun encodeBundle(frame: TickBundleFrame): ByteArray {
        val w = ByteWriter()
        w.writeLong(frame.tick.value)
        w.writeInt(frame.inputs.size)
        for ((playerId, payload) in frame.inputs) {
            w.writeInt(playerId.value)
            w.writeInt(payload.size)
            w.writeBytes(payload)
        }
        return w.toByteArray()
    }

    fun decodeBundle(packet: ByteArray): TickBundleFrame? {
        val c = ByteCursor(packet)
        if (c.remaining() < 8 + 4) return null
        val tick = Tick(c.readLong())
        val count = c.readInt()
        if (count < 0) return null
        val inputs = LinkedHashMap<PlayerId, ByteArray>(count)
        repeat(count) {
            if (c.remaining() < 4 + 4) return null
            val player = PlayerId(c.readInt())
            val len = c.readInt()
            if (len < 0 || c.remaining() < len) return null
            inputs[player] = c.readBytes(len)
        }
        if (c.remaining() != 0) return null
        return TickBundleFrame(tick, inputs)
    }
}

