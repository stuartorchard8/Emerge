package org.emerge.demo.outofspace.world

/**
 * Watches the tile it faces and puts that machine's fullness on the wire beneath it.
 *
 * This is where signals come from, and the only reason wiring has anything to say. One sensor, one
 * reading — a machine that measured several things would need a UI to say which.
 *
 * It used to name a colour. It no longer names anything: what it drives is whatever run of
 * [Conduit.Signal] passes under its own tile, so the answer to "what does this sensor control" is
 * something you can trace with your eye instead of something you have to remember. A sensor with no
 * wire under it is not an error — it is a half-built vessel, and it simply drives nothing.
 */
data class Sensor(
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Sensor),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Sensor
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)
}
