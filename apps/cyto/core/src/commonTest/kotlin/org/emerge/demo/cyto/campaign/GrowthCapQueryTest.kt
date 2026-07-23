package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Clause
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.CytoCellComponent
import org.emerge.demo.cyto.sim.EnergySource
import org.emerge.demo.cyto.sim.Gene
import org.emerge.demo.cyto.sim.GeneAction
import org.emerge.demo.cyto.sim.GeneCondition
import org.emerge.demo.cyto.sim.Operand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [Lineage.convertBiomassCap] — the reading `ch01-divide` gates its growth-limiting step on. It has to
 * come off the *live* genome through the same path the coach reads (`worldStats`), because the whole point
 * of the step is that the player's own edit satisfies it.
 */
class GrowthCapQueryTest {
    private fun convert(vararg clauses: Clause) = Gene(
        EnergySource.Light,
        GeneCondition(clauses.toList()),
        GeneAction(ActionType.Convert, "r"),
    )

    private fun bioUnder(n: Int) = Clause(Operand.Biomass, Comparison.Less, Operand.Constant(n))

    /** Focus the founder and give it [genome], then read the campaign's view of it. */
    private fun focusedWith(genome: List<Gene>): Lineage {
        val c = CytoController()
        val frame = c.tick(0f)
        val id = frame.state.components.getTable<CytoCellComponent>().asMap().keys.first()
        c.focus(id)
        // Clear the founder's own genome so the cell carries exactly the one under test. Edits are queued
        // and applied at the top of a tick, so each needs its own tick(0f) — which applies pending edits
        // without stepping the world.
        repeat(c.heldGenome()!!.size) { c.deleteHeldGene(0); c.tick(0f) }
        c.addHeldGenes(genome)
        c.tick(0f)
        return c.worldStats().lineage!!
    }

    @Test
    fun anUncappedConvertGeneReportsNoCeiling() {
        assertNull(focusedWith(listOf(convert())).convertBiomassCap, "a gene with no condition grows forever")
    }

    @Test
    fun aBiomassLessClauseIsTheCeiling() {
        assertEquals(3000, focusedWith(listOf(convert(bioUnder(3000)))).convertBiomassCap)
    }

    /** All the clauses have to hold, so the tightest is the one that actually bites. */
    @Test
    fun theSmallestCeilingWins() {
        assertEquals(2500, focusedWith(listOf(convert(bioUnder(4000), bioUnder(2500)))).convertBiomassCap)
    }

    /** A ceiling is `Biomass < N`. `Biomass > N` is a floor — it starts the gene rather than stopping it,
     *  and reading it as a cap would pass the chapter's gate on a gene that still grows without limit. */
    @Test
    fun aBiomassFloorIsNotACeiling() {
        val floor = Clause(Operand.Biomass, Comparison.Greater, Operand.Constant(3000))
        assertNull(focusedWith(listOf(convert(floor))).convertBiomassCap)
    }

    /** The cap belongs to the CONVERT gene — a limit on some other gene doesn't stop the cell growing. */
    @Test
    fun aCeilingOnAnotherGeneDoesNotCount() {
        val cappedDivide = Gene(
            EnergySource.Light,
            GeneCondition(listOf(bioUnder(3000))),
            GeneAction(ActionType.Mitosis),
        )
        assertNull(focusedWith(listOf(convert(), cappedDivide)).convertBiomassCap)
    }
}
