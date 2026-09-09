package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.FluidPhase
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.condensedDensityAt
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VolumeField
import org.emerge.demo.outofspace.world.bathPhaseOf
import org.emerge.demo.outofspace.world.bathVolume
import org.emerge.demo.outofspace.world.fillPermille
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **A bath states a volume, and the volume is what gives its contents a phase.**
 *
 * `PLAN_electrochemistry.md` §5.5, increment 1b.3. A `BufferLayer` store is a `Mixture` — mass and
 * energy — so until a compartment says what volume it is, nothing in it has a density and therefore
 * nothing in it has a phase. That is what §5.6's sampled outputs will need in order to ship the gas
 * standing above a bath rather than a product the machine chose.
 */
class BathTest {

    /**
     * ⛔ **Derived from the casing, not stated.** A second opinion about how much of a tile a machine
     * fills would be free to drift from the one its own mass is computed from.
     */
    @Test
    fun `a bath is the void inside one tile of casing`() {
        val kind = DeckMachineKind.Electrolyzer
        assertEquals(
            VolumeField.FULL * (1000 - kind.fillPermille) / 1000,
            kind.bathVolume,
            "a bath is not what is left of the tile once the casing has it",
        )
        assertTrue(kind.bathVolume in 1 until VolumeField.FULL, "a bath is some of a tile, not all or none")
    }

    /** ⚠️ A thinner machine has a bigger chamber, which falls out rather than being arranged. */
    @Test
    fun `a thinner walled machine has more room inside it`() {
        val thin = DeckMachineKind.Hull          // 60 permille of plate
        val thick = DeckMachineKind.Furnace      // a lining
        assertTrue(
            thin.bathVolume > thick.bathVolume,
            "a hull's shell (${thin.fillPermille}) left less room than a furnace's (${thick.fillPermille})",
        )
    }

    /**
     * ⭐ **The job the volume exists for.** The same species is a liquid, a boiling mixture or a
     * vapour according to *how much of it is in how much room* — and a `Mixture` is mass and energy,
     * so it can answer none of that on its own. Three points, because two would not show that the
     * middle one exists.
     *
     * ⚠️ **The middle answer is `Separating`, and that is the physical one rather than a fudge.** A
     * bath a bit over half full of water at room temperature is inside the saturation dome: part of
     * it is liquid and the rest is the vapour standing above it, which is exactly the state §5.6's
     * sampled outputs exist to ship from. I expected `Liquid` here and the code was right.
     */
    @Test
    fun `the volume is what tells a liquid from a boiling bath from a vapour`() {
        val kind = DeckMachineKind.Electrolyzer
        fun water(kg: Long) = bathPhaseOf(kind, Species.Water, kg * 1000L * Budget.GRAM, Temperature.AMBIENT_KELVIN)

        assertEquals(FluidPhase.Liquid, water(650L), "a bath filled to its liquid density is not a liquid")
        assertEquals(FluidPhase.Separating, water(400L), "a half-full bath is not boiling")
        assertEquals(FluidPhase.Vapour, bathPhaseOf(kind, Species.Water, Budget.GRAM, Temperature.AMBIENT_KELVIN),
            "a trace of water in a whole bath reads as a liquid")
    }

    /**
     * ⛔ **And the reason the volume does NOT also answer the appetite** — measured, and it is why
     * `Electrolyzer.BUFFER_CAP` survives increment 1b.
     *
     * §5.5 argued that one number should answer both the phase and the room an input port has, and
     * called that pairing *"the test that it is the right number"*. It is not available: hydrogen and
     * oxygen are **above their critical temperature at room temperature**, so neither has a condensed
     * density and neither can be liquid at any pressure. "The bath is full of it" is undefined for
     * exactly the two species a cell exists to make, and a supercritical gas in a fixed volume has no
     * capacity short of close packing.
     *
     * ⚠️ Pinned as a test rather than left as a note, because the day one of these gains a
     * condensed density at ambient is the day that argument changes, and it should be loud.
     */
    @Test
    fun `a cell's two gases have no liquid density to be full of`() {
        assertNotNull(
            condensedDensityAt(Temperature.AMBIENT_KELVIN, Species.Water),
            "water stopped being condensable at room temperature",
        )
        assertNull(
            condensedDensityAt(Temperature.AMBIENT_KELVIN, Species.Hydrogen),
            "hydrogen gained a liquid density at room temperature, so a volume CAN cap a gas bath now",
        )
        assertNull(
            condensedDensityAt(Temperature.AMBIENT_KELVIN, Species.Oxygen),
            "oxygen gained a liquid density at room temperature, so a volume CAN cap a gas bath now",
        )
    }

    /** A species with no critical point on file has no phase, and says so rather than guessing. */
    @Test
    fun `a solid with no critical point has no phase in a bath`() {
        assertNull(
            bathPhaseOf(DeckMachineKind.Electrolyzer, Species.Iron, Capacity.PACKET_MASS, Temperature.AMBIENT_KELVIN),
            "iron was given a phase off a critical point it does not have",
        )
    }
}
