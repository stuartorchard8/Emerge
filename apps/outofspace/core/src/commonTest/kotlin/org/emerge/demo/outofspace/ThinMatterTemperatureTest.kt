package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.energyAtKelvin
import org.emerge.demo.outofspace.world.gasKelvin
import org.emerge.demo.outofspace.world.kelvinOf
import org.emerge.demo.outofspace.world.thermalMassAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thin matter has a temperature, and it is its own.
 *
 * A capacity is `Σ mass × specificHeat / CAPACITY_DIVISOR`, and that divisor is 10⁷ — so anything
 * under about 9.6 mg of nitrogen used to have a capacity of *zero*, take the `capacity <= 0` branch
 * of every temperature in the game, and report a fabricated 293 K however much heat it held. See
 * `heatCapacityAt`.
 */
class ThinMatterTemperatureTest {

    /**
     * ⛔ **What makes the ambient fallback unreachable for matter that exists.**
     *
     * `kelvinOf` answers ambient when the thermal mass is zero, and that is only an honest answer
     * for a genuinely empty tile. It stays honest exactly as long as every species costs something
     * to warm: one species with a `specificHeat` of zero and a tile full of it would have no thermal
     * mass, take the fallback, and be back to reporting a fabricated 293 K for real matter — the bug
     * this file exists to prevent, reintroduced through the species table rather than through the
     * arithmetic.
     */
    @Test
    fun `every species costs something to warm`() {
        for (s in Species.ALL) {
            assertTrue(s.specificHeat > 0, "${s.name} has specificHeat ${s.specificHeat}")
        }
    }

    /** Under the old pre-divided capacity every one of these read exactly ambient. */
    @Test
    fun `a cell far too thin to have a capacity still reads its own temperature`() {
        for (kelvin in listOf(4, 16, 77, 200, 400, 1200)) {
            val mass = MassArray(1)
            mass[TileIndex(0), Fluid.of(Species.Nitrogen)!!] = 7_109L // 7.1 mg — capacity floors to 0
            val thermal = thermalMassAt(mass, TileIndex(0))
            assertEquals(0L, thermal / org.emerge.demo.outofspace.num.Budget.CAPACITY_DIVISOR,
                "the fixture stopped being thin enough to be the case under test")

            val energy = EnergyArray(1)
            energy[TileIndex(0)] = energyAtKelvin(thermal, kelvin)
            val read = gasKelvin(energy, mass)[0]

            assertTrue(read in (kelvin - 2)..(kelvin + 2), "$kelvin K read back as $read K")
            if (kelvin != Temperature.AMBIENT_KELVIN) {
                assertTrue(read != Temperature.AMBIENT_KELVIN, "$kelvin K still reads as ambient")
            }
        }
    }

    /**
     * The property the two halves owe each other. `energyAtKelvin` is the only way to state "this
     * matter is at this temperature" and `kelvinOf` the only way to read it back; if they ever stop
     * being inverses, something is seeding matter at a temperature it will not report.
     *
     * ### What is still lossy, and why it is the right way round
     *
     * ⚠️ **The energy unit is a floor and always will be.** One microgram of nitrogen at 3 K holds
     * about 3 nJ, and a unit is 10 mJ — there is no integer that says so, so it reads 0 K. The
     * quantum is `CAPACITY_DIVISOR / thermalMass` kelvin, which is a **hundredth of a kelvin** for a
     * milligram and vanishes entirely for anything a player can see.
     *
     * ⛔ The distinction from what this replaced is not the size of the error but its **direction**.
     * A pre-divided capacity failed *upward*, to a fabricated 293 K, and stayed there however cold
     * the matter really was. This fails *downward*, toward the cold and empty answer, and only ever
     * by what the energy unit cannot express. Never overstating is the half that is asserted here.
     */
    @Test
    fun `stating a temperature and reading it back agree to within the energy unit`() {
        for (units in listOf(1L, 7L, 100L, 9_615L, 1_000_000L, 1_000_000_000L)) {
            val mass = MassArray(1)
            mass[TileIndex(0), Fluid.of(Species.Nitrogen)!!] = units
            val thermal = thermalMassAt(mass, TileIndex(0))
            // One energy unit is worth this many kelvin to this much matter.
            val quantum = org.emerge.demo.outofspace.num.Budget.CAPACITY_DIVISOR / thermal + 1L
            for (kelvin in listOf(3, 16, 293, 3000)) {
                val read = kelvinOf(energyAtKelvin(thermal, kelvin), thermal)
                assertTrue(read <= kelvin, "$units units at $kelvin K read back HOTTER, as $read K")
                assertTrue(read >= kelvin - quantum,
                    "$units units at $kelvin K read back as $read K, more than one $quantum K quantum low")
            }
        }
    }
}
