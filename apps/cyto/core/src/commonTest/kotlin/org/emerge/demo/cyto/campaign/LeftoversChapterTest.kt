package org.emerge.demo.cyto.campaign

import org.emerge.demo.cyto.cells.CellType
import org.emerge.demo.cyto.host.CampaignContent
import org.emerge.demo.cyto.sim.ActionType
import org.emerge.demo.cyto.sim.Comparison
import org.emerge.demo.cyto.sim.Clause
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
 * Leftovers points the CONVERT gene at the two-atom molecule the rest of the genome already runs on, and
 * then has to rescue the lineage from what that costs. The arc only makes sense as a pair of changes:
 *
 *   - Retarget CONVERT alone: the gene goes inert on the spot (an action reads its input from the tick-start
 *     snapshot, and the recycling gene clears the cytoplasm out every morning) and the cell ruptures about
 *     4,200 ticks later.
 *   - Add a floor of 100 to the recycler as well: biomass returns to its 3000 cap and holds.
 *
 * Both readings are measured in the real world, and the gates below sit inside them.
 */
class LeftoversChapterTest {

    private val chapter = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.LEFTOVERS }

    private fun gate(prefix: String): Gate.World =
        chapter.steps.mapNotNull { it.gate as? Gate.World }.single { it.desc.startsWith(prefix) }

    private fun query(
        convertTarget: String?, reserve: Int? = null, biomass: Int = 3000, cells: Int = 1,
    ) = CampaignQuery(
        WorldStats(
            0L, cells, mapOf(CellType.Collector to cells), biomass, emptySet(),
            if (cells == 0) null else FocusedCell(CellType.Collector, biomass, mapOf("gb" to 100)),
            Lineage(
                geneCount = 3, convertChem = convertTarget, convertProduct = "gb", convertBiomassCap = 3000,
                divideBiomassMinimum = 2000, hasDivide = true, divideProduct = "gb",
                hasPhotosynthesis = true, recycleReserve = reserve, divideFuelConflicts = false,
            ),
        ),
        paused = false, selectedGenome = null,
    )

    @Test
    fun theRetargetGoalWantsTheDivideProductNotTheMonomer() {
        val g = gate("Point the CONVERT gene")
        assertFalse(g.met(query("r")), "still on the bare monomer")
        assertFalse(g.met(query("")), "CONVERT exists but nothing is picked")
        assertTrue(g.met(query("gb")))
    }

    /** The die-off is the beat, so it moves on by itself rather than leaving "now let it run" on screen over
     *  an empty world — the same treatment ch01's rupture beat gets. */
    @Test
    fun theDieOffAdvancesItself() {
        val step = chapter.steps.single { (it.gate as? Gate.World)?.desc?.startsWith("Watch what happens") == true }
        assertTrue(step.autoAdvance, "the die-off must not wait for a click")
        assertTrue(step.allow.allows(Control.Spawn), "and the player needs to be able to put a cell back")
    }

    /**
     * The reserve gate takes a band, not the number in the copy. The copy says 100 because that is what the
     * sweep landed on, but a player who reasons their way to 150 has understood the lesson and must not be
     * told they are wrong.
     */
    @Test
    fun theReserveGoalAcceptsAnyWorkingReserve() {
        val g = gate("Block the BREAK gene")
        assertFalse(g.met(query("gb", reserve = null)), "no condition at all is the state they start in")
        assertFalse(g.met(query("gb", reserve = 0)), "> 0 still takes the lot")
        assertTrue(g.met(query("gb", reserve = 100)), "the value the copy suggests")
        assertTrue(g.met(query("gb", reserve = 150)), "a player who reasoned their own way there")
    }

    /**
     * The closing "watch it come back" beat is a `Gate.Next`, not a measured one: it was a
     * `Gate.World("Grow the cell back") { biomass >= 2800 }` until `edb38f5a`, which made the payoff
     * something the player reads rather than something they wait out. Two tests covering that threshold
     * retired with it. If the wait ever comes back, so should they — the numbers that worked were
     * biomass 1200 = still falling, 2999 = recovered, and an empty world must not pass by default.
     */
    @Test
    fun theClosingBeatIsReadNotWaitedOut() {
        assertEquals(Gate.Next, chapter.steps.last().gate)
    }

    @Test
    fun nightShiftLeadsHere() {
        val night = CampaignContent.PLAYABLE_CHAPTERS.first { it.id == CampaignContent.NIGHT_SHIFT }
        assertEquals(listOf(CampaignContent.LEFTOVERS), night.branchesTo)
        assertEquals(CampaignContent.LEFTOVERS, night.next?.invoke(query("gb")))
        assertEquals(
            listOf(CampaignContent.NIGHT_SHIFT),
            CampaignContent.predecessorsOf(CampaignContent.LEFTOVERS, CampaignContent.PLAYABLE_CHAPTERS),
        )
    }

    // ── the reading itself ────────────────────────────────────────────────────────────────────────────

    private fun clause(species: String, cmp: Comparison, v: Int) =
        Clause(Operand.Chem(species), cmp, Operand.Constant(v))

    private fun recycler(vararg clauses: Clause, source: EnergySource = EnergySource.Light) =
        Gene(source, GeneCondition(clauses.toList()), GeneAction(ActionType.BreakBond, "g", "b"))

    private val divide = Gene(
        EnergySource.FormBond("g", "b"), GeneCondition(emptyList()), GeneAction(ActionType.Divide),
    )

    @Test
    fun anUnconditionalRecyclerHasNoReserve() {
        assertNull(lineageOf(listOf(divide, recycler())).recycleReserve)
    }

    @Test
    fun theReserveIsTheThresholdOnTheWasteItself() {
        assertEquals(100, lineageOf(listOf(divide, recycler(clause("gb", Comparison.Greater, 100)))).recycleReserve)
    }

    /** Every clause has to hold, so the largest threshold is the one that actually bites — the same rule as
     *  the divide floor. */
    @Test
    fun theLargestThresholdBites() {
        val g = recycler(clause("gb", Comparison.Greater, 100), clause("gb", Comparison.Greater, 400))
        assertEquals(400, lineageOf(listOf(divide, g)).recycleReserve)
    }

    /** A threshold on some OTHER chemical is not a reserve of the waste — it gates when the gene runs, not
     *  how much it leaves behind. */
    @Test
    fun aThresholdOnAnotherChemicalIsNotAReserve() {
        assertNull(lineageOf(listOf(divide, recycler(clause("g", Comparison.Less, 200)))).recycleReserve)
    }

    /** And `< N` is a ceiling, not a floor: it would run the gene only while the waste is LOW, which is the
     *  opposite of leaving a reserve. */
    @Test
    fun aCeilingIsNotAReserve() {
        assertNull(lineageOf(listOf(divide, recycler(clause("gb", Comparison.Less, 100)))).recycleReserve)
    }

    /** Read off the recycling gene specifically. A chemistry-powered break is a different gene doing a
     *  different job, and its threshold must not read as this one's reserve. */
    @Test
    fun onlyTheLightPoweredRecyclerCounts() {
        val chem = recycler(clause("gb", Comparison.Greater, 100), source = EnergySource.FormBond("r", "g"))
        assertNull(lineageOf(listOf(divide, chem)).recycleReserve)
    }
}
