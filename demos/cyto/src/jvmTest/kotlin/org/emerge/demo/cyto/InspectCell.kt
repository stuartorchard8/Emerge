package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoMatterGrid
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
 *   ./gradlew :demos:cyto:jvmTest --tests "*InspectCell*" -Dinspectcell=1803
 */
class InspectCell {

    @Test
    fun inspect() {
        val target = System.getProperty("inspectcell")?.toIntOrNull() ?: return
        val path = System.getProperty("savefile") ?: "/home/stu/emerge/platform/desktop-app/cyto-save.bin"
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
        val grid = state.components.getTable<CytoMatterGridComponent>()[GRID_SINGLETON]?.grid ?: CytoMatterGrid.empty()
        val gridIndex = if (pos != null) {
            grid.indexOf(org.emerge.demo.cyto.sim.CytoUnits.toLogical(pos.x), org.emerge.demo.cyto.sim.CytoUnits.toLogical(pos.y))
        } else -1

        sb.appendLine("\n=== cell $target (type ${cell.type}) ===")
        sb.appendLine("logicalRadius=${cell.logicalRadius.raw} wear=${cell.wear} gridIndex=$gridIndex")
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
        }
        for (g in cell.genome) {
            val c0 = g.condition
            val l = opVal(c0.lhs); val r = opVal(c0.rhs)
            val pass = if (c0.cmp == org.emerge.demo.cyto.sim.Comparison.Greater) l > r else l < r
            val cmp = if (c0.cmp == org.emerge.demo.cyto.sim.Comparison.Greater) ">" else "<"
            var note = if (pass) "GATE OK" else "gate FALSE ($l$cmp$r)"
            if (pass && g.action.type == org.emerge.demo.cyto.sim.ActionType.Convert) {
                val have = cell.cytoplasm[g.action.a] ?: 0
                note += if (have > 0) "; has ${g.action.a}=$have" else "; but NO ${g.action.a} in cytoplasm → 0 ops"
            }
            sb.appendLine("  ${GeneCodec.serialize(listOf(g))}    [$note]")
        }
        val h = handleableOf(cell.genome)
        sb.appendLine("handleable bondTypes=${h.bondTypeCount}; canHold: " +
            listOf("a", "b", "c", "ab", "abb", "bb").joinToString(" ") { "$it=${h.canHold(SpeciesRegistry.id(it))}" })
        if (gridIndex >= 0) {
            val res = LinkedHashMap<String, Int>()
            val rc = grid.cellAt(gridIndex)
            for ((sp, n) in rc) res[sp] = n
            sb.appendLine("reservoir @ $gridIndex = $res")
        }
        java.io.File("/tmp/inspectcell.txt").writeText(sb.toString())
    }
}
