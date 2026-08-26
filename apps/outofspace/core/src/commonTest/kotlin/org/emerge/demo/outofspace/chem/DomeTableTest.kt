package org.emerge.demo.outofspace.chem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the per-kelvin dome table is **memoisation and not approximation**.
 *
 * The simulation reads the saturation curves through `...At(kelvin, species)`, which answers from a
 * table built at startup; every test in `SaturationTest` and `PhaseRealityTest` reads them through
 * the interpolating form, which answers from `DOMES`. Those are two paths to the same number, and
 * the moment they stop agreeing the tests are checking a curve the game does not use.
 *
 * So this checks every entry of every table against the curve it was built from — the whole domain,
 * not a sample of it, because the tables are only a few thousand entries wide and a disagreement
 * would be at one awkward kelvin rather than spread across the range. It also checks the seam: one
 * kelvin past a species' critical point both forms must answer null, since that is where the table
 * ends and the reason it can.
 */
class DomeTableTest {

    @Test
    fun `the table answers exactly what the curve does, at every kelvin of every species`() {
        var entries = 0
        var tabulated = 0
        for (species in Species.ALL) {
            val critical = CRITICAL[species] ?: continue
            if (saturationPressure(reducedTemperature(0, species)!!, species) == null) continue
            tabulated++
            for (kelvin in 0 until critical.kelvin) {
                val tr = reducedTemperature(kelvin, species)!!
                assertEquals(
                    saturatedVapourDensity(tr, species), saturatedVapourDensityAt(kelvin, species),
                    "$species vapour density at $kelvin K",
                )
                assertEquals(
                    condensedDensity(tr, species), condensedDensityAt(kelvin, species),
                    "$species condensed density at $kelvin K",
                )
                assertEquals(
                    saturationPressure(tr, species), saturationPressureAt(kelvin, species),
                    "$species saturation pressure at $kelvin K",
                )
                entries++
            }
        }
        assertTrue(tabulated >= 20, "only $tabulated species tabulated; the fluids have gone missing")
        // Not an assertion about performance, an assertion about *size*: three longs per entry is
        // what this costs to hold, and a table that quietly grew by an order of magnitude would be a
        // megabyte-scale decision made by accident.
        assertTrue(entries in 1_000..200_000, "$entries entries per table is not the size expected")
    }

    /** Where the table ends is where the curve stops answering — the seam both forms have to share. */
    @Test
    fun `above the critical point both forms answer nothing`() {
        for (species in Species.ALL) {
            val critical = CRITICAL[species] ?: continue
            for (kelvin in intArrayOf(critical.kelvin, critical.kelvin + 1, critical.kelvin * 2)) {
                val tr = reducedTemperature(kelvin, species)!!
                assertEquals(null, saturatedVapourDensity(tr, species), "$species vapour at $kelvin K")
                assertEquals(null, saturatedVapourDensityAt(kelvin, species), "$species vapour (tabled) at $kelvin K")
                assertEquals(null, condensedDensityAt(kelvin, species), "$species condensed (tabled) at $kelvin K")
                assertEquals(null, saturationPressureAt(kelvin, species), "$species saturation (tabled) at $kelvin K")
            }
        }
    }

    /**
     * A negative kelvin is not a temperature, and the table cannot hold one — so it has to fall
     * through to the curve rather than index below zero. Nothing in the game asks, and that is
     * exactly why it is worth pinning: an `IndexOutOfBounds` here would arrive as a crash in the
     * middle of a tick, a long way from anything that looks like a temperature.
     */
    @Test
    fun `a negative kelvin falls through to the curve instead of indexing below zero`() {
        for (species in Species.ALL) {
            if (CRITICAL[species] == null) continue
            val tr = reducedTemperature(-5, species)!!
            assertEquals(saturatedVapourDensity(tr, species), saturatedVapourDensityAt(-5, species), "$species")
            assertEquals(condensedDensity(tr, species), condensedDensityAt(-5, species), "$species")
            assertEquals(saturationPressure(tr, species), saturationPressureAt(-5, species), "$species")
        }
    }
}
