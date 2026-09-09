package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.FluidPhase
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.chem.phaseAt
import org.emerge.demo.outofspace.chem.reducedDensity
import org.emerge.demo.outofspace.chem.reducedTemperature
import org.emerge.demo.outofspace.world.machine.DeckMachineKind

/**
 * **How much room a bath has: the void inside one tile of the machine's casing.**
 *
 * `PLAN_electrochemistry.md` §5.5. A [org.emerge.demo.outofspace.world.BufferLayer] store is a
 * `Mixture` — mass and energy, no volume — so its contents have no **density**, and without a
 * density they have no phase. `FluidPhase` comes off reduced density and temperature
 * (`StateEquation.kt`), which is a question about matter *in a volume*.
 *
 * ⛔ **Derived, never stated.** A tile is [VolumeField.FULL] of room and a machine's casing occupies
 * [DeckMachineKind.fillPermille] of it, so what is left is the chamber. A litres-per-bath constant is
 * the version of this that quietly becomes a fudge — and it would be a second opinion about how much
 * of a tile a machine fills, free to drift from the one the machine's own mass is computed from.
 *
 * ⚠️ **A thin-walled machine has a bigger chamber**, which falls out rather than being arranged: an
 * electrolyzer at 150‰ has 870 of 1024, some 705 litres.
 */
val DeckMachineKind.bathVolume: Int
    get() = VolumeField.FULL * (1000 - fillPermille) / 1000

/**
 * What phase [mass] of [species] is in, standing in one of [kind]'s baths at [kelvin] — or null for a
 * species with no critical point on file.
 *
 * ⛔ **This is the only job the volume does, and that is a correction to the plan rather than a
 * shortfall.** §5.5 argued that one number should answer both the phase *and* the room an input port
 * has, and called the pair *"the test that it is the right number"*. Measured, the second half does
 * not exist: [org.emerge.demo.outofspace.chem.condensedDensityAt] returns **null** for hydrogen and
 * for oxygen at room temperature, because both are above their critical point there and cannot be
 * liquid at any pressure. A supercritical gas in a fixed volume has no capacity short of close
 * packing — the pressure simply rises — so "the bath is full of it" is undefined for exactly the two
 * species a cell exists to make.
 *
 * ⚠️ **So the appetite stays on a mass cap** — `Electrolyzer.BUFFER_CAP` — and a gas bath will want a
 * *pressure* ceiling if it ever wants a capacity, which is a mechanism and a balance dial rather than
 * a derivation (Stu, 2026-09-09). Nothing here pretends otherwise.
 */
fun bathPhaseOf(kind: DeckMachineKind, species: Species, mass: Long, kelvin: Int): FluidPhase? {
    val densityR = reducedDensity(mass, species, kind.bathVolume, VolumeField.FULL) ?: return null
    val temperatureR = reducedTemperature(kelvin, species) ?: return null
    return phaseAt(densityR, temperatureR, species)
}
