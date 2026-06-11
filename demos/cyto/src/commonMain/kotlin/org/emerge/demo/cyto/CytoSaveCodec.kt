package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.spawnCell
import org.emerge.demo.cyto.sim.systems.addSpring
import org.emerge.sim.core.EntityId
import org.emerge.net.codec.ByteCursor
import org.emerge.net.codec.ByteWriter
import org.emerge.sim.core.physics.components.MotionComponent
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.components.TransformComponent
import org.emerge.sim.core.physics.primitives.Coord
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.physics.primitives.Frac
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState

/**
 * Versioned byte snapshot of a native Cyto [SimState] — the Box2D-free replacement for the
 * Phase-A cell/connection codec. Serialises each cell's position/velocity (torus
 * fixed-point raw ints), type, radius, divide cooldown, stickiness, and chemicals, plus the
 * connection (spring) pairs. On decode, saved entity ids are remapped to freshly-spawned
 * ids so connections are rebuilt correctly.
 */
object CytoSaveCodec {
    private const val FORMAT_VERSION = 2
    private val cfg = CytoConfig()

    fun encode(state: SimState): ByteArray {
        val w = ByteWriter()
        w.writeInt(FORMAT_VERSION)

        val cells = state.components.getTable<CytoCellComponent>().asMap()
        val transforms = state.components.getTable<TransformComponent>()
        val motions = state.components.getTable<MotionComponent>()

        w.writeInt(cells.size)
        for ((id, cell) in cells) {
            val pos = transforms[id]?.pos ?: Coord2.zero
            val vel = motions[id]?.vel ?: Coord2.zero
            w.writeInt(id.value)
            w.writeInt(pos.x.raw); w.writeInt(pos.y.raw)
            w.writeInt(vel.x.raw); w.writeInt(vel.y.raw)
            w.writeLong(cell.type.dbIndex)
            w.writeLong(cell.logicalRadius.raw)
            w.writeLong(cell.divideCharge.raw)
            w.writeByte(if (cell.sticky) 1 else 0)
            w.writeInt(cell.chemicals.size)
            for ((k, v) in cell.chemicals) {
                w.writeString(k); w.writeLong(v.raw)
            }
        }

        // Unique connection pairs (a < b).
        val springTable = state.components.getTable<SpringConstraintComponent>().asMap()
        val pairs = LinkedHashSet<Long>()
        for ((id, comp) in springTable) {
            for (spring in comp.springs) {
                val a = id.value
                val b = spring.other.value
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                pairs.add((lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL))
            }
        }
        w.writeInt(pairs.size)
        for (packed in pairs) {
            w.writeInt((packed ushr 32).toInt())
            w.writeInt(packed.toInt())
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): SimState {
        val c = ByteCursor(bytes)
        val version = c.readInt()
        require(version == FORMAT_VERSION) {
            "Unsupported Cyto save format version: $version (expected $FORMAT_VERSION)"
        }
        val builder = SimBuilder(SimState())
        val idMap = HashMap<Int, EntityId>()

        val cellCount = c.readInt()
        require(cellCount >= 0) { "Invalid cell count: $cellCount" }
        repeat(cellCount) {
            val savedId = c.readInt()
            val pos = Coord2(Coord(c.readInt()), Coord(c.readInt()))
            val vel = Coord2(Coord(c.readInt()), Coord(c.readInt()))
            val type = CellType.fromDbIndex(c.readLong())
            val radius = Frac(c.readLong())
            val cooldown = Frac(c.readLong())
            val sticky = c.readByte().toInt() != 0
            val chemCount = c.readInt()
            require(chemCount >= 0) { "Invalid chemical count: $chemCount" }
            val chemicals = LinkedHashMap<String, Frac>(chemCount)
            repeat(chemCount) { chemicals[c.readString()] = Frac(c.readLong()) }

            val newId = builder.spawnCell(pos, vel, type, chemicals, radius, sticky)
            builder.update<CytoCellComponent>(newId) { current ->
                (current ?: CytoCellComponent(type, chemicals, radius)).copy(divideCharge = cooldown)
            }
            idMap[savedId] = newId
        }

        val springCount = c.readInt()
        require(springCount >= 0) { "Invalid spring count: $springCount" }
        repeat(springCount) {
            val a = idMap[c.readInt()]
            val b = idMap[c.readInt()]
            if (a != null && b != null) addSpring(builder, a, b, cfg)
        }

        require(c.remaining() == 0) { "Unexpected trailing bytes in Cyto snapshot: ${c.remaining()}" }
        return builder.build()
    }

    private fun ByteWriter.writeString(s: String) {
        val b = s.encodeToByteArray()
        writeInt(b.size); writeBytes(b)
    }
    private fun ByteCursor.readString(): String {
        val len = readInt()
        require(len >= 0) { "Invalid string length: $len" }
        return readBytes(len).decodeToString()
    }
}
