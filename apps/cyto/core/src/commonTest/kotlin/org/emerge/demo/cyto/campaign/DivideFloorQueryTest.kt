package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Clause
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Operand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two readings the ch01 divide-floor beat and the ch02 exhaust beat gate on:
 * [Lineage.divideBiomassMinimum] and [Lineage.hasPhotosynthesis]. `lineageOf` is pure, so these read it
 * directly — that it comes off the live genome is already covered by [GrowthCapQueryTest].
 */
class DivideFloorQueryTest {
    private fun bioOver(n: Int) = Clause(Operand.Biomass, Comparison.Greater, Operand.Constant(n))

    private fun divide(vararg clauses: Clause) = Gene(
        EnergySource.FormBond("g", "b"),
        GeneCondition(clauses.toList()),
        GeneAction(ActionType.Divide),
    )

    @Test
    fun anUnconditionalDivideGeneReportsNoFloor() {
        assertNull(lineageOf(listOf(divide())).divideBiomassMinimum)
    }

    @Test
    fun aBiomassGreaterClauseIsTheFloor() {
        assertEquals(2000, lineageOf(listOf(divide(bioOver(2000)))).divideBiomassMinimum)
    }

    /** All clauses have to hold, so the LARGEST floor is the one that actually bites — the mirror of the
     *  CONVERT cap, where the smallest ceiling wins. */
    @Test
    fun theLargestFloorWins() {
        assertEquals(2500, lineageOf(listOf(divide(bioOver(1000), bioOver(2500)))).divideBiomassMinimum)
    }

    /** `Biomass < N` stops the gene rather than arming it; reading it as a floor would pass the chapter's
     *  gate on a gene that still divides its daughters to death. */
    @Test
    fun aBiomassCeilingIsNotAFloor() {
        val ceiling = Clause(Operand.Biomass, Comparison.Less, Operand.Constant(2000))
        assertNull(lineageOf(listOf(divide(ceiling))).divideBiomassMinimum)
    }

    /** The floor belongs to the DIVIDE gene — a floor on the CONVERT gene doesn't stop it splitting. */
    @Test
    fun aFloorOnAnotherGeneDoesNotCount() {
        val convert = Gene(EnergySource.Light, GeneCondition(listOf(bioOver(2000))), GeneAction(ActionType.Convert, "r"))
        assertNull(lineageOf(listOf(convert, divide())).divideBiomassMinimum)
    }

    private fun breakGene(source: EnergySource, a: String, b: String) =
        Gene(source, GeneCondition(emptyList()), GeneAction(ActionType.BreakBond, a, b))

    @Test
    fun aLightPoweredBreakOfTheFuelProductIsTheRecyclingGene() {
        val g = lineageOf(listOf(divide(), breakGene(EnergySource.Light, "g", "b")))
        assertEquals("gb", g.divideProduct)
        assertTrue(g.hasPhotosynthesis)
    }

    /** Breaking some *other* molecule leaves the exhaust exactly where it was. */
    @Test
    fun breakingAnotherMoleculeIsNotTheRecyclingGene() {
        assertFalse(lineageOf(listOf(divide(), breakGene(EnergySource.Light, "r", "g"))).hasPhotosynthesis)
    }

    /** Paying for the break with chemistry just trades one bond for another — the beat is about sunlight
     *  funding work that no bond has to. */
    @Test
    fun aChemistryPoweredBreakIsNotTheRecyclingGene() {
        val chem = breakGene(EnergySource.FormBond("r", "g"), "g", "b")
        assertFalse(lineageOf(listOf(divide(), chem)).hasPhotosynthesis)
    }

    /** With no fuel reaction there is no exhaust, so nothing can read as recycling it. */
    @Test
    fun withNoFuelReactionThereIsNoRecyclingGene() {
        val lightDivide = Gene(EnergySource.Light, GeneCondition(emptyList()), GeneAction(ActionType.Divide))
        assertFalse(lineageOf(listOf(lightDivide, breakGene(EnergySource.Light, "g", "b"))).hasPhotosynthesis)
    }
}
