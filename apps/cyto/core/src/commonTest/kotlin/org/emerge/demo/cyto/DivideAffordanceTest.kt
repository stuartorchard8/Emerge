package org.emerge.demo.cyto

import org.emerge.demo.cyto.CytoController.CellInfo
import org.emerge.demo.cyto.sim.CytoFixtures
import org.emerge.demo.cyto.sim.CytoTestWorld
import org.emerge.demo.cyto.sim.CytoWorldConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The DIVIDE affordance's discriminating case**, which is the reason `CytoTestWorld` exists.
 *
 * Division needs `biomass/4` energy units in ONE tick, and the division phase splits the cell's means by
 * the number of gated-open DIVIDE genes. So two genes that would each divide happily on their own can both
 * be unfunded together — the cell reads perfectly active and never divides. The gene card flags that on the
 * SOURCE token; this pins the flag against a stated world, at numbers chosen to sit clear of the boundary
 * on both sides (funded at 1.5× cost, unfunded at 0.75×).
 *
 * Before the fixture builder this was untestable end-to-end: no live world could be steered into the window
 * that separates the two cases, so the behaviour was pinned only by a unit test on the arithmetic — which
 * would have kept passing if the panel had stopped consulting it.
 */
class DivideAffordanceTest {

    private fun cellInfoFor(fixture: CytoTestWorld.Fixture): CellInfo {
        CytoWorldConfig.applyFrom(fixture.scenario)
        val c = CytoController(scenario = fixture.scenario)
        c.focusFixtureCell(fixture, "divider")
        return c.heldCellInfo() ?: error("the fixture's cell has no panel info")
    }

    /** The source token — `BND r·g` — is the one the unfunded flag rides on. */
    private fun sourceSpan(row: CellInfo.GeneRow) =
        row.spans.firstOrNull { it.text.startsWith("BND") } ?: error("no source span in '${row.desc}'")

    @Test fun oneDivideGeneAffordsTheSplit() {
        val info = cellInfoFor(CytoFixtures.divideContention(genes = 1))
        assertEquals(1, info.genes.size)
        val row = info.genes[0]
        assertFalse(
            sourceSpan(row).blocking,
            "a lone DIVIDE gene draws the whole fuel pool (1500 >= cost 1000) and must read funded: '${row.desc}'",
        )
        assertTrue(row.active, "nothing else should be blocking it either: '${row.desc}'")
    }

    @Test fun twoContendingDivideGenesAreBothUnfunded() {
        val info = cellInfoFor(CytoFixtures.divideContention(genes = 2))
        assertEquals(2, info.genes.size, "the fixture must present both contending genes")
        for ((i, row) in info.genes.withIndex()) {
            assertTrue(
                sourceSpan(row).blocking,
                "with two DIVIDE genes splitting the pool each draws 750 < cost 1000, so gene $i must read " +
                    "unfunded on its SOURCE token: '${row.desc}'",
            )
        }
    }

    /**
     * **The panel's claim, checked against the sim.** `describeGeneSpans` mirrors `CytoBiologyCore`'s gating
     * rather than calling it, so agreement is a real thing to test and not a tautology: the funded fixture
     * must actually divide, and the contended one must actually sit there. Without this the two tests above
     * would only pin the panel's own arithmetic.
     */
    @Test fun theSimAgreesWithWhatThePanelSays() {
        fun cellsAfterATick(fixture: CytoTestWorld.Fixture): Int {
            CytoWorldConfig.applyFrom(fixture.scenario)
            val c = CytoController(scenario = fixture.scenario)
            c.loadFixture(fixture)
            c.stepOnce()
            c.publish()
            return c.worldStats().cellCount
        }
        assertEquals(2, cellsAfterATick(CytoFixtures.divideContention(genes = 1)),
            "the funded gene must actually divide on the first tick, not merely read funded")
        assertEquals(1, cellsAfterATick(CytoFixtures.divideContention(genes = 2)),
            "two contending genes must actually fail to divide, not merely read unfunded")
    }

    /**
     * The point of the pair: the *only* difference between the two states is how many genes are contending.
     * If this stops holding, the split is no longer what decides funding and the two cases above could both
     * be passing for some other reason.
     */
    @Test fun contentionIsTheOnlyDifferenceBetweenTheTwoStates() {
        val one = cellInfoFor(CytoFixtures.divideContention(genes = 1))
        val two = cellInfoFor(CytoFixtures.divideContention(genes = 2))
        assertEquals(one.totalBiomass, two.totalBiomass, "same biomass, so the same division cost")
        assertEquals(one.genes[0].gene, two.genes[0].gene, "same gene in both worlds")
        assertFalse(sourceSpan(one.genes[0]).blocking)
        assertTrue(sourceSpan(two.genes[0]).blocking)
    }
}
