package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * Thermal decomposition — carbonates and hydrates give up CO₂/H₂O on heating alone.
 * Calcite → lime + CO₂, serpentine → olivine + water.
 * No reagent, just heat, which makes it the natural tier-1 refinery and a good sink for waste heat from the reactor.
 */
data class ThermalDecomposer(
    override val center: TileIndex,
    override val facing: Direction,
    val carry: Long = 0L,
    /**
     * Minimum number of machine ticks it takes to convert inProgress resources to product and tailings.
     */
    val ticksPerAction: Int = 128,
    val progress: Int = 0,
    val setTemperature: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.ThermalDecomposer
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
