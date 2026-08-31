package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * Watches the tile it faces and puts that machine's fullness on the wire beneath it.
 *
 * This is where signals come from, and the only reason wiring has anything to say. One sensor, one
 * reading — a machine that measured several things would need a UI to say which.
 *
 * It used to name a colour. It no longer names anything: what it drives is whatever run of
 * [org.emerge.demo.outofspace.world.Conduit.Signal] passes under its own tile, so the answer to "what does this sensor control" is
 * something you can trace with your eye instead of something you have to remember. A sensor with no
 * wire under it is not an error — it is a half-built vessel, and it simply drives nothing.
 */
data class Sensor(
    override val center: TileIndex,
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
    /** Signal propagation characteristics */
    val threshold: Int,
    val delay: Int,
    val delayedFor: Int = 0,
    val release: Int,
    val releasedFor: Int = 0,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Sensor
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
