package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.CytoConfig
import org.emerge.demo.cyto.sim.soa.CytoSoaReducer
import org.emerge.demo.cyto.sim.soa.CytoWorld
import kotlin.test.Test

/**
 * Throwaway diagnostic: is `CytoCellComponent.activeMask` actually populated and varying after a few
 * ticks of the real reducer on a real save? A mask that were always 0 would render every gene particle
 * inactive and look plausible on screen — so check the data, not the picture.
 *   ./gradlew :apps:cyto:core:jvmTest --tests "*GeneActiveProbe*" -Dgeneprobe=1
 */
class GeneActiveProbe {

    @Test
    fun probe() {
        if (System.getProperty("geneprobe") == null) return
        val path = System.getProperty("savefile") ?: "/home/stu/emerge/apps/cyto/desktop/cyto-save.bin"
        val initial = CytoSaveCodec.decode(java.io.File(path).readBytes())
        val soa = CytoSoaReducer(CytoConfig())
        var w = CytoWorld.fromSimState(initial)
        for (t in 1..8) w = soa.tick(w, org.emerge.demo.cyto.sim.CytoInput.EMPTY)
        val cells = w.toSimState().components.getTable<CytoCellComponent>().asMap().values.toList()

        val counts = cells.map { it.activeMask.countOneBits() }
        val hist = counts.groupingBy { it }.eachCount().toSortedMap()
        println("=== activeMask after 8 ticks: cells=${cells.size}")
        println("active genes per cell: min=${counts.min()} max=${counts.max()} mean=${"%.2f".format(counts.average())}")
        println("histogram(activeGenes->cells): $hist")
        println("cells with a zero mask: ${counts.count { it == 0 }}")
        // Which gene indices ever fire — a mask stuck on one bit would also be suspicious.
        var union = 0L
        for (c in cells) union = union or c.activeMask
        println("union of active bits: ${union.toString(2)}")
    }
}
