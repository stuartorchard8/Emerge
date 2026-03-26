package org.emerge.sim.sync.lockstep

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick

internal object LockstepProtocol {
    private const val HELLO: Byte = 1
    private const val WELCOME: Byte = 2
    private const val INPUT: Byte = 3
    private const val TICK_INPUTS: Byte = 4
    private const val RESYNC: Byte = 5

    data class Hello(val clientMode: ClientMode = ClientMode.LOCKSTEP)

    data class Welcome(val playerId: PlayerId, val tick: Tick, val stateBytes: ByteArray)

    data class InputMsg(val playerId: PlayerId, val payload: ByteArray)

    data class TickInputs(val tick: Tick, val inputs: Map<PlayerId, ByteArray>)

    data class Resync(val tick: Tick, val stateBytes: ByteArray)

    // ── Hello ──

    fun encodeHello(msg: Hello): ByteArray {
        val w = ByteWriter()
        w.writeByte(HELLO)
        w.writeInt(0) // nameLen, reserved
        w.writeByte(msg.clientMode.ordinal.toByte())
        return w.toByteArray()
    }

    fun decodeHello(packet: ByteArray): Hello? {
        val c = ByteCursor(packet)
        if (c.remaining() < 1 + 4 + 1) return null
        if (c.readByte() != HELLO) return null
        val nameLen = c.readInt()
        if (nameLen != 0) return null
        val modeByte = c.readByte().toInt()
        val entries = ClientMode.entries
        if (modeByte < 0 || modeByte >= entries.size) return null
        if (c.remaining() != 0) return null
        return Hello(clientMode = entries[modeByte])
    }

    // ── Welcome ──

    fun encodeWelcome(msg: Welcome): ByteArray {
        val w = ByteWriter()
        w.writeByte(WELCOME)
        w.writeInt(msg.playerId.value)
        w.writeLong(msg.tick.value)
        w.writeInt(msg.stateBytes.size)
        w.writeBytes(msg.stateBytes)
        return w.toByteArray()
    }

    fun decodeWelcome(packet: ByteArray): Welcome? {
        val c = ByteCursor(packet)
        if (c.remaining() < 1 + 4 + 8 + 4) return null
        if (c.readByte() != WELCOME) return null
        val pid = PlayerId(c.readInt())
        val tick = Tick(c.readLong())
        val len = c.readInt()
        if (len < 0 || c.remaining() != len) return null
        val bytes = c.readBytes(len)
        return Welcome(pid, tick, bytes)
    }

    // ── Input (client → host) ──

    fun encodeInput(msg: InputMsg): ByteArray {
        val w = ByteWriter()
        w.writeByte(INPUT)
        w.writeInt(msg.playerId.value)
        w.writeInt(msg.payload.size)
        w.writeBytes(msg.payload)
        return w.toByteArray()
    }

    fun decodeInput(packet: ByteArray): InputMsg? {
        val c = ByteCursor(packet)
        if (c.remaining() < 1 + 4 + 4) return null
        if (c.readByte() != INPUT) return null
        val pid = PlayerId(c.readInt())
        val len = c.readInt()
        if (len < 0 || c.remaining() != len) return null
        val payload = c.readBytes(len)
        return InputMsg(pid, payload)
    }

    // ── TickInputs (host → all clients): all players' inputs for one tick ──

    fun encodeTickInputs(msg: TickInputs): ByteArray {
        val w = ByteWriter()
        w.writeByte(TICK_INPUTS)
        w.writeLong(msg.tick.value)
        w.writeInt(msg.inputs.size)
        for ((playerId, payload) in msg.inputs) {
            w.writeInt(playerId.value)
            w.writeInt(payload.size)
            w.writeBytes(payload)
        }
        return w.toByteArray()
    }

    fun decodeTickInputs(packet: ByteArray): TickInputs? {
        val c = ByteCursor(packet)
        if (c.remaining() < 1 + 8 + 4) return null
        if (c.readByte() != TICK_INPUTS) return null
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
        return TickInputs(tick, inputs)
    }

    // ── Resync (host → clients): full state replacement on join/leave ──

    fun encodeResync(msg: Resync): ByteArray {
        val w = ByteWriter()
        w.writeByte(RESYNC)
        w.writeLong(msg.tick.value)
        w.writeInt(msg.stateBytes.size)
        w.writeBytes(msg.stateBytes)
        return w.toByteArray()
    }

    fun decodeResync(packet: ByteArray): Resync? {
        val c = ByteCursor(packet)
        if (c.remaining() < 1 + 8 + 4) return null
        if (c.readByte() != RESYNC) return null
        val tick = Tick(c.readLong())
        val len = c.readInt()
        if (len < 0 || c.remaining() != len) return null
        val bytes = c.readBytes(len)
        return Resync(tick, bytes)
    }

    /**
     * Peek at the tag byte to determine the message type, then decode.
     * Returns one of [Hello], [Welcome], [InputMsg], [TickInputs], [Resync], or null.
     */
    fun decode(packet: ByteArray): Any? {
        if (packet.isEmpty()) return null
        return when (packet[0]) {
            HELLO -> decodeHello(packet)
            WELCOME -> decodeWelcome(packet)
            INPUT -> decodeInput(packet)
            TICK_INPUTS -> decodeTickInputs(packet)
            RESYNC -> decodeResync(packet)
            else -> null
        }
    }
}
