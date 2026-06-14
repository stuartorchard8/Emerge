package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoMatterGrid
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
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
 * Versioned byte snapshot of a matter-model Cyto [SimState]: each cell's position/velocity, type,
 * radius, wear, stickiness, its cytoplasm + biomass molecule counts, and its genome (GeneCodec text);
 * the connection (spring) pairs; and the finite [CytoMatterGrid] reservoir. Saved ids are remapped to
 * freshly-spawned ids on decode so connections rebuild correctly. (v4 = the matter rework; earlier
 * energy-model saves don't load — cyto saves are regenerated runtime artifacts.)
 */
object CytoSaveCodec {
    // v5: persist the PRNG randomSeed (mutation continuity + avoids the seed-0 LCG degeneracy on load).
    // v6: persist the sim clock (state.tick) so the moving light field resumes at the right phase on load.
    private const val FORMAT_VERSION = 6
    private val cfg = CytoConfig()

    fun encode(state: SimState): ByteArray {
        val w = ByteWriter()
        w.writeInt(FORMAT_VERSION)
        w.writeLong(state.randomSeed)
        w.writeLong(state.tick)

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
            w.writeInt(cell.wear)
            w.writeByte(if (cell.sticky) 1 else 0)
            writeCounts(w, cell.cytoplasm)
            writeCounts(w, cell.biomass)
            w.writeString(GeneCodec.serialize(cell.genome))
        }

        // Unique connection pairs (a < b).
        val springTable = state.components.getTable<SpringConstraintComponent>().asMap()
        val pairs = LinkedHashSet<Long>()
        for ((id, comp) in springTable) {
            for (spring in comp.springs) {
                val lo = minOf(id.value, spring.other.value)
                val hi = maxOf(id.value, spring.other.value)
                pairs.add((lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL))
            }
        }
        w.writeInt(pairs.size)
        for (packed in pairs) {
            w.writeInt((packed ushr 32).toInt())
            w.writeInt(packed.toInt())
        }

        // Matter reservoir: every non-empty grid cell.
        val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid ?: CytoMatterGrid.empty()
        val nonEmpty = ArrayList<Int>()
        for (idx in 0 until CytoMatterGrid.RES * CytoMatterGrid.RES) {
            if (grid.cellAt(idx).isNotEmpty()) nonEmpty.add(idx)
        }
        w.writeInt(nonEmpty.size)
        for (idx in nonEmpty) {
            w.writeInt(idx)
            writeCounts(w, grid.cellAt(idx))
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): SimState {
        val c = ByteCursor(bytes)
        val version = c.readInt()
        require(version == FORMAT_VERSION) {
            "Unsupported Cyto save format version: $version (expected $FORMAT_VERSION)"
        }
        val randomSeed = c.readLong()
        val tick = c.readLong()
        val builder = SimBuilder(SimState(randomSeed = randomSeed, tick = tick))
        val idMap = HashMap<Int, EntityId>()

        val cellCount = c.readInt()
        require(cellCount >= 0) { "Invalid cell count: $cellCount" }
        repeat(cellCount) {
            val savedId = c.readInt()
            val pos = Coord2(Coord(c.readInt()), Coord(c.readInt()))
            val vel = Coord2(Coord(c.readInt()), Coord(c.readInt()))
            val type = CellType.fromDbIndex(c.readLong())
            val radius = Frac(c.readLong())
            val wear = c.readInt()
            val sticky = c.readByte().toInt() != 0
            val cytoplasm = readCounts(c)
            val biomass = readCounts(c)
            val genome = GeneCodec.parse(c.readString())

            val newId = builder.spawnCell(pos, vel, type, cytoplasm, biomass, radius, sticky, genome)
            builder.update<CytoCellComponent>(newId) { current -> (current ?: error("spawn")).copy(wear = wear) }
            idMap[savedId] = newId
        }

        val springCount = c.readInt()
        require(springCount >= 0) { "Invalid spring count: $springCount" }
        repeat(springCount) {
            val a = idMap[c.readInt()]
            val b = idMap[c.readInt()]
            if (a != null && b != null) addSpring(builder, a, b, cfg)
        }

        val grid = CytoMatterGrid.empty()
        val gridCellCount = c.readInt()
        require(gridCellCount >= 0) { "Invalid grid-cell count: $gridCellCount" }
        repeat(gridCellCount) {
            val idx = c.readInt()
            for ((species, count) in readCounts(c)) grid.deposit(idx, species, count)
        }
        builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }

        require(c.remaining() == 0) { "Unexpected trailing bytes in Cyto snapshot: ${c.remaining()}" }
        return builder.build()
    }

    private fun writeCounts(w: ByteWriter, counts: Map<String, Int>) {
        w.writeInt(counts.size)
        for ((species, count) in counts) { w.writeString(species); w.writeInt(count) }
    }

    private fun readCounts(c: ByteCursor): Map<String, Int> {
        val n = c.readInt()
        require(n >= 0) { "Invalid count map size: $n" }
        val out = LinkedHashMap<String, Int>(n)
        repeat(n) { out[c.readString()] = c.readInt() }
        return out
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
