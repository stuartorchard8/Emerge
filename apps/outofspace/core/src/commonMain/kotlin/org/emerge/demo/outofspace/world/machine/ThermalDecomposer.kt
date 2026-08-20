package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * A well-insulated box in which the player chooses the conditions.
 *
 * Thermal decomposition — carbonates and hydrates giving up CO₂/H₂O on heating alone, calcite →
 * lime + CO₂, serpentine → olivine + water — is not something this machine *does*. It is something
 * that happens to matter at a temperature, anywhere in the vessel, and this is simply the one place
 * where a temperature can be asked for rather than merely suffered. See
 * `PLAN_ambient_chemistry.md`, decision 3.
 *
 * ⚠️ **It has no recipe, no progress and no rate.** Those went with the chemistry when the chemistry
 * left. What is left is a thermostat: it pulls a charge in, runs an element until the charge reaches
 * [setTemperature], and hands on whatever the charge has *become* by then. If nothing decomposes at
 * the temperature the player set, nothing decomposes — which is the machine finally telling the
 * truth about what it is for.
 *
 * Its [org.emerge.demo.outofspace.world.Material.Firebrick] casing stops being decoration at the
 * same moment. The element is modelled as being *in* the chamber, so the charge is what gets hot and
 * the casing is what the heat then bleeds into — slowly, at the buffer's own contact conductance —
 * and from there into the room. A decomposer working steadily is a heat source you have to plan
 * around, and that is also the argument for putting it somewhere the ventilation has been thought
 * about: its gaseous products leave by the room, not by a belt.
 */
data class ThermalDecomposer(
    override val center: TileIndex,
    override val facing: Direction,
    val setTemperature: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.ThermalDecomposer
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
