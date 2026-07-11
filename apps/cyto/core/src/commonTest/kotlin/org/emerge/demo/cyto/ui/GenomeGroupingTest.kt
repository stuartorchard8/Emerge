package org.emerge.demo.cyto.ui

import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Operand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GenomeGrouping.sections] buckets a live genome by structural gene equality (CAMPAIGN_PLAN.md §10),
 *  preserving live indices, collating by group, and never depending on storage order. */
class GenomeGroupingTest {

    private fun grow() = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(3000)), GeneAction(ActionType.Convert, "rg"))
    private fun bond() = Gene(EnergySource.Light, GeneCondition(Operand.Chem("rg"), Comparison.Less, Operand.Constant(3000)), GeneAction(ActionType.FormBond, "r", "g"))
    private fun divide() = Gene(EnergySource.BreakBond("rg"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(2000)), GeneAction(ActionType.Mitosis))

    private val grouping = GenomeGrouping(listOf(
        GeneGroup("Grow", 0x1L, listOf(grow(), bond())),
        GeneGroup("Reproduce", 0x2L, listOf(divide())),
    ))

    @Test fun onePresentGroupOnly() {
        // A grow-only genome shows a single non-empty section; the empty Reproduce group is omitted.
        val secs = grouping.sections(listOf(grow(), bond()))
        assertEquals(1, secs.size)
        assertEquals("Grow", secs[0].name)
        assertEquals(listOf(0, 1), secs[0].items.map { it.index })
    }

    @Test fun addedGeneBucketsIntoItsGroup() {
        // Appending the divide gene surfaces the Reproduce section, preserving live indices.
        val secs = grouping.sections(listOf(grow(), bond(), divide()))
        assertEquals(listOf("Grow", "Reproduce"), secs.map { it.name })
        assertEquals(listOf(2), secs.last().items.map { it.index })
    }

    @Test fun collatesNonContiguousMembersWithoutReordering() {
        // Grow genes at live indices 0 and 2 collate into one section though a foreign gene sits between them;
        // the foreign gene falls to the unnamed "Other" bucket. Display order != storage order, by design.
        val foreign = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(1)), GeneAction(ActionType.Repair))
        val secs = grouping.sections(listOf(grow(), foreign, bond()))
        val growSec = secs.first { it.name == "Grow" }
        assertEquals(listOf(0, 2), growSec.items.map { it.index })
        val other = secs.first { it.isOther }
        assertNull(other.name)
        assertEquals(listOf(1), other.items.map { it.index })
    }

    @Test fun emptyGenomeYieldsNoSections() {
        assertTrue(grouping.sections(emptyList()).isEmpty())
    }
}
