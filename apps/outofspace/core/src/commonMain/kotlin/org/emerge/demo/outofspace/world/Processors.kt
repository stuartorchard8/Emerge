package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Resource

/**
 * A mineral processor: the **concentrate leaves by the facing side** and the **tailings by the side
 * clockwise of it**. Never backwards — the input arrives that way.
 *
 * That direction contract is what makes a straight-through chain work: feed one processor's output
 * into the next and purity climbs (41% iron becomes 75%, then 100%), at the cost of throwing more
 * and more still-useful material into the tailings. Wasteful and effective, which is the trade the
 * machine exists to offer. Each stage needs somewhere for its tailings to go, or it backs up.
 *
 * Deliberately slower than a miner, so a naively built line jams and the player has to think about
 * throughput. That is the other lesson it teaches.
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
