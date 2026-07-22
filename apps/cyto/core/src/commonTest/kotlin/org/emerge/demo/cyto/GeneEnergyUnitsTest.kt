package org.emerge.demo.cyto

import org.emerge.demo.cyto.sim.EnergySource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The panel's model of what a gene can raise in one tick ([CytoController.energyUnits]) — the number the
 * DIVIDE affordance compares against `biomass/4`. It has to agree with `CytoBiologyCore`'s own arithmetic
 * *on the boundary*, not near it: division is all-or-nothing, so being one unit out flips a gene between
 * "will divide" and "will never divide" in the UI.
 */
class GeneEnergyUnitsTest {
    private val c = CytoController()
    private val cyto = mapOf("r" to 1000, "g" to 300)

    @Test
    fun aSynthesisSourceIsBoundedByTheScarcerReactant() {
        assertEquals(300, c.energyUnits(EnergySource.FormBond("r", "g"), cyto, quanta = 0, share = 1))
        assertEquals(300, c.energyUnits(EnergySource.FormBond("g", "r"), cyto, quanta = 0, share = 1))
    }

    @Test
    fun aReactantTheCellDoesNotHoldRaisesNothing() {
        assertEquals(0, c.energyUnits(EnergySource.FormBond("r", "b"), cyto, quanta = 0, share = 1))
    }

    @Test
    fun aSelfJoinCostsTwoCopiesPerBond() {
        assertEquals(500, c.energyUnits(EnergySource.FormBond("r", "r"), cyto, quanta = 0, share = 1))
    }

    @Test
    fun lightGivesTheCellsQuanta() {
        assertEquals(80, c.energyUnits(EnergySource.Light, cyto, quanta = 80, share = 1))
    }

    /** The 1/n split: contending DIVIDE genes each get a share of the means, not the whole pool. Two divides
     *  that would each fire alone can both be unfunded together — the sim's `dn` behaviour. */
    @Test
    fun contendingGenesSplitTheMeans() {
        assertEquals(150, c.energyUnits(EnergySource.FormBond("r", "g"), cyto, quanta = 0, share = 2))
        assertEquals(100, c.energyUnits(EnergySource.FormBond("r", "g"), cyto, quanta = 0, share = 3))
        assertEquals(40, c.energyUnits(EnergySource.Light, cyto, quanta = 80, share = 2))
    }

    /** Splitting and halving compound, and both truncate — a self-join under contention is quartered, and a
     *  pool that can't cover one whole bond per gene raises nothing at all rather than a fraction. */
    @Test
    fun aContendedSelfJoinIsQuartered() {
        assertEquals(250, c.energyUnits(EnergySource.FormBond("r", "r"), cyto, quanta = 0, share = 2))
        // 3 copies, 2 genes: each gets 1 copy, and one copy does not make a bond that needs two.
        assertEquals(0, c.energyUnits(EnergySource.FormBond("x", "x"), mapOf("x" to 3), quanta = 0, share = 2))
    }

    /** A genome with no gated-open DIVIDE gene still has to divide by something. */
    @Test
    fun aShareOfZeroIsTreatedAsOne() {
        assertEquals(300, c.energyUnits(EnergySource.FormBond("r", "g"), cyto, quanta = 0, share = 0))
    }
}
