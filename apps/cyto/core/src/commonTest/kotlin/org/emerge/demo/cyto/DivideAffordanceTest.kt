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
     * The flag says "not funded"; the [CellInfo.Fuel] reading says **by how much**, which is the difference
     * between "something is wrong" and "you are three quarters of the way there". Both numbers are the ones
     * the sim uses: `energyUnits` split by the contending count, over `biomass/4`.
     */
    @Test fun theFuelReadingSaysHowCloseTheGeneIs() {
        val one = cellInfoFor(CytoFixtures.divideContention(genes = 1)).genes[0]
        val two = cellInfoFor(CytoFixtures.divideContention(genes = 2)).genes[0]

        assertEquals(1000, one.fuel?.required, "cost is biomass/4, and the fixture holds 4000")
        assertEquals(1500, one.fuel?.available, "a lone gene draws min(1500, 1500)")
        assertFalse(one.fuel!!.short)

        assertEquals(1000, two.fuel?.required, "the same cost - contention doesn't change what a split costs")
        assertEquals(750, two.fuel?.available, "two contending genes halve the pool each gene can draw")
        assertTrue(two.fuel!!.short)
    }

    /** The reading is on the card as well as in the model — beside the source, where the shortfall is. */
    @Test fun theFuelReadingIsWrittenOnTheCard() {
        val row = cellInfoFor(CytoFixtures.divideContention(genes = 2)).genes[0]
        assertTrue(row.desc.contains("750/1000"), "the card must show the ratio: '${row.desc}'")
        assertTrue(
            row.spans.first { it.text.contains("750/1000") }.blocking,
            "and colour it as part of what's blocking, since this is why the gene won't fire",
        )
    }

    /**
     * Only DIVIDE carries a reading. Every other action simply does fewer ops when energy is short, so a
     * ratio there would imply an all-or-nothing threshold that doesn't exist.
     */
    @Test fun otherActionsHaveNoFuelReading() {
        val info = cellInfoFor(
            CytoTestWorld.empty()
                .cell(
                    "divider",
                    genome = org.emerge.demo.cyto.sim.GeneCodec.parse("Light : Biomass < 3000 : Convert r"),
                    cytoplasm = mapOf("r" to 500),
                    biomass = mapOf("r" to 1000),
                )
                .build(),
        )
        assertEquals(null, info.genes[0].fuel, "a CONVERT gene has no threshold to be close to")
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
