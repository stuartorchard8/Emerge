package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * An opening between the pipe under it and the room it stands in: gas crosses freely at this tile.
 *
 * ### Why this can be a building now, when it could not before
 *
 * ⚠️ A valve was tried as a deck machine once and could not work: placing a building **displaces the
 * air out of its own tile**, so the valve opened onto a cell that putting it there had just emptied,
 * and nothing ever crossed. That was true of a *solid* machine, and the placement rule was right.
 *
 * [DeckMachineKind.isPermeable] is the thing that did not exist then. A permeable machine is a plate
 * and not a block — `StructureMap.derive` skips it, so its tile stays exactly the room it was and no
 * air is moved to make space. A valve displaces nothing, which is the whole of what it needs.
 *
 * ### What being a segment gave for free, and now does not
 *
 * A valve could not exist without a pipe to be part of. As a building it can be put down on bare
 * floor, where it opens onto nothing and simply does nothing until a run is threaded under it —
 * the same as a sensor with no wire, and stated as a rule rather than guaranteed by the shape.
 */
data class Valve(
    override val center: TileIndex,
    override val wiring: Wiring = Wiring.RUNNING,
) : DeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Valve
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)
}
