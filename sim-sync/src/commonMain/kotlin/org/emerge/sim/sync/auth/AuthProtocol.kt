package org.emerge.sim.sync.auth

import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.Tick

internal object AuthProtocol {
    // message tags
    private const val HELLO: Byte = 1
    private const val WELCOME: Byte = 2
    private const val INPUT: Byte = 3
    private const val SNAPSHOT: Byte = 4

    data class Hello(val wantPlayerName: String? = null)

    data class Welcome(val playerId: PlayerId, val tick: Tick, val stateBytes: ByteArray)

    data class InputMsg(val playerId: PlayerId, val payload: ByteArray)

    data class Snapshot(val tick: Tick, val stateBytes: ByteArray)

    fun encodeHello(msg: Hello): ByteArray {
        val w = ByteWriter()
        w.writeByte(HELLO)
        // keep minimal: no name for now
        w.writeInt(0)
        return w.toByteArray()
    }

    fun decodeHello(packet: ByteArray): Hello? {
        val c = ByteCursor(packet)
        if (c.remaining() < 1 + 4) return null
        if (c.readByte() != HELLO) return null
        val nameLen = c.readInt()
        if (nameLen != 0) return null
        if (c.remaining() != 0) return null
        return Hello()
    }

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

    fun encodeSnapshot(msg: Snapshot): ByteArray {
        val w = ByteWriter()
        w.writeByte(SNAPSHOT)
        w.writeLong(msg.tick.value)
        w.writeInt(msg.stateBytes.size)
        w.writeBytes(msg.stateBytes)
        return w.toByteArray()
    }

    fun decodeSnapshot(packet: ByteArray): Snapshot? {
        val c = ByteCursor(packet)
        if (c.remaining() < 1 + 8 + 4) return null
        if (c.readByte() != SNAPSHOT) return null
        val tick = Tick(c.readLong())
        val len = c.readInt()
        if (len < 0 || c.remaining() != len) return null
        val bytes = c.readBytes(len)
        return Snapshot(tick, bytes)
    }
}

