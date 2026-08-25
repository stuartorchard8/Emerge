package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * A gantry three tiles long that carries a belt over whatever is in the way.
 *
 * ⚠️ **A bridge holds nothing itself.** Its load lives in
 * [org.emerge.demo.outofspace.world.BufferLayer] at the three tiles it stands on, addressed by
 * [org.emerge.demo.outofspace.world.BufferRole]: `Input` at the near end, `Inside` over the tile
 * being hopped, `Product` at the far end. Those are exactly where the three slots were always
 * *drawn* — a bridge's ports have always sat at ±1 from its centre — so this changed where the
 * packets are stored and not where they appear.
 *
 * ### Why it takes up floor space now
 *
 * A bridge used to occupy no layer at all, which made it the one building that could be stacked
 * without limit: bridges over bridges over machines, and a tile whose contents no overlay could
 * honestly draw. It is a [DeckMachineKind] like everything else now, so **a bridge cannot be built
 * over another deck machine or another bridge**, and crossing a run costs three tiles of deck. That
 * is a real constraint and it is meant to be: the game is about being out of space.
 *
 * ⚠️ Its footprint is a **line along [facing]** — see
 * `FootprintShape.Span`. Turning a bridge therefore moves it onto two different tiles, so unlike
 * every other machine a rotation can be refused.
 */
data class Bridge(
    override val center: TileIndex,
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Bridge
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    companion object {
        /** Tiles a bridge spans, and so how many packets it can have aboard at once. */
        const val SLOTS = 3
    }
}
