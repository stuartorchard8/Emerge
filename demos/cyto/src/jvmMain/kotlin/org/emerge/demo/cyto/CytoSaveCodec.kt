package org.emerge.demo.cyto

import com.badlogic.gdx.math.Vector2
import org.emerge.demo.cyto.cells.CellConnectionData
import org.emerge.demo.cyto.cells.CellData
import org.emerge.demo.cyto.cells.CellType
import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter

data class CytoSnapshot(
  val cells: List<CellData>,
  val connections: List<CellConnectionData>,
)

/**
 * Binary save format for a Cyto world snapshot — the Emerge-native replacement for
 * Cyto's SQLDelight `Cell` / `CellConnection` tables. Mirrors
 * [org.emerge.demo.drockets.DrocketsSaveCodec]'s versioned-snapshot shape, but Cyto's
 * Box2D-backed sim isn't an Emerge [org.emerge.sim.core.sim.SimState], so this writes
 * the plain [CellData] / [CellConnectionData] read models directly.
 *
 * Compatibility: any wire-layout change must bump [FORMAT_VERSION]; the decoder rejects
 * other versions outright (start a fresh sim instead).
 */
object CytoSaveCodec {
  private const val FORMAT_VERSION = 1

  fun encode(snapshot: CytoSnapshot): ByteArray {
    val w = ByteWriter()
    w.writeInt(FORMAT_VERSION)

    w.writeInt(snapshot.cells.size)
    snapshot.cells.forEach { cell ->
      w.writeLong(cell.id ?: -1L)
      w.writeFloat(cell.position.x)
      w.writeFloat(cell.position.y)
      w.writeFloat(cell.linearVelocity.x)
      w.writeFloat(cell.linearVelocity.y)
      w.writeFloat(cell.direction)
      w.writeFloat(cell.spin)
      w.writeFloat(cell.radius)
      w.writeLong(cell.type.dbIndex)
      w.writeInt(cell.chemicals.size)
      cell.chemicals.forEach { (k, v) ->
        w.writeString(k)
        w.writeFloat(v)
      }
    }

    w.writeInt(snapshot.connections.size)
    snapshot.connections.forEach { con ->
      w.writeLong(con.id1)
      w.writeLong(con.id2)
    }
    return w.toByteArray()
  }

  fun decode(bytes: ByteArray): CytoSnapshot {
    val c = ByteCursor(bytes)
    val version = c.readInt()
    require(version == FORMAT_VERSION) {
      "Unsupported Cyto save format version: $version (expected $FORMAT_VERSION)"
    }

    val cellCount = c.readInt()
    require(cellCount >= 0) { "Invalid cell count: $cellCount" }
    val cells = ArrayList<CellData>(cellCount)
    repeat(cellCount) {
      val id = c.readLong()
      val posX = c.readFloat()
      val posY = c.readFloat()
      val velX = c.readFloat()
      val velY = c.readFloat()
      val direction = c.readFloat()
      val spin = c.readFloat()
      val radius = c.readFloat()
      val typeDbIndex = c.readLong()
      val chemCount = c.readInt()
      require(chemCount >= 0) { "Invalid chemical count: $chemCount" }
      val chemicals = LinkedHashMap<String, Float>(chemCount)
      repeat(chemCount) {
        val key = c.readString()
        chemicals[key] = c.readFloat()
      }
      cells.add(
        CellData(
          position = Vector2(posX, posY),
          linearVelocity = Vector2(velX, velY),
          chemicals = chemicals,
          direction = direction,
          spin = spin,
          radius = radius,
          type = CellType.fromDbIndex(typeDbIndex),
          id = if (id < 0L) null else id,
        )
      )
    }

    val connCount = c.readInt()
    require(connCount >= 0) { "Invalid connection count: $connCount" }
    val connections = ArrayList<CellConnectionData>(connCount)
    repeat(connCount) {
      val id1 = c.readLong()
      val id2 = c.readLong()
      connections.add(CellConnectionData(id1, id2))
    }

    require(c.remaining() == 0) { "Unexpected trailing bytes in Cyto snapshot: ${c.remaining()}" }
    return CytoSnapshot(cells = cells, connections = connections)
  }

  // ── ByteWriter/ByteCursor float + string helpers ───────────────────────────
  // The engine's writer only ships int/long/bytes; floats go via raw IEEE-754 bits
  // and strings as a length-prefixed UTF-8 byte run.

  private fun ByteWriter.writeFloat(v: Float) = writeInt(v.toRawBits())

  private fun ByteWriter.writeString(s: String) {
    val bytes = s.encodeToByteArray()
    writeInt(bytes.size)
    writeBytes(bytes)
  }

  private fun ByteCursor.readFloat(): Float = Float.fromBits(readInt())

  private fun ByteCursor.readString(): String {
    val len = readInt()
    require(len >= 0) { "Invalid string length: $len" }
    return readBytes(len).decodeToString()
  }
}
