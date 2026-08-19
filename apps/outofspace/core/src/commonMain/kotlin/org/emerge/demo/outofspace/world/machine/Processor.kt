package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * Mineral processor: concentrate out facing side, tailings out clockwise-side.
 * Chain: purity climbs (41%→75%→100%), wasteful (tailings = lost material).
 */
data class Processor(
    override val center: TileIndex,
    override val facing: Direction,
    val carry: Long = 0L,
    /**
     * Minimum number of machine ticks it takes to convert inProgress resources to product and tailings.
     */
    val ticksPerAction: Int = 128,
    val progress: Int = 0,
    val efficiencyPermille: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Processor
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
