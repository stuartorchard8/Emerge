package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.num.Budget

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Wiring

/**
 * A store: material in at one door, out at the other, and a level you can see.
 *
 * ### One class, three sizes
 *
 * [DeckMachineKind.Warehouse] is 3×3 and holds twenty tonnes, [DeckMachineKind.Silo] is 1×3 and
 * holds five, [DeckMachineKind.Buffer] is 1×2 and holds two. **They are the same machine** — the
 * same lock, the same pooled store, the same two doors, the same claim on the vessel's inventory —
 * so they are one class with a [kind], not three classes with three copies of the behaviour. What
 * differs between them is a footprint and a number, and both of those are facts about the kind
 * (`DeckMachineKind.shape` and [capacity]) rather than about the machine.
 *
 * ⚠️ **`is Storage` is therefore the right test everywhere**, and every site that had one keeps
 * working: a silo sorts, a buffer counts towards the stockpile, and neither needed a line adding to
 * the reducer. A size that needed its own branch in the sim would be a fourth machine wearing this
 * one's name.
 *
 * ⚠️ **A storage holds nothing itself.** Its contents live in
 * [org.emerge.demo.outofspace.world.BufferLayer] at the tile
 * [org.emerge.demo.outofspace.world.storageBufferTile] names — its centre, because a warehouse's
 * contents are the volume of the building rather than a queue at either door. The machine is the
 * behaviour and the layer is the matter, so asking what a storage holds needs the world and not just
 * the machine.
 *
 * ⚠️ For a [DeckMachineKind.Buffer] that centre tile **is** its input door — at two tiles long there
 * is no middle to put a volume in. That is allowed rather than an accident: `bufferTile`'s rule is
 * that a store sits on the port it serves, and a storage's one store serves both doors, so putting
 * it on the mouth material arrives at breaks nothing. See [org.emerge.demo.outofspace.world.localBufferOffset].
 *
 * Holds one form, releases it out the front while its RUN activation is positive — so a storage
 * wired to a sensor is a valve, and a storage wired to nothing is a dead end that fills up.
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
    /**
     * Which of the three sizes this is. Defaults to the [DeckMachineKind.Warehouse], which is what
     * every storage was when there was only one.
     *
     * ⛔ **Not every kind is a storage.** Nothing enforces that here — a `when` guarding a
     * constructor argument buys a runtime failure where a compile-time one is impossible either way
     * — but the only three callers that ever pass it are [org.emerge.demo.outofspace.world.machine.newDeckMachine],
     * the save reader and the tests, and each hands over the kind it was asked for.
     */
    override val kind: DeckMachineKind = DeckMachineKind.Warehouse,
    override val wiring: Wiring = Wiring.RUNNING,
    /** What this warehouse is locked onto, or null while it takes anything. */
    val filter: SpeciesFilter? = null,
    val autoLock: Boolean,
    val autoUnlock: Boolean,
) : DirectedDeckMachine {
    /**
     * How much this one holds — [WAREHOUSE_CAP], [SILO_CAP] or [BUFFER_CAP].
     *
     * ⛔ **Read this, never `Storage.WAREHOUSE_CAP`.** Every caller that wants "how full is it" wants
     * *this* store's tank, and the one that reached for the constant instead was reading a silo as
     * a quarter-full warehouse. The constants are for the derivations that argue the numbers, and
     * for tests that name a size on purpose.
     */
    val capacity: Long get() = capacityOf(kind)

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
        const val WAREHOUSE_CAP = 20L * Budget.TONNE

        /**
         * How much a silo holds: **five tonnes**.
         *
         * ⚠️ **A quarter of a warehouse on a third of the floor**, which is the trade the small
         * sizes exist to offer: a corridor-width store costs you less deck and gives you less than
         * proportionally back, so a player short of space pays for it in tonnage. Nine tiles of
         * warehouse is 2.2 t/tile; three tiles of silo is 1.7.
         */
        const val SILO_CAP = 5L * Budget.TONNE

        /**
         * How much a buffer holds: **two tonnes**.
         *
         * The same trade again and steeper — 1 t/tile — plus the floor that stops it being useless:
         * two tonnes is twenty belt-loads, so a buffer smooths a line that stutters rather than
         * merely widening it by one packet. Below about that it would be a length of track with a
         * dial on it, which the game already has.
         */
        const val BUFFER_CAP = 2L * Budget.TONNE

        /**
         * How much a store of [kind] holds. See [Storage.capacity], which is what callers holding a
         * machine should ask.
         *
         * Not `when`-exhaustive over [DeckMachineKind] and deliberately so: it answers for the three
         * kinds this class wears, and asking it about a furnace is a bug rather than a case.
         */
        fun capacityOf(kind: DeckMachineKind): Long = when (kind) {
            DeckMachineKind.Silo -> SILO_CAP
            DeckMachineKind.Buffer -> BUFFER_CAP
            else -> WAREHOUSE_CAP
        }
    }
}
