package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * Mineral processor: concentrate out facing side, tailings out clockwise-side.
 *
 * Chain: purity climbs, wastefully (tailings = lost material). Fed the standard ore body at the
 * default efficiency the ladder runs **41% → 65% → 86% → 94% → 97% → 100%** — five machines, and
 * half the mass lost at every one of them, so a pure packet costs about 32 packets of ore.
 */
data class Processor(
    override val center: TileIndex,
    override val facing: Direction,
    val carry: Long = 0L,
    /**
     * Minimum number of machine ticks it takes to convert inProgress resources to product and tailings.
     */
    val ticksPerAction: Int = 16,
    val progress: Int = 0,
    /**
     * Machine quality, capped by the ore's own purity — see [org.emerge.demo.outofspace.chem.process].
     *
     * 600 is the mid-tier separator: good enough to finish the job, slow enough that finishing it
     * takes a chain. It is also the point where the curve is at its most even — at 650 and above,
     * stage 2 is still limited by the ore rather than by the machine, so raising the rating past
     * that buys a duplicated 87% step instead of a better one.
     */
    val efficiencyPermille: Int = 600,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Processor
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {
        const val CHARGE_MASS = Capacity.PACKET_MASS*2
    }
}
