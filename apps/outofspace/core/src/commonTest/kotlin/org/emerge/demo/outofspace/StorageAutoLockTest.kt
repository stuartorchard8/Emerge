package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Acceptance
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.Whitelist
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A store that is **about to change its mind** may not state an appetite as though it were not.
 *
 * Stu's save `dump.txt`, the buffer at (19,15): auto-lock on, locked to "anything pure", two tonnes
 * of room. It therefore told every source on the vessel that it would take every pure lump they had.
 * They let go, the first arrival narrowed the filter to one species, and everything else that had
 * already committed came to rest in a corridor whose only sink now refuses it. The network has no
 * reverse gear, so the branch stayed dead until the tank was unlocked by hand.
 *
 * ⛔ **Every other appetite in the demand pass is a promise about the future and this one was not.**
 * A construction site short by a tonne will still want that tonne when the tonne arrives; a store
 * with room will still have room. A store that has yet to decide *what* it holds is the one sink
 * whose stated kind is provisional — so the largest honest appetite it has is one packet, which is
 * all it takes to decide. See the cap in `OutofspaceSim`'s `accepts` build.
 */
class StorageAutoLockTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap<PlayerId, OutofspaceInput>()) }
        return s
    }

    private fun pure(species: Species, mass: Long) = Mixture.of(species to mass, energy = 0)

    /**
     * Two tanks feeding one, down two corridors that meet at a junction.
     *
     * ⚠️ **Two sources and two species, because one of each cannot see the bug.** A single source of
     * a single species overdraws just as hard, but everything it sends is admissible whatever the
     * tank locks onto, so nothing strands and the run looks healthy. The failure needs a *loser* —
     * material that was welcome when it set off and is refused when it arrives.
     *
     *      (2,2) iron  ──▶ ─────────────┐
     *                                   ├──▶ (11,4) the tank
     *      (2,6) nickel ──▶ ────────────┘
     */
    private fun twoSourcesOneTank(dest: Storage): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(2, 2), Direction.Right)  // out at (3,2)
        deck += fixtureStorage(grid.tile(2, 6), Direction.Right)  // out at (3,6)
        deck += dest                                              // in at (10,4)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 9, 2)
        joinRow(grid, rails, 3, 9, 6)
        joinCol(grid, rails, 9, 2, 6)
        joinRow(grid, rails, 9, 10, 4)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(grid.tile(2, 2), pure(Species.Iron, 10L * Capacity.PACKET_MASS))
            .stocked(grid.tile(2, 6), pure(Species.Nickel, 10L * Capacity.PACKET_MASS))
    }

    /** A tank that will lock onto the first pure thing it is given — Stu's (19,15). */
    private fun undecidedTank(): Storage = Storage(
        cfg.initialGrid.tile(11, 4),
        Direction.Right,
        filter = SpeciesFilter(species = null, pure = true),
        autoLock = true,
        autoUnlock = false,
    )

    /** Everything standing on the track, by species. */
    private fun onTrack(s: VesselState): Mixture {
        var total = Mixture.EMPTY
        for (i in 0 until s.grid.size) s.rail.resourceAt(TileIndex(i))?.let { total += it }
        return total
    }

    private fun destination(s: VesselState): Storage = s.deck[s.grid.tile(11, 4)] as Storage

    /**
     * **The headline.** Nothing sets off toward a tank that is about to stop wanting it.
     *
     * The loser's corridor is the assertion: whichever species the tank does *not* settle on must
     * never have left its own store, because the appetite that would have justified it moving was
     * spent the moment the winner committed.
     */
    @Test
    fun `an undecided tank strands nothing when it makes up its mind`() {
        val s = run(twoSourcesOneTank(undecidedTank()), 30 * RAIL_PERIOD)

        val locked = assertNotNull(destination(s).filter?.species, "the tank never locked onto anything")
        val loser = if (locked == Species.Iron) Species.Nickel else Species.Iron
        assertEquals(
            0L,
            onTrack(s)[loser],
            "$loser set off toward a tank that locked onto $locked, and is now standing in a corridor" +
                " whose only sink refuses it: ${onTrack(s)}",
        )
    }

    /**
     * The loser's tank is not merely un-stranded — **it never let go of a gram**.
     *
     * ⛔ A stronger claim than the one above, and the one that says the fix is in the right place. An
     * empty corridor could also mean the nickel set off and was eaten somewhere; this says the
     * demand pass never justified it moving at all, which is what "nothing travels toward a place
     * that cannot use it" actually asserts.
     */
    @Test
    fun `the species the tank did not choose never leaves its store`() {
        val s = run(twoSourcesOneTank(undecidedTank()), 30 * RAIL_PERIOD)

        val locked = assertNotNull(destination(s).filter?.species, "the tank never locked onto anything")
        val loserTile = if (locked == Species.Iron) s.grid.tile(2, 6) else s.grid.tile(2, 2)
        assertEquals(
            10L * Capacity.PACKET_MASS,
            s.inStore(loserTile, BufferRole.Inside)?.total,
            "the tank settled on $locked and the other store poured anyway",
        )
    }

    /**
     * The control: **a tank that has already decided is not rationed at all.**
     *
     * ⛔ The cap must not survive the lock, or every auto-locking store in the game becomes a
     * one-packet-at-a-time trickle for ever — which is a worse bug than the one it fixes, and the
     * reason [Storage.speciesUndecided] asks about the species rather than about auto-lock.
     */
    @Test
    fun `a tank that has already locked pours as it always did`() {
        val decided = undecidedTank().copy(filter = SpeciesFilter(Species.Iron, pure = true))
        val s = run(twoSourcesOneTank(decided), 30 * RAIL_PERIOD)

        val held = s.inStore(s.grid.tile(11, 4), BufferRole.Inside)?.total ?: 0L
        assertTrue(
            held > 3L * Capacity.PACKET_MASS,
            "a decided tank took only $held g in thirty rail periods — the cap outlived the lock",
        )
        assertEquals(0L, onTrack(s)[Species.Nickel], "nickel set off toward a tank locked to iron")
    }

    // ── The shortlist: which of them it is allowed to settle on ──────────────

    /** [undecidedTank] with a shortlist on it. */
    private fun shortlisted(vararg species: Species): Storage =
        undecidedTank().withCandidates(species.toSet())

    /**
     * ⭐ **The point of the shortlist.** A tank that would have taken the first pure thing to reach
     * it takes the first pure thing *on the list*, and the network never sets off with anything else.
     *
     * ⛔ **Both halves again.** An empty corridor is the claim: nickel is not merely refused at the
     * door, it is never justified in moving, because the appetite the tank published never mentioned
     * it. A shortlist that only worked at the door would leave exactly the jam the one-packet cap
     * was written to prevent.
     */
    @Test
    fun `a shortlisted tank locks onto a species from its list`() {
        val s = run(twoSourcesOneTank(shortlisted(Species.Iron)), 30 * RAIL_PERIOD)

        assertEquals(SpeciesFilter(Species.Iron, pure = true), destination(s).filter)
        assertEquals(0L, onTrack(s)[Species.Nickel], "nickel set off toward a tank that had barred it")
        assertEquals(
            10L * Capacity.PACKET_MASS,
            s.inStore(s.grid.tile(2, 6), BufferRole.Inside)?.total,
            "the barred store poured anyway",
        )
    }

    /**
     * ⛔ **An EMPTY shortlist bars EVERYTHING**, exactly as a
     * [org.emerge.demo.outofspace.world.machine.FeedBook]'s empty book does. One rule for every list
     * in the game; what differs between them is only the **default** a fresh machine starts with.
     *
     * ⛔ **It read the other way for one commit**, and BAR ALL is what proved it wrong: the state
     * that button has to write was the state that meant the opposite, so the control had nowhere to
     * put its answer. A missing control and a wrong encoding turned out to be the same bug.
     */
    @Test
    fun `an empty shortlist bars everything`() {
        val s = run(twoSourcesOneTank(shortlisted()), 30 * RAIL_PERIOD)

        assertEquals(null, destination(s).filter?.species, "a tank barred from everything locked anyway")
        assertEquals(0L, onTrack(s).total, "and nothing should have set off toward it")
    }

    /**
     * The control, and the reason the *default* is what it is: a store nobody has touched behaves
     * exactly as it did before shortlists existed.
     *
     * ⚠️ [undecidedTank] states no shortlist at all, so this is asserting on [Storage.ANY_SPECIES] —
     * the value a fresh store and an older file's store both get.
     */
    @Test
    fun `a store with no shortlist stated is unrestricted`() {
        assertTrue(undecidedTank().allowsAnySpecies, "a fresh store started out restricted")
        val s = run(twoSourcesOneTank(undecidedTank()), 30 * RAIL_PERIOD)
        assertNotNull(destination(s).filter?.species, "an unrestricted tank never locked")
    }

    /**
     * The shortlist is asked at the **door** as well as on the route.
     *
     * ⚠️ A tank shortlisted to something that never arrives stays undecided rather than settling on
     * the first thing to reach it by some other road — see `lockedOnto`, where the same predicate is
     * read a second time.
     */
    @Test
    fun `a tank shortlisted to something absent never settles`() {
        val s = run(twoSourcesOneTank(shortlisted(Species.Titanium)), 30 * RAIL_PERIOD)

        assertEquals(null, destination(s).filter?.species, "the tank settled on something it had barred")
        assertEquals(0L, onTrack(s).total, "and nothing should have set off toward it")
    }

    /**
     * ⛔ **One appetite for the whole shortlist, not one per species.** Five acceptances would be
     * five independent promises — [Whitelist.promised] is keyed per acceptance — so one packet of
     * each shortlisted species could set off in a single step and all but one would strand. That is
     * the one-packet cap walked back in through a door the fix left open, and it is why
     * [Acceptance.shortlisted] is a single acceptance carrying both halves.
     */
    @Test
    fun `a shortlist of both species still commits to only one`() {
        val s = run(twoSourcesOneTank(shortlisted(Species.Iron, Species.Nickel)), 30 * RAIL_PERIOD)

        val locked = assertNotNull(destination(s).filter?.species, "the tank never locked onto anything")
        val loser = if (locked == Species.Iron) Species.Nickel else Species.Iron
        assertEquals(
            0L,
            onTrack(s)[loser],
            "both shortlisted species set off and $loser is stranded: ${onTrack(s)}",
        )
    }

    private fun reloadedTank(store: Storage): Storage {
        val back = Save.read(Save.write(twoSourcesOneTank(store)))
        return back.deck[back.grid.tile(11, 4)] as Storage
    }

    /**
     * All three states of the shortlist survive a round trip — and the **unrestricted** one leaves
     * no trace in the file.
     *
     * ⛔ **Which is what makes "absent means every species" a reading rather than a migration.** A
     * file written before shortlists existed is byte-identical to one written today by a store
     * nobody has touched, so the two cannot be told apart and do not need to be. The state that
     * needs a spelling is the *empty* one, and it gets `NONE`.
     */
    @Test
    fun `all three shortlist states round-trip through a save`() {
        assertEquals(
            setOf(Species.Iron, Species.Nickel),
            reloadedTank(shortlisted(Species.Iron, Species.Nickel)).candidates,
        )
        assertTrue(reloadedTank(shortlisted()).candidates.isEmpty(), "BAR ALL did not survive the file")
        assertTrue(reloadedTank(undecidedTank()).allowsAnySpecies, "an unrestricted tank came back restricted")

        val text = Save.write(twoSourcesOneTank(undecidedTank()))
        assertTrue(
            text.lineSequence().none { it.startsWith("deckmachine") && it.contains("shortlist=") },
            "an unrestricted shortlist wrote a field; an older file is supposed to be indistinguishable",
        )
    }

    /**
     * ⛔ **The bulk pair means every species in the GAME, not every row on the sheet.**
     *
     * The sheet lists what is aboard plus what is named, which is a *view*. A player pressing ALLOW
     * ALL means the rule, and a bulk press that quietly stopped at the view would be a list that
     * changed its mind the first time something new was mined. Asserted on a species the fixture has
     * none of and has never shown a row for.
     *
     * ⚠️ Driven through the controller and a real tick, because the edit is the thing under test —
     * `setAllShortlisted` builds a set and `Edit.ShortlistStorage` carries it whole.
     */
    @Test
    fun `the bulk pair reaches species that are not aboard`() {
        val controller = OutofspaceController(cfg, twoSourcesOneTank(undecidedTank()))
        fun tank() = controller.state.deck[cfg.initialGrid.tile(11, 4)] as Storage

        controller.setAllShortlisted(tank(), false)
        controller.stepOnce()
        assertTrue(tank().candidates.isEmpty(), "BAR ALL left something on the list")

        controller.setAllShortlisted(tank(), true)
        controller.stepOnce()
        assertTrue(tank().allowsAnySpecies, "ALLOW ALL did not reach every species")
        // Nothing of this is aboard the fixture, so it has never had a row on any sheet.
        assertTrue(tank().mayLockOnto(Species.Zircon), "ALLOW ALL stopped at what was aboard")
    }

    // ── Which of two sources gets there first ─────────────────────────────────

    /**
     * Two sources feeding one undecided tank down corridors that merge, with their **output ports
     * chosen so that tile order and hash-bucket order disagree**.
     *
     * `(10,1)` is index 26 and `(2,3)` is index 50 on this 16-wide grid. [TileIndex] is a value class
     * over `Int`, so its hash *is* the index and a `HashMap` iterates `index & (capacity - 1)`: at
     * the capacity a map this size takes, 50 lands in bucket 2 and 26 in bucket 10, so the **higher**
     * tile went first. Sorted, the lower one does.
     *
     * ⚠️ The sources are locked to what they hold so that neither is hungry for the other's cargo —
     * an unlocked store is a sink with room, and two of them facing each other is a second race the
     * fixture is not asking about.
     */
    private fun raceOfTwoPorts(lowPort: Species, highPort: Species): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(9, 1), Direction.Right, SpeciesFilter(lowPort, pure = true))
        deck += fixtureStorage(grid.tile(1, 3), Direction.Right, SpeciesFilter(highPort, pure = true))
        deck += Storage(
            grid.tile(13, 5), Direction.Right, DeckMachineKind.Buffer,
            filter = SpeciesFilter(species = null, pure = true),
            autoLock = true, autoUnlock = false,
        )
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 10, 12, 1)   // out of the low port, right
        joinRow(grid, rails, 2, 12, 3)    // out of the high port, right
        joinCol(grid, rails, 12, 1, 5)    // the two corridors merge on this column
        joinRow(grid, rails, 12, 13, 5)   // and into the tank's door
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(grid.tile(9, 1), pure(lowPort, 10L * Capacity.PACKET_MASS))
            .stocked(grid.tile(1, 3), pure(highPort, 10L * Capacity.PACKET_MASS))
    }

    private fun raceWinner(s: VesselState): Species? =
        (s.deck[s.grid.tile(13, 5)] as Storage).filter?.species

    /**
     * ⭐ **The source on the lower tile goes first, and that is now a rule rather than a coincidence.**
     *
     * ⛔ **It was a hash bucket.** `portsByTile` returned a bare `HashMap`, so the tie-break between
     * two sources was `index & (capacity - 1)` — deterministic, but unstable under a resize and
     * matching no rule anybody could be told. Stu's `dump.txt`: the buffer at (19,15) has a chromite
     * silo one tile below it and a nickel silo eleven tiles away, and nickel won every time, because
     * its port at 518 wrapped to bucket 6 while chromite's sat at 500. The chromite silo's only route
     * out leads to that buffer, so once it locked, chromite could never ship again — it still held
     * all 100 kg of it three thousand ticks later.
     *
     * ⚠️ **Run twice with the cargo swapped**, because "the lower tile wins" and "iron wins" look the
     * same in one direction. The winner has to follow the *port*, not the species.
     */
    @Test
    fun `the source on the lower tile wins the race to an undecided tank`() {
        assertEquals(
            Species.Iron,
            raceWinner(run(raceOfTwoPorts(lowPort = Species.Iron, highPort = Species.Nickel), 30 * RAIL_PERIOD)),
        )
        assertEquals(
            Species.Nickel,
            raceWinner(run(raceOfTwoPorts(lowPort = Species.Nickel, highPort = Species.Iron), 30 * RAIL_PERIOD)),
            "the winner followed the species rather than the tile",
        )
    }

    /**
     * A near source with a **high** tile index and a far one with a **low** index — so that "nearest"
     * and "lowest tile" cannot both be right.
     *
     *      (2,1) far  ──▼── (2,2)=34 ──▶ ─────────────▶ (8,4) the tank
     *                                                     ▲
     *      (8,6) near ─────────────────────────── (8,5)=88
     *
     * The near source is **one hop** from the tank's door and the far one is eight; their ports are
     * 88 and 34, so ascending tile order picks the far one and proximity picks the near one.
     */
    private fun nearAndFar(near: Species, far: Species): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(8, 6), Direction.Up, SpeciesFilter(near, pure = true))
        deck += fixtureStorage(grid.tile(2, 1), Direction.Down, SpeciesFilter(far, pure = true))
        deck += Storage(
            grid.tile(8, 4), Direction.Right, DeckMachineKind.Buffer,
            filter = SpeciesFilter(species = null, pure = true),
            autoLock = true, autoUnlock = false,
        )
        val rails = arrayOfNulls<Segment>(grid.size)
        joinCol(grid, rails, 8, 4, 5)   // the near source, one hop below the door
        joinCol(grid, rails, 2, 2, 4)   // the far source, down its own column
        joinRow(grid, rails, 2, 8, 4)   // and along row 4 to the door
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            .stocked(grid.tile(8, 6), pure(near, 10L * Capacity.PACKET_MASS))
            .stocked(grid.tile(2, 1), pure(far, 10L * Capacity.PACKET_MASS))
    }

    /**
     * ⭐ **The nearest source decides an undecided tank**, and it is the only sink in the game
     * proximity decides anything for.
     *
     * ⛔ **Because the losers are not merely late.** Every other sink is served by whoever can reach
     * it, which is right — a network with two feeds should use both. A sink that takes one packet and
     * then changes what it wants is the exception: whatever the losers sent is refused for ever the
     * instant the winner lands, and on Stu's ship that left a chromite silo one tile below the buffer
     * at (19,15) holding all 100 kg of its contents for three thousand ticks, because a magnetite
     * silo six tiles away had got there first and the buffer's lock shut the door behind it.
     *
     * ⚠️ **Run twice with the cargo swapped**, so "the near one wins" cannot pass as "iron wins".
     */
    @Test
    fun `the nearest source decides an undecided tank`() {
        assertEquals(
            Species.Iron,
            (run(nearAndFar(near = Species.Iron, far = Species.Nickel), 30 * RAIL_PERIOD)
                .let { it.deck[it.grid.tile(8, 4)] as Storage }).filter?.species,
        )
        assertEquals(
            Species.Nickel,
            (run(nearAndFar(near = Species.Nickel, far = Species.Iron), 30 * RAIL_PERIOD)
                .let { it.deck[it.grid.tile(8, 4)] as Storage }).filter?.species,
            "the winner followed the species rather than the distance",
        )
    }

    /**
     * A blend lock does **not** grow an opinion about purity when a pure lump turns up.
     *
     * ⛔ `SpeciesFilter.pure` says so in as many words — *"an opinion about purity here would refuse
     * the rest of the seam"* — and the auto-lock did it anyway, because it re-fired on any store
     * whose filter was not already `pure = true`. That is the stranding bug one rung down: the tank
     * advertises two tonnes of appetite for iron-bearing rock, takes one clean lump, tightens itself,
     * and the seam behind it is standing in a corridor that now refuses it.
     */
    @Test
    fun `a tank locked to a species at any purity keeps its opinion when pure metal arrives`() {
        val seam = undecidedTank().copy(filter = SpeciesFilter(Species.Iron, pure = null))
        val s = run(twoSourcesOneTank(seam), 30 * RAIL_PERIOD)

        assertEquals(
            SpeciesFilter(Species.Iron, pure = null),
            destination(s).filter,
            "pure iron arriving tightened a species-only lock, and the seam behind it is now refused",
        )
    }
}
