package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoMatterField
import org.emerge.demo.cyto.sim.CytoSeed
import org.emerge.demo.cyto.sim.CytoMatterGridComponent
import org.emerge.demo.cyto.sim.GRID_SINGLETON
import org.emerge.demo.cyto.sim.GeneCodec
import org.emerge.demo.cyto.sim.SpeciesRegistry
import org.emerge.demo.cyto.sim.handleableOf
import org.emerge.sim.core.EntityId
import org.emerge.sim.core.physics.components.TransformComponent
import kotlin.test.Test

/**
 * Throwaway diagnostic: dump one cell from a cyto-save.bin **as the running app sees it** — i.e. by its
 * post-load EntityId (CytoSaveCodec.decode remaps saved ids to fresh ones, which is what the info panel
 * shows). Run with -Dinspectcell=<id>; writes to /tmp/inspectcell.txt.
 *   ./gradlew :apps:cyto:core:jvmTest --tests "*InspectCell*" -Dinspectcell=1803
 */
class InspectCell {

    @Test
    fun inspect() {
        val target = System.getProperty("inspectcell")?.toIntOrNull() ?: return
        val path = System.getProperty("savefile") ?: "/home/stu/emerge/apps/cyto/desktop/cyto-save.bin"
        val state = CytoSaveCodec.decode(java.io.File(path).readBytes())
        val sb = StringBuilder()
        sb.appendLine("save=$path  tick=${state.tick}  seed=${state.randomSeed}")

        val cells = state.components.getTable<CytoCellComponent>().asMap()
        sb.appendLine("cells=${cells.size}; post-load id range ${cells.keys.minByOrNull { it.value }?.value}..${cells.keys.maxByOrNull { it.value }?.value}")

        val id = EntityId(target)
        val cell = cells[id]
        if (cell == null) { sb.appendLine("\nNO cell with post-load id $target"); java.io.File("/tmp/inspectcell.txt").writeText(sb.toString()); return }

        val transforms = state.components.getTable<TransformComponent>().asMap()
        val pos = transforms[id]?.pos
        val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid ?: CytoMatterField.empty()

        sb.appendLine("\n=== cell $target (type ${cell.type}) ===")
        sb.appendLine("logicalRadius=${cell.logicalRadius.raw} wear=${cell.wear}")
        sb.appendLine("cytoplasm=${cell.cytoplasm}")
        sb.appendLine("biomass=${cell.biomass}")
        val bioBonds = org.emerge.demo.cyto.sim.totalBiomassBonds(cell.biomass)
        sb.appendLine("totalBiomassBonds (what the Biomass gate reads) = $bioBonds")
        sb.appendLine("  (note: monomers like c have 0 bonds, so they don't count toward Biomass)")
        sb.appendLine("genome (gate evaluated against this cell's current state):")
        fun opVal(o: org.emerge.demo.cyto.sim.Operand): Int = when (o) {
            is org.emerge.demo.cyto.sim.Operand.Constant -> o.value
            is org.emerge.demo.cyto.sim.Operand.Chem -> cell.cytoplasm[o.species] ?: 0
            org.emerge.demo.cyto.sim.Operand.Biomass -> bioBonds
            org.emerge.demo.cyto.sim.Operand.Touching -> 0
            org.emerge.demo.cyto.sim.Operand.Neighbours -> 0
        }
        for (g in cell.genome) {
            // AND-conjunction: the gate passes iff every clause holds.
            fun clauseStr(c: org.emerge.demo.cyto.sim.Clause): String {
                val l = opVal(c.lhs); val r = opVal(c.rhs)
                val cmp = if (c.cmp == org.emerge.demo.cyto.sim.Comparison.Greater) ">" else "<"
                return "$l$cmp$r"
            }
            val pass = g.condition.clauses.all { c ->
                val l = opVal(c.lhs); val r = opVal(c.rhs)
                if (c.cmp == org.emerge.demo.cyto.sim.Comparison.Greater) l > r else l < r
            }
            var note = if (pass) "GATE OK" else "gate FALSE (${g.condition.clauses.joinToString(" & ") { clauseStr(it) }})"
            if (pass && g.action.type == org.emerge.demo.cyto.sim.ActionType.Convert) {
                val have = cell.cytoplasm[g.action.a] ?: 0
                note += if (have > 0) "; has ${g.action.a}=$have" else "; but NO ${g.action.a} in cytoplasm → 0 ops"
            }
            sb.appendLine("  ${GeneCodec.serialize(listOf(g))}    [$note]")
        }
        val h = handleableOf(cell.genome)
        sb.appendLine("handleable bondTypes=${h.bondTypeCount}; canHold: " +
            listOf("r", "g", "b", "rg", "rgg", "gb").joinToString(" ") { "$it=${h.canHold(SpeciesRegistry.id(it))}" })
        if (pos != null) {
            val rc = grid.contentsAt(org.emerge.demo.cyto.sim.CytoUnits.toLogical(pos.x), org.emerge.demo.cyto.sim.CytoUnits.toLogical(pos.y))
            sb.appendLine("reservoir @ cell footprint = ${LinkedHashMap(rc)}")
        }

        // Replicate the reducer's per-tick LIGHT quanta for this cell (sample × exposure × SCALE), using its
        // connected neighbours for the exposure weight — to test whether a Light-powered Mitosis can fund the
        // biomass/4 cost (the design says it "can't").
        if (pos != null) {
            val springs = state.components.getTable<org.emerge.sim.core.physics.components.SpringConstraintComponent>().asMap()[id]?.springs.orEmpty()
            val angles = LongArray(org.emerge.demo.cyto.sim.CytoExposure.MAX_NEIGHBOURS)
            var ek = 0
            for (s in springs) {
                if (ek >= angles.size) break
                val np = transforms[s.other]?.pos ?: continue
                val d = np - pos   // torus-aware Coord2 - Coord2 -> Frac2, neighbour relative to self
                angles[ek++] = org.emerge.demo.cyto.sim.CytoExposure.diamondAngle(d.x, d.y).raw
            }
            val exposure = org.emerge.demo.cyto.sim.CytoExposure.weight(angles, ek)
            val field = org.emerge.demo.cyto.sim.CytoLightField.default()
            val lx = org.emerge.demo.cyto.sim.CytoUnits.toLogical(pos.x); val ly = org.emerge.demo.cyto.sim.CytoUnits.toLogical(pos.y)
            fun quantaAt(t: Long): Int {
                val sample = field.sampleAt(lx, ly, t)
                return (((sample * exposure) * org.emerge.demo.cyto.sim.CytoTuning.LIGHT_QUANTA_SCALE).raw / Int.MAX_VALUE.toLong()).toInt()
            }
            val nowQ = quantaAt(state.tick)
            // Scan a full daylight orbit to find the peak light this cell actually sees (the current instant
            // may be the dark phase). The orbit base is the tick's phase; sweep a whole period.
            var peakQ = 0
            for (dt in 0 until org.emerge.demo.cyto.sim.CytoTuning.LIGHT_ORBIT_PERIOD) {
                val q = quantaAt(state.tick + dt); if (q > peakQ) peakQ = q
            }
            sb.appendLine("\n--- light energy (exposure=${exposure.toFloat()}, LIGHT_QUANTA_SCALE=${org.emerge.demo.cyto.sim.CytoTuning.LIGHT_QUANTA_SCALE}) ---")
            sb.appendLine("quanta now (tick ${state.tick}) = $nowQ   |   PEAK over the orbit = $peakQ")
            sb.appendLine("Mitosis cost = biomass/4: $bioBonds/4 = ${bioBonds / 4} now; at the gate (Biomass>6000) = 1500")
            sb.appendLine("→ at peak daylight a single Light tick gives ~${peakQ} quanta vs a 1500 cost — light ${if (peakQ >= 1500) "EASILY funds" else "cannot fund"} division (${if (peakQ >= 1500) "%.0fx".format(peakQ / 1500.0) else "-"})")
        }
        // Connected siblings: do they share the Light-Mitosis trait?
        val springs2 = state.components.getTable<org.emerge.sim.core.physics.components.SpringConstraintComponent>().asMap()[id]?.springs.orEmpty()
        sb.appendLine("\n--- ${springs2.size} connected siblings (does each have a Light-powered Mitosis gene?) ---")
        var lightMito = 0
        for (s in springs2) {
            val sib = cells[s.other] ?: continue
            val has = sib.genome.any { it.action.type == org.emerge.demo.cyto.sim.ActionType.Mitosis && it.source is org.emerge.demo.cyto.sim.EnergySource.Light }
            if (has) lightMito++
            sb.appendLine("  ${s.other.value}: Light-Mitosis=${has}  biomass=${org.emerge.demo.cyto.sim.totalBiomassBonds(sib.biomass)}")
        }
        sb.appendLine("→ $lightMito/${springs2.size} connected siblings carry a Light-powered Mitosis gene")
        java.io.File("/tmp/inspectcell.txt").writeText(sb.toString())
    }
}
