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
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Furnace
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The decomposer's feed book: **nothing is cooked that the player has not named.**
 *
 * ⛔ **A kiln that takes anything *is sent* everything**, which is the whole reason this exists. The
 * furnace stated `Acceptance.ANYTHING` — honest, while it had nothing to say — so on a demand
 * network every belt on the vessel led to it, and the only way to cook one species was a tank in
 * front of its mouth locked to that species and re-locked by hand each time something new turned up.
 * Ammonia and serpentine both decompose usefully; wanting both meant two tanks, and wanting ten
 * meant ten, on a ship whose entire subject is that there is no room for ten of anything. Stu,
 * 2026-09-10.
 *
 * The list is the ejector's, and it is literally the ejector's — see `FeedBook`, which both machines
 * implement. So the cases here are the ones that are *not* already covered next door: that the
 * decomposer reads the shared statement at all, that an unstated book leaves it idle, and that a
 * file written before it had one loads it shut rather than open.
 *
 * ### Both halves, as always
 *
 * **The door** (`sinkAdmits`) and **the route** (`Whitelist`) are one statement read twice. Every
 * case asserts on the corridor as well as on the chamber: a full chamber says the door worked, and
 * an empty run says the network never sent what the door would have refused.
 */
class FurnaceFeedTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap<PlayerId, OutofspaceInput>()) }
        return s
    }

    private val tank = 2 to 3
    private val kiln = 9 to 3
    private val load = 6L * Capacity.PACKET_MASS

    /** A tank at (2,3) pouring right along row 3 into a decomposer at (9,3) carrying [whitelist]. */
    private fun line(whitelist: Set<Species>, cargo: Mixture, ore: Boolean = false): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(tank.first, tank.second), Direction.Right)
        deck += Furnace(
            grid.tile(kiln.first, kiln.second),
            Direction.Right,
            // ⚠️ The coldest rung, which is off: this file is about what *arrives*, and a charge
            // that decomposes on the way in would change the mass under every assertion.
            setTemperature = 200,
            whitelist = whitelist,
            ore = ore,
        )
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, kiln.first, 3)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(tank.first, tank.second), cargo)
    }

    private fun pure(species: Species, mass: Long = load) = Mixture.of(species to mass, energy = 0).atAmbient()

    private fun blend(a: Species, b: Species) =
        Mixture.of(a to load / 2, b to load / 2, energy = 0).atAmbient()

    private fun onTrack(s: VesselState): Long {
        var total = 0L
        for (i in 0 until s.grid.size) total += s.rail.massAt(TileIndex(i))
        return total
    }

    private fun stillInTank(s: VesselState): Long =
        s.inStore(s.grid.tile(tank.first, tank.second), BufferRole.Inside)?.total ?: 0L

    private fun inChamber(s: VesselState): Long =
        s.inStore(s.grid.tile(kiln.first, kiln.second), BufferRole.Input)?.total ?: 0L

    // ── The door, and the road to it ──────────────────────────────────────────

    /**
     * ⭐ **A decomposer that names nothing is a dead end**, and a dead end is not a machine that
     * refuses at the door — it is one the network never routes to at all.
     *
     * ⛔ **`onTrack` is the half that matters.** Were only the door fussy the tank would pour six
     * packets up the run and they would come to rest against a mouth that never takes them, which is
     * a jam with no visible cause. This is also the behaviour every furnace in an existing save now
     * has on load, and it is correct rather than a migration gap — see the save case below.
     */
    @Test
    fun `a decomposer that has been told nothing is fed nothing`() {
        val s = run(line(emptySet(), pure(Species.Serpentine)), 20 * RAIL_PERIOD)

        assertEquals(0L, inChamber(s), "an unstated kiln was fed")
        assertEquals(0L, onTrack(s), "the tank poured at a kiln that will not take it")
        assertEquals(load, stillInTank(s), "and every gram should still be in the tank")
    }

    /** The control: name the species and the belts feed it, exactly as they always did. */
    @Test
    fun `a named species is routed to the kiln`() {
        val s = run(line(setOf(Species.Serpentine), pure(Species.Serpentine)), 20 * RAIL_PERIOD)

        assertTrue(inChamber(s) > 0L, "serpentine was named and the kiln is still empty")
        assertTrue(stillInTank(s) < load, "and the tank should be emptying")
    }

    /**
     * ⛔ **Ticking a species does not consent to cooking rock that contains it.** A tile holding two
     * things is deliverable only as ore — the counter's partition, and the only line the network can
     * draw. See `SpeciesFilter.MIXED`.
     */
    @Test
    fun `a named species does not admit a blend that contains it`() {
        val s = run(line(setOf(Species.Serpentine), blend(Species.Serpentine, Species.Quartz)), 20 * RAIL_PERIOD)

        assertEquals(0L, inChamber(s), "a blend reached a kiln that named only the pure species")
        assertEquals(0L, onTrack(s), "and it should never have set off")
    }

    /** The other side of that line: the ORE switch is what a raw charge rides in on. */
    @Test
    fun `the ore switch admits a blend`() {
        val s = run(line(emptySet(), blend(Species.Serpentine, Species.Quartz), ore = true), 20 * RAIL_PERIOD)

        assertTrue(inChamber(s) > 0L, "the ore switch was on and no rock arrived")
    }

    /**
     * **Two species, one kiln, no tanks** — the thing the whole increment is for.
     *
     * ⚠️ The tank holds them one at a time rather than blended, because a store's contents are one
     * `Mixture` and a blend of the two is neither of them: that limit is exactly why the *store* did
     * not grow a whitelist and the machine did. See `project_oos_demand_flow`.
     */
    @Test
    fun `a kiln can be told about several species at once`() {
        val both = setOf(Species.Serpentine, Species.Ammonia)
        assertTrue(run(line(both, pure(Species.Serpentine)), 20 * RAIL_PERIOD).let { inChamber(it) } > 0L)
        assertTrue(run(line(both, pure(Species.Ammonia)), 20 * RAIL_PERIOD).let { inChamber(it) } > 0L)

        // And something it was not told about still does not travel.
        val other = run(line(both, pure(Species.Quartz)), 20 * RAIL_PERIOD)
        assertEquals(0L, inChamber(other), "quartz was not on the list and reached the kiln anyway")
        assertEquals(0L, onTrack(other), "and it should never have set off")
    }

    // ── The file ──────────────────────────────────────────────────────────────

    /** The book survives a round trip, both halves of it. */
    @Test
    fun `the book round-trips through a save`() {
        val s = line(setOf(Species.Serpentine, Species.Ammonia), pure(Species.Serpentine), ore = true)
        val loaded = Save.read(Save.write(s))
        val kilnBack = assertNotNull(
            loaded.deck[loaded.grid.tile(kiln.first, kiln.second)] as? Furnace,
            "the kiln went missing",
        )
        assertEquals(setOf(Species.Serpentine, Species.Ammonia), kilnBack.whitelist)
        assertTrue(kilnBack.ore)
    }

    /**
     * ⛔ **A file written before the decomposer had a book loads it SHUT.**
     *
     * An absent field says "nothing on the list" and an empty list refuses everything — the same
     * reading the ejector's own field takes, and the same one that makes a fresh machine honest. So
     * every kiln in an existing save stands idle until its panel is opened, which is accepted rather
     * than papered over: the alternative is a fourth state meaning "never been told", whose first
     * tick would silently switch the machine from everything to one species. Stu's call, 2026-09-10.
     */
    @Test
    fun `a kiln written before the book existed loads shut`() {
        val text = Save.write(line(emptySet(), pure(Species.Serpentine)))
        assertTrue(
            text.lineSequence().none { it.startsWith("deckmachine") && it.contains("Furnace") && it.contains("eject=") },
            "a shut kiln wrote a book field; an older file is supposed to be indistinguishable from it",
        )
        val loaded = Save.read(text)
        val kilnBack = assertNotNull(
            loaded.deck[loaded.grid.tile(kiln.first, kiln.second)] as? Furnace,
            "the kiln went missing",
        )
        assertTrue(kilnBack.isShut, "a kiln with no field in the file loaded open")
    }
}
