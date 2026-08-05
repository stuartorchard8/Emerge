package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Resource

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
    val gramsPerTick: Long = 125L,
    val efficiencyPermille: Int = 900,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Processor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Processor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)
}
