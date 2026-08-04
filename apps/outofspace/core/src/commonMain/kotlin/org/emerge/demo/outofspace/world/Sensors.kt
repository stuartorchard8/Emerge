package org.emerge.demo.outofspace.world

/**
 * Watches the tile it faces and emits that machine's fullness on [channel].
 *
 * This is where signals come from, and the only reason wiring has anything to say. One sensor, one
 * channel, one reading — a machine that measured several things would need a UI to say which.
 */
data class Sensor(
    override val facing: Direction,
    val channel: Channel = Channel.Red,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Sensor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Sensor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)
}
