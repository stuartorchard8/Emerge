package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Wiring

/**
 * Thermal decomposition — carbonates and hydrates give up CO₂/H₂O on heating alone.
 * Calcite → lime + CO₂, serpentine → olivine + water.
 * No reagent, just heat, which makes it the natural tier-1 refinery and a good sink for waste heat from the reactor.
 */
data class ThermalDecomposer(
    override val facing: Direction,
    val input: Resource? = null,
    val inside: Resource? = null,
    val product: Resource? = null,
    val carry: Long = 0L,
    /**
     * Minimum number of machine ticks it takes to convert inProgress resources to product and tailings.
     */
    val ticksPerAction: Int = 128,
    val progress: Int = 0,
    val setTemperature: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
    override val energy: TileEnergy = ambientEnergy(MachineKind.Processor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Processor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withEnergy(energy: TileEnergy): Machine = copy(energy = energy)
}
