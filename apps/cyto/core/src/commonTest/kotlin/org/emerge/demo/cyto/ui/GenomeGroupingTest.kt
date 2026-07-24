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

/** [GenomeGrouping.sections] buckets a live genome by each gene's [Gene.group] tag (CAMPAIGN_PLAN.md §10) —
 *  no matching — preserving live indices and collating by group in registry order. */
class GenomeGroupingTest {

    private fun grow(group: String = "Grow") = Gene(EnergySource.Light, GeneCondition(Operand.Biomass, Comparison.Less, Operand.Constant(3000)), GeneAction(ActionType.Convert, "rg"), group = group)
    private fun bond(group: String = "Grow") = Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Chem("rg"), Comparison.Less, Operand.Constant(3000)), GeneAction(ActionType.Convert, "rg"), group = group)
    private fun divide(group: String = "Reproduce") = Gene(EnergySource.FormBond("r", "g"), GeneCondition(Operand.Biomass, Comparison.Greater, Operand.Constant(2000)), GeneAction(ActionType.Divide), group = group)

    private val grouping = GenomeGrouping(listOf(
        GeneGroup("Grow"),
        GeneGroup("Reproduce"),
    ))

    @Test fun onePresentGroupOnly() {
        val secs = grouping.sections(listOf(grow(), bond()))
        assertEquals(1, secs.size)
        assertEquals("Grow", secs[0].name)
        assertEquals(listOf(0, 1), secs[0].items.map { it.index })
    }

    @Test fun tagSurvivesAnEditThatChangesGeneContents() {
        // A divide gene edited (SEVER toggled → rejectMother flipped) keeps its "Reproduce" tag, so it stays
        // in its group — the whole point of tag-based grouping vs matching by contents.
        val edited = divide().let { it.copy(action = it.action.copy(rejectMother = !it.action.rejectMother)) }
        val secs = grouping.sections(listOf(grow(), bond(), edited))
        assertEquals(listOf("Grow", "Reproduce"), secs.map { it.name })
        assertEquals(listOf(2), secs.last().items.map { it.index })
    }

    @Test fun collatesByTagInRegistryOrderNotStorageOrder() {
        // Grow genes at live indices 0 and 2 collate into one section though a Reproduce gene sits between
        // them; display order (Grow then Reproduce) follows the registry, not storage order.
        val secs = grouping.sections(listOf(grow(), divide(), bond()))
        val growSec = secs.first { it.name == "Grow" }
        assertEquals(listOf(0, 2), growSec.items.map { it.index })
        assertEquals(listOf("Grow", "Reproduce"), secs.map { it.name })
    }

    @Test fun untaggedGenesFallToOther() {
        val secs = grouping.sections(listOf(grow(), bond(group = "")))
        val other = secs.first { it.isOther }
        assertNull(other.name)
        assertEquals(listOf(1), other.items.map { it.index })
    }

    @Test fun unregisteredTagKeepsItsName() {
        val secs = grouping.sections(listOf(divide(group = "Locomotion")))
        assertEquals("Locomotion", secs.single().name)
    }

    @Test fun emptyGenomeYieldsNoSections() {
        assertTrue(grouping.sections(emptyList()).isEmpty())
    }

    @Test fun autoColorIsDeterministicPerName() {
        // A group's colour is a pure function of its name (no registry) — stable across calls, and the same
        // name always yields the same colour so a group looks identical everywhere it appears.
        assertEquals(GenomeGrouping.autoColor("Polarise"), GenomeGrouping.autoColor("Polarise"))
        assertTrue(GenomeGrouping.autoColor("Grow") and 0xFFL == 0xFFL, "opaque (full alpha)")
    }

    @Test fun registrylessGroupingStillBucketsByTag() {
        // Free-play path: no registered groups, but a tagged genome still splits into named sections.
        val secs = GenomeGrouping(emptyList()).sections(listOf(grow(), divide()))
        assertEquals(setOf("Grow", "Reproduce"), secs.mapNotNull { it.name }.toSet())
    }
}
