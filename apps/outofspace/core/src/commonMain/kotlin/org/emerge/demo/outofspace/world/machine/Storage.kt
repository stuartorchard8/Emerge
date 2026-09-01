package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * ⚠️ **A storage holds nothing itself.** Its contents live in
 * [org.emerge.demo.outofspace.world.BufferLayer] at the tile
 * [org.emerge.demo.outofspace.world.storageBufferTile] names — its centre, because a warehouse's
 * contents are the volume of the building rather than a queue at either door. The machine is the
 * behaviour and the layer is the matter, so asking what a storage holds needs the world and not just
 * the machine.
 *
 * A buffer you can see the level of. Holds one form, releases it out the front while its RUN
 * activation is positive — so a storage wired to a sensor is a valve, and a storage wired to nothing
 * is a dead end that fills up.
 *
 * **A locked warehouse is the network's only sorter.** [filter] is null until the player locks it,
 * and locking captures whatever the warehouse is holding most of — see
 * [org.emerge.demo.outofspace.world.SpeciesFilter]. From then on the rail network treats the tank
 * as an endless appetite *for that one species*, so material it cannot use is never sent down the
 * branch that leads to it. There is no species list to pick from, and that is the point: a
 * warehouse can only be locked onto something it has actually got, so a filter always names
 * material the player has seen arrive.
 *
 * **Storage is also the vessel's inventory.** The global [org.emerge.demo.outofspace.world.Stockpile] construction draws on is the sum
 * of every storage aboard, computed fresh each tick — there is no separate act of "banking". That
 * keeps material in one place instead of two: what you can build with is exactly what you can walk
 * up to and point at, and blowing a hole beside a full warehouse costs you the contents.
 */
data class Storage(
    override val center: TileIndex,
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
    /** What this warehouse is locked onto, or null while it takes anything. */
    val filter: SpeciesFilter? = null,
    val autoLock: Boolean,
    val autoUnlock: Boolean,
) : DirectedDeckMachine {
    override val kind: DeckMachineKind get() = DeckMachineKind.Storage
    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /** Locked onto [filter], or unlocked when it is null. */
    fun withFilter(filter: SpeciesFilter?): Storage = copy(filter = filter)

    companion object {
        /**
         * How much a warehouse holds: **twenty tonnes**.
         *
         * **Derivation**: five tiles' worth of ore, so a warehouse swallows several boulders and
         * reads as a building rather than a crate. Two hundred belt-loads at the current packet
         * size — but stated as a mass, because what makes this number right is how much material a
         * room holds, not how it got there. See [MACHINE_BUFFER_CAP] for the same distinction and
         * the bug that taught it.
         */
        const val CAP = 20L * Budget.TONNE
    }
}
