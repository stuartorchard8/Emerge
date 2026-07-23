package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.CytoController
import org.emerge.demo.cyto.fixtureCell
import org.emerge.demo.cyto.loadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The fixture builder is load-bearing for every test written on top of it, so its own promises are pinned
 * here: a cell arrives in the world with exactly the chemistry it was given, the names come back, and
 * "in daylight" / "in the dark" actually mean that.
 */
class CytoTestWorldTest {

    private fun controllerFor(f: CytoTestWorld.Fixture): CytoController {
        CytoWorldConfig.applyFrom(f.scenario)
        return CytoController(scenario = f.scenario).also { it.loadFixture(f) }
    }

    @Test fun aStatedCellArrivesWithExactlyTheChemistryItWasGiven() {
        val f = CytoTestWorld.empty()
            .cell("a", cytoplasm = mapOf("r" to 700, "gb" to 3), biomass = mapOf("rg" to 250))
            .matter(level = 0)
            .build()
        val c = controllerFor(f)
        c.focus(c.fixtureCell(f, "a"))
        c.publish()
        val info = c.heldCellInfo()!!
        assertEquals(500, info.totalBiomass, "biomass is counted in ATOMS: 250 x 'rg' = 500")
        val cyto = c.worldStats().focused!!.cytoplasm
        assertEquals(700, cyto["r"], "cytoplasm must survive the build + save round-trip unchanged")
        assertEquals(3, cyto["gb"])
    }

    @Test fun namedCellsComeBackAfterTheRoundTrip() {
        val f = CytoTestWorld.empty()
            .cell("first", cytoplasm = mapOf("r" to 1))
            .cell("second", cytoplasm = mapOf("g" to 1))
            .matter(level = 0)
            .build()
        assertEquals(setOf("first", "second"), f.names)
        val c = controllerFor(f)
        val a = c.fixtureCell(f, "first")
        val b = c.fixtureCell(f, "second")
        assertNotEquals(a, b, "two named cells must resolve to two different entities")
    }

    /** The quanta the panel reports — the energy the cell can actually spend, which is what "in the dark"
     *  has to mean. The raw field value at [CytoTestWorld.Light.None] is not exactly zero (the band's
     *  gaussian has infinite support and the torus is finite), it is ~1e-7 of peak, which floors to no
     *  quanta at all. Asserting on the field would pin the wrong number. */
    private fun quantaOf(light: CytoTestWorld.Light): Int {
        val f = CytoTestWorld.empty().cell("a", light = light).matter(level = 0).build()
        val c = controllerFor(f)
        c.focus(c.fixtureCell(f, "a"))
        c.publish()
        return c.heldCellInfo()!!.light.substringBefore(" q").toInt()
    }

    /** Light is a position, so this is really "did we solve for the right x". */
    @Test fun lightFullIsDaylightAndLightNoneIsDark() {
        assertTrue(quantaOf(CytoTestWorld.Light.Full) > 0, "Light.Full must land under the band")
        assertEquals(0, quantaOf(CytoTestWorld.Light.None), "Light.None must leave the cell with no energy to spend")
    }

    @Test fun lightOfLandsBetweenTheTwo() {
        CytoWorldConfig.applyFrom(CytoScenario.DEFAULT)
        val field = CytoLightField.default()
        val full = field.sampleAt(CytoTestWorld.xForLight(CytoTestWorld.Light.Full), 0f, 0L).raw
        val half = field.sampleAt(CytoTestWorld.xForLight(CytoTestWorld.Light.Of(0.5f)), 0f, 0L).raw
        assertTrue(half in 1 until full, "a half-strength placement must be lit but dimmer than peak (got $half of $full)")
    }

    /** Cells placed by light share an x, so they must not end up stacked on the same spot. */
    @Test fun lightPlacedCellsDoNotCollide() {
        val f = CytoTestWorld.empty()
            .cell("a", light = CytoTestWorld.Light.None)
            .cell("b", light = CytoTestWorld.Light.None)
            .build()
        val (ax, ay) = f.positionOf("a")
        val (bx, by) = f.positionOf("b")
        assertEquals(ax, bx, "same light means the same x")
        assertTrue(kotlin.math.abs(ay - by) > 2f, "but they must be far enough apart in y not to touch")
    }
}
