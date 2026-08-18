package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/** A smelter: refined metal out the front, slag out the side. */
data class Smelter(
    override val center: TileIndex,
    override val facing: Direction,
    val carry: Long = 0L,
    /**
     * Grams per tick at full activation: **one belt-load**.
     *
     * ⚠️ **A producer must never out-produce the belt it feeds.** A belt tile holds one packet and a
     * machine hands over at most one packet per tick, so belt throughput *is* [Capacity.PACKET_MASS]
     * per tick — which makes that the ceiling for every machine in the game. Deriving the rate from
     * the packet states the invariant instead of leaving it to two literals that happen to agree:
     * when the belt-load went from a tonne to 100 kg, the old hard-coded rates were suddenly 2.5x
     * what the belts could carry and every refinery line silently became throughput-broken.
     *
     * Tunable per machine later — a slow smelter and a fast one are a reasonable thing to want — but
     * the cap is structural and anything above it is a machine that starves its own output.
     */
    val massPerTick: Long = Capacity.PACKET_MASS,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Smelter
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
