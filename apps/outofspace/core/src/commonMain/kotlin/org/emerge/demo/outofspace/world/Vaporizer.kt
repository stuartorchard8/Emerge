package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Resource

/**
 * Mineral vaporizer: emits input ore into the fluid sim as species.
 */
data class Vaporizer(
    override val facing: Direction,
    val input: Resource? = null,
    val carry: Long = 0L,
    val gramsPerTick: Long = 125L * Budget.KILOGRAM,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Processor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Vaporizer
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)
}
