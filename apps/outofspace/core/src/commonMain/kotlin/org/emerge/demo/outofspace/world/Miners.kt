package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource

/**
 * An ore source: a stand-in for the mining and import the game does not model yet, and deliberately
 * shallow. When its output is blocked the buffer fills to [BUFFER_CAP] and it stops.
 */
data class Miner(
    override val facing: Direction,
    val composition: Mixture,
    val buffer: Resource = Resource(Form.Ore, Mixture.EMPTY),
    val carry: Long = 0L,
    val gramsPerTick: Long = 250L,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Miner),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Miner
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)

    companion object {
        const val BUFFER_CAP = 5_000L
    }
}
