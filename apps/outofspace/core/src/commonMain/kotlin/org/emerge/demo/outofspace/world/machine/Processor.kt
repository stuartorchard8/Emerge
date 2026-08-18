package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Wiring

/**
 * Mineral processor: concentrate out facing side, tailings out clockwise-side.
 * Chain: purity climbs (41%→75%→100%), wasteful (tailings = lost material).
 */
data class Processor(
    override val facing: Direction,
    val carry: Long = 0L,
    /**
     * Minimum number of machine ticks it takes to convert inProgress resources to product and tailings.
     */
    val ticksPerAction: Int = 128,
    val progress: Int = 0,
    val efficiencyPermille: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
    override val energy: TileEnergy = ambientEnergy(MachineKind.Processor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Processor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withEnergy(energy: TileEnergy): Machine = copy(energy = energy)
}
