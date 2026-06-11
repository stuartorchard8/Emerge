package org.emerge.demo.cyto.sim

import org.emerge.sim.core.physics.primitives.Frac
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CytoEnergyGridTest {

    /** Draw then deposit the same amount back: total reservoir energy is exactly preserved (the
     *  conservation invariant the closed system rests on). */
    @Test
    fun drawAndDepositConserveEnergy() {
        val g = CytoEnergyGrid.seeded()
        val before = g.total().raw
        val idx = g.indexOf(CytoLightField.SOURCES.first().first, CytoLightField.SOURCES.first().second)
        val drawn = g.draw(idx, Frac(1, 50))
        assertTrue(drawn.raw > 0L, "should draw something from a seeded source cell")
        assertEquals(before - drawn.raw, g.total().raw, "draw debits exactly what it returns")
        g.deposit(idx, drawn)
        assertEquals(before, g.total().raw, "deposit of the drawn amount restores the total")
    }

    /** A draw is capped at what's available and never drives a cell negative. */
    @Test
    fun drawIsCappedAtAvailable() {
        val g = CytoEnergyGrid.seeded()
        val darkIdx = g.indexOf(0f, 0f)   // torus centre — between all sources, ~empty
        val avail = g.at(darkIdx).raw
        val taken = g.draw(darkIdx, Frac(1, 1))   // demand far more than is there
        assertTrue(taken.raw <= avail, "draw cannot exceed the available energy")
        assertTrue(g.at(darkIdx).raw >= 0L, "a cell never goes negative")
    }

    /** The reservoir holds real energy concentrated at the sources, little at the dark midpoints. */
    @Test
    fun seededReservoirIsBrightAtSourcesDarkBetween() {
        val g = CytoEnergyGrid.seeded()
        val (sx, sy) = CytoLightField.SOURCES.first()
        assertTrue(g.at(g.indexOf(sx, sy)).raw > g.at(g.indexOf(0f, 0f)).raw, "a source cell holds more than the centre")
        assertTrue(g.total().raw > 0L, "the reservoir has energy")
    }
}
