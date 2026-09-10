package org.emerge.demo.outofspace.world.machine

import org.emerge.demo.outofspace.chem.Species
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
    /**
     * The species auto-lock is allowed to settle on — a **shortlist**.
     *
     * ⛔ **EMPTY MEANS NOTHING, exactly as a [FeedBook]'s book does**, and what differs between them
     * is the **default**: a fresh ejector or kiln is *empty* and therefore shut, and a fresh store is
     * *full* and therefore takes anything. That is the whole of the difference, and it is a fact
     * about how each machine starts rather than about what a list means.
     *
     * ⛔ **It was "empty means any" for one commit, and that was wrong twice over.** It made this the
     * only list in the game whose empty state was permissive — the two sheets looked identical and
     * meant opposite things — and it left BAR ALL with nowhere to put its answer, since the state it
     * would have written was the one that means the opposite. A control the player cannot reach is a
     * good sign the encoding is wrong. Stu, 2026-09-10.
     *
     * ⚠️ **So a store built or loaded without one gets [ANY_SPECIES]**, and the save writes nothing
     * for it — which is what keeps an older file's tanks behaving exactly as they did. See
     * `Save.writeDeckMachine`, where the three states are encoded.
     *
     * ⛔ **It does not widen what the store HOLDS.** A store's contents are one `Mixture` on one
     * tile and `takePacket` draws them proportionally, so a tank admitting two species does not hold
     * two things — it holds an alloy of them, and can never ship either one pure again. That is the
     * reason the shortlist constrains the *lock* rather than replacing it: the tank still settles on
     * exactly one species, and all this decides is which ones it is allowed to settle on. Stu,
     * 2026-09-10.
     *
     * ⚠️ **Inert unless [autoLock] is on**, since there is nothing to constrain otherwise — see
     * [speciesUndecided], which is the only reader. The panel says so out loud rather than leaving a
     * dial that quietly does nothing.
     *
     * ⚠️ **The purity dial still applies on top.** A shortlist of five under `pure = true` means
     * five species and no blends at all, including no blend of two shortlisted species — see
     * [org.emerge.demo.outofspace.world.Acceptance.shortlisted], where the two halves are composed.
     */
    val candidates: Set<Species> = ANY_SPECIES,
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

    /**
     * Whether this store has yet to decide **what** it holds — auto-lock is on and no species has
     * been captured.
     *
     * ⛔ **This is one statement read twice, and it has to be.** The delivery path locks the filter
     * and the demand pass sizes the appetite, and the two are answering the same question: is the
     * species this store will settle on still unknown? Written down once here after they were
     * written down separately and disagreed — see the appetite cap in `OutofspaceSim`'s `accepts`
     * build, and the auto-lock in `deliver`.
     *
     * ⚠️ **Species only, never purity.** A store already locked to iron is *decided*, whatever it
     * still thinks about purity: everything the network could send it is iron, so no arrival can
     * narrow it in a way that strands what is already rolling. A store at
     * `SpeciesFilter(null, pure = true)` — "anything pure", which is what a player who wants one
     * clean tank of *something* sets — is the undecided case and the one this exists for.
     */
    val speciesUndecided: Boolean get() = autoLock && filter?.species == null

    override fun rotated(): DeckMachine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): DeckMachine = copy(wiring = wiring)
    override fun movedTo(center: TileIndex): DeckMachine = copy(center = center)

    /** Locked onto [filter], or unlocked when it is null. */
    fun withFilter(filter: SpeciesFilter?): Storage = copy(filter = filter)

    /**
     * Whether auto-lock may settle this store on [species] — the shortlist, read the way the door
     * reads it. Plain membership: an empty shortlist says no to everything.
     */
    fun mayLockOnto(species: Species): Boolean = species in candidates

    /**
     * Whether the shortlist bars nothing — the ordinary store, and the state a fresh one is in.
     *
     * ⚠️ **A size test, so it costs nothing on the hot path.** The demand pass reads it every rail
     * step to decide whether an undecided tank needs the per-species walk at its door at all, and an
     * unrestricted store is the overwhelmingly common case.
     */
    val allowsAnySpecies: Boolean get() = candidates.size >= Species.COUNT

    /** This store with a new shortlist. */
    fun withCandidates(candidates: Set<Species>): Storage = copy(candidates = candidates)

    companion object {
        /**
         * Every species there is — what [candidates] holds when the player has barred nothing.
         *
         * ⚠️ **Shared rather than built per store**, because it is the default of a `data class`
         * whose every `copy` would otherwise allocate a hundred-odd element set to say "no opinion".
         */
        val ANY_SPECIES: Set<Species> = Species.ALL.toSet()

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
