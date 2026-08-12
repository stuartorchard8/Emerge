package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.logistics.Capacity

/**
 * Mineral processor: concentrate out facing side, tailings out clockwise-side.
 * Chain: purity climbs (41%→75%→100%), wasteful (tailings = lost material). Slower than extractor (jam = throughput lesson).
 */
data class Processor(
    override val facing: Direction,
    val input: Resource? = null,
    val product: Resource? = null,
    val tailings: Resource? = null,
    val carry: Long = 0L,
    /**
     * Grams per tick at full activation: **one belt-load**.
     *
     * ⚠️ **A producer must never out-produce the belt it feeds.** A belt tile holds one packet and a
     * machine hands over at most one packet per tick, so belt throughput *is* [Capacity.PACKET_GRAMS]
     * per tick — which makes that the ceiling for every machine in the game. Deriving the rate from
     * the packet states the invariant instead of leaving it to two literals that happen to agree:
     * when the belt-load went from a tonne to 100 kg, the old hard-coded rates were suddenly 2.5x
     * what the belts could carry and every refinery line silently became throughput-broken.
     *
     * Tunable per machine later — a slow smelter and a fast one are a reasonable thing to want — but
     * the cap is structural and anything above it is a machine that starves its own output.
     */
    val gramsPerTick: Long = Capacity.PACKET_GRAMS,
    val efficiencyPermille: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: TileJoules = ambientJoules(MachineKind.Processor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Processor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: TileJoules): Machine = copy(joules = joules)
}
