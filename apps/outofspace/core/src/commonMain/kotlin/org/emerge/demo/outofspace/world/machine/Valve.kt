package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * An opening between the pipe under it and the room it stands in where gas crosses freely.
 */
data class Valve(
    override val center: TileIndex,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Valve
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
