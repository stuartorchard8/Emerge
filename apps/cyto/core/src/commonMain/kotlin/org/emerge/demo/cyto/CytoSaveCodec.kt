package org.emerge.demo.cyto

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.CytoSimParamsComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.PARAMS_SINGLETON
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
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
 * the connection (spring) pairs; and the finite [CytoMatterField] reservoir. Saved ids are remapped to
 * freshly-spawned ids on decode so connections rebuild correctly. (v4 = the matter rework; earlier
 * energy-model saves don't load — cyto saves are regenerated runtime artifacts.)
 */
object CytoSaveCodec {
    // v5: persist the PRNG randomSeed (mutation continuity + avoids the seed-0 LCG degeneracy on load).
    // v6: persist the sim clock (state.tick) so the moving light field resumes at the right phase on load.
    // v7: persist the runtime mutation rate-denominator (-1 = inherit the cfg default).
    // v8: FormBond flipped wildcard-default → exact-default (MORPHOGENESIS.md §2026-06-18); pre-v8 genomes are
    // migrated to explicit wildcard on load (see [migrateFormBondToWildcard]) so they behave byte-for-byte.
    // v11: HYDROTHERMAL_CHEMISTRY_PLAN.md added EnergySource.FormBond + ActionType.BreakBond, the mirror
    // images of the pre-existing EnergySource.BreakBond + ActionType.FormBond; pre-v11 genomes are migrated
    // to their mirror on load (see [migrateInvertedChemistry]) so their metabolic niche carries over inverted.
    private const val FORMAT_VERSION = 11
    private val cfg = CytoConfig()

    fun encode(state: SimState): ByteArray {
        val w = ByteWriter()
        w.writeInt(FORMAT_VERSION)
        w.writeLong(state.randomSeed)
        w.writeLong(state.tick)
        w.writeInt(state.components.getTable<CytoSimParamsComponent>()[PARAMS_SINGLETON]?.mutationRateDenom ?: -1)

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

        // Matter reservoir: the dense field, one full column per present species.
        val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid ?: CytoMatterField.empty()
        grid.encode({ w.writeInt(it) }, { w.writeString(it) })
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): SimState {
        val c = ByteCursor(bytes)
        val version = c.readInt()
        // Read back-compatibly across the bumps (v6 = +tick, v7 = +mutation rate, v10 = the dense matter
        // field replacing v9's quad-tree): older fields just default and a v9 tree is migrated on load.
        // Re-saving upgrades the file to the current version. (Pre-v6 = the energy model, a different
        // structure — still rejected.)
        require(version in 6..FORMAT_VERSION) {
            "Unsupported Cyto save format version: $version (expected 6..$FORMAT_VERSION)"
        }
        val randomSeed = c.readLong()
        val tick = c.readLong()
        val mutationRateDenom = if (version >= 7) c.readInt() else -1   // v6 lacks the rate field → inherit
        val builder = SimBuilder(SimState(randomSeed = randomSeed, tick = tick))
        // Only restore the params singleton when explicitly set (≥0); -1 leaves the world inheriting the cfg default.
        if (mutationRateDenom >= 0) builder.update<CytoSimParamsComponent>(PARAMS_SINGLETON) { CytoSimParamsComponent(mutationRateDenom) }
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
                .let { if (version < 8) migrateFormBondToWildcard(it) else it }
                .let { if (version < 11) migrateInvertedChemistry(it) else it }

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

        val grid = if (version >= 10) {
            CytoMatterField.empty().also { it.decodeInto({ c.readInt() }, { c.readString() }) }
        } else if (version == 9) {
            // v9 stored the adaptive quad-tree. Walk it and splat each leaf across the texels it covered —
            // the field is dense now, so a coarse leaf's pooled matter spreads over its whole region.
            CytoMatterField.decodeTree({ c.readByte().toInt() }, { readCounts(c) }, { c.readInt() })
        } else {
            // v6–8 stored a FLAT grid (count + idx/counts pairs). The quad-tree world is a different scale,
            // so consume + discard those bytes and start the field fresh (the cells/springs still restore).
            val gridCellCount = c.readInt(); require(gridCellCount >= 0) { "Invalid grid-cell count: $gridCellCount" }
            repeat(gridCellCount) { c.readInt(); readCounts(c) }
            CytoMatterField.seededUniform(CytoSeed.MATTER_UNIFORM_LEVEL)
        }
        builder.update<CytoMatterGridComponent>(GRID_SINGLETON) { CytoMatterGridComponent(grid) }

        require(c.remaining() == 0) { "Unexpected trailing bytes in Cyto snapshot: ${c.remaining()}" }
        return builder.build()
    }

    /** v8 migration (MORPHOGENESIS.md §2026-06-18): FormBond went from wildcard-by-default (richest molecule
     *  ending/starting with the operand) to **exact-species** by default. Pre-v8 genomes were authored/evolved
     *  under the wildcard meaning, so mark every FormBond gene's operands as explicit wildcards — preserving
     *  their behaviour byte-for-byte. Re-saving then stores them as v8 with the `*` markers, so the migration
     *  runs once. (Empty operands stay no-ops; the flag is inert on them.) */
    private fun migrateFormBondToWildcard(genome: List<Gene>): List<Gene> = genome.map { g ->
        if (g.action.type == ActionType.FormBond) g.copy(action = g.action.copy(aWild = true, bWild = true)) else g
    }

    /** v11 migration (HYDROTHERMAL_CHEMISTRY_PLAN.md): the engine gained the inverted-chemistry primitives —
     *  [EnergySource.FormBond] (energy from JOINING monomers) and [ActionType.BreakBond] (an energy-COSTED
     *  action that splits a stored bond) — as the mirror images of the pre-existing [EnergySource.BreakBond]
     *  (free fuel-harvesting by breaking) and [ActionType.FormBond] (energy-costed building). Pre-v11 genomes
     *  were authored/evolved entirely within the old pair, so on load every gene is reinterpreted into its
     *  mirror: a BreakBond ENERGY SOURCE becomes a FormBond energy source (same bond string), and a FormBond
     *  ACTION becomes a BreakBond action (bond = `a.last + b.first`, the junction bond FormBond would have
     *  created). Detectable purely by DSL **position** (source vs. action) — every legacy save only ever put
     *  BreakBond in source position and FormBond in action position, so the swap is unambiguous, no new
     *  keyword needed. An empty-operand FormBond action (an inert no-op gene) is left alone — there's no bond
     *  to derive. Re-saving upgrades the file to v11, so the migration runs once. */
    private fun migrateInvertedChemistry(genome: List<Gene>): List<Gene> = genome.map { g ->
        val newSource = (g.source as? EnergySource.BreakBond)?.let { EnergySource.FormBond(it.bond) } ?: g.source
        val newAction = if (g.action.type == ActionType.FormBond && g.action.a.isNotEmpty() && g.action.b.isNotEmpty()) {
            GeneAction(ActionType.BreakBond, a = "${g.action.a.last()}${g.action.b.first()}")
        } else g.action
        g.copy(source = newSource, action = newAction)
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
