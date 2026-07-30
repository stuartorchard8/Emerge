package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.CytoTestWorld
import org.emerge.demo.cyto.sim.CytoWorldConfig
import org.emerge.demo.cyto.sim.GeneCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **What a gene's condition reads right now**, published beside the clause so the card can show
 * `BIO 1840 > 2000` rather than a bare `BIO` that is either orange or not.
 *
 * The readings come from the same `operandValue` the blocking flags do, which is the property worth pinning:
 * a number that disagreed with the colour beside it would be worse than no number at all.
 */
class GeneClauseReadingsTest {

    /** A cell holding [cytoplasm] and [biomass], with [genome] parsed from `.gene` text. */
    private fun rowFor(genome: String, cytoplasm: Map<String, Int>, biomass: Map<String, Int>) =
        CytoTestWorld.empty()
            .cell("subject", genome = GeneCodec.parse(genome), cytoplasm = cytoplasm, biomass = biomass)
            .build()
            .let { f ->
                CytoWorldConfig.applyFrom(f.scenario)
                val c = CytoController(scenario = f.scenario)
                c.focusFixtureCell(f, "subject")
                (c.heldCellInfo() ?: error("no panel info")).genes[0]
            }

    @Test fun aLiveSideReadsItsCurrentValueAndAConstantReadsNothing() {
        val row = rowFor(
            "Light : Biomass < 3000 : Convert r",
            cytoplasm = mapOf("r" to 40), biomass = mapOf("r" to 1800),
        )
        val reading = row.readings.single()
        assertEquals(1800, reading.lhs, "BIO is what the cell actually masses")
        assertNull(reading.rhs, "3000 is already written on the card; repeating it would read as a fault")
    }

    @Test fun aCytoplasmCountReadsTheCellsOwnStock() {
        val row = rowFor(
            "Light : r > 100 : Convert r",
            cytoplasm = mapOf("r" to 320), biomass = mapOf("r" to 1000),
        )
        assertEquals(320, row.readings.single().lhs)
    }

    /** A species the cell doesn't hold reads 0, not blank — "none of it" is the answer to the question. */
    @Test fun anAbsentSpeciesReadsZero() {
        val row = rowFor(
            "Light : gg > 10 : Convert r",
            cytoplasm = mapOf("r" to 320), biomass = mapOf("r" to 1000),
        )
        assertEquals(0, row.readings.single().lhs)
    }

    /** Both sides can be live at once, and both are then reported. */
    @Test fun twoLiveSidesBothRead() {
        val row = rowFor(
            "Light : r > g : Convert r",
            cytoplasm = mapOf("r" to 320, "g" to 75), biomass = mapOf("r" to 1000),
        )
        val reading = row.readings.single()
        assertEquals(320, reading.lhs)
        assertEquals(75, reading.rhs)
    }

    /** One reading per clause, in the gene's own order — the card pairs them off by index. */
    @Test fun readingsLineUpWithTheClausesOneForOne() {
        val row = rowFor(
            "Light : Biomass < 3000 & r > 100 : Convert r",
            cytoplasm = mapOf("r" to 320), biomass = mapOf("r" to 1800),
        )
        assertEquals(2, row.readings.size)
        assertEquals(1800, row.readings[0].lhs)
        assertEquals(320, row.readings[1].lhs)
    }

    /**
     * The number and the colour beside it must tell the same story: a clause the panel flags as blocking has
     * to be one the reading itself fails.
     */
    @Test fun theReadingAgreesWithTheBlockingFlag() {
        val row = rowFor(
            "Light : Biomass < 3000 : Convert r",
            cytoplasm = mapOf("r" to 40), biomass = mapOf("r" to 4000),
        )
        assertEquals(4000, row.readings.single().lhs, "over the 3000 ceiling")
        assertTrue(
            row.spans.first { it.text.contains("<") }.blocking,
            "so the clause must read as what's blocking the gene: '${row.desc}'",
        )
    }

    /** A gene with no condition is ALWAYS, and has nothing to report. */
    @Test fun anAlwaysGeneHasNoReadings() {
        val row = rowFor("Light : : Convert r", cytoplasm = mapOf("r" to 40), biomass = mapOf("r" to 1000))
        assertEquals(emptyList(), row.readings)
    }
}
