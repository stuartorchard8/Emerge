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
import org.emerge.demo.outofspace.world.SaveError
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Stockpile
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Ejector
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ejector's whitelist: **nothing goes overboard that the player has not named.**
 *
 * The machine used to be a `Vent` and it took everything, for ever, which made it the network's one
 * bottomless sink and the reason half the transport fixtures in this suite exist. It now states an
 * appetite like any other fussy machine — one `Acceptance.onlyOf` at its own tile — and the whole of
 * this file is the consequences of that sentence being true.
 *
 * ### The two halves, and why they are both here
 *
 * **The door** (`sinkAdmits`) and **the route** (`Whitelist`) are one statement read twice, and a
 * change that satisfies only one of them is the failure the demand design exists to prevent: a belt
 * filling solid against a mouth that will never take what is on it. So every case below asserts on
 * *both* — what the machine threw away, and whether anything was left standing on the track. The
 * ledger tells you the door worked; the empty track tells you the network never sent it.
 *
 * ### Pure and mixed are two categories
 *
 * A species on the list means that species **pure**; every blend is `Ejector.ore`, one switch. That
 * is the docking counter's partition and the only line the network can draw — see
 * `SpeciesFilter.MIXED`. Several cases below exist purely to hold that line: ticking IRON must never
 * be a way to throw away rock that happens to contain iron.
 *
 * ⚠️ **`ventedMass` keeps its name**, on the machine and in the file. Mass leaving the vessel is
 * vented whatever pushed it out — a shrinking grid vents, an airlock vents — and the rename was of
 * the machine, not of the verb.
 */
class EjectorTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(16, 8))

    private fun run(state: VesselState, ticks: Int, input: OutofspaceInput = OutofspaceInput.EMPTY): VesselState {
        var s = state
        val inputs = mapOf(PlayerId(0) to input)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, if (it == 0) inputs else emptyMap()) }
        return s
    }

    private val tank = 2 to 3
    private val ejector = 9 to 3
    private val load = 6L * Capacity.PACKET_MASS

    /**
     * A tank at (2,3) pouring right along row 3 into an ejector at (9,3) carrying [whitelist].
     *
     * The single fixture every case here uses, because the only variable worth changing is the list:
     * one source, one sink, one road between them and nowhere else for anything to go.
     */
    private fun line(whitelist: Set<Species>, cargo: Mixture, ore: Boolean = false): VesselState {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(tank.first, tank.second), Direction.Right)
        deck += Ejector(grid.tile(ejector.first, ejector.second), whitelist = whitelist, ore = ore)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, ejector.first, 3)
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

    /** Everything still standing on the track — a jammed run's fingerprint. */
    private fun onTrack(s: VesselState): Long {
        var total = 0L
        for (i in 0 until s.grid.size) total += s.rail.massAt(TileIndex(i))
        return total
    }

    private fun stillInTank(s: VesselState): Long =
        s.inStore(s.grid.tile(tank.first, tank.second), BufferRole.Inside)?.total ?: 0L

    private fun ejectorAt(s: VesselState): Ejector =
        assertNotNull(s.deck[s.grid.tile(ejector.first, ejector.second)] as? Ejector, "the ejector went missing")

    // ── The door, and the road to it ──────────────────────────────────────────

    /**
     * ⭐ **The whole increment in one assertion.** A freshly placed ejector names nothing, so it is a
     * dead end — and a dead end is not merely a machine that refuses at the door, it is a machine the
     * network never routes to at all. The tank keeps every gram and the corridor stays empty.
     *
     * ⛔ **`onTrack` is the half that matters.** Were only the door fussy, the tank would still pour
     * six packets up the run and they would come to rest against a mouth that will never take them —
     * a jam the player cannot see the cause of. That is the exact failure `Acceptance` was written to
     * prevent, and it is what an ejector reproduced for as long as `takesAnything` forgot to read
     * `Acceptance.only`.
     */
    @Test
    fun `an ejector that names nothing is a dead end, not a jam`() {
        val s = run(line(emptySet(), pure(Species.Iron)), 40 * RAIL_PERIOD)

        assertEquals(0L, ejectorAt(s).ventedMass, "an unlisted ejector threw something away")
        assertEquals(0L, s.ventedMass, "and the vessel's ledger agrees nothing left")
        assertEquals(0L, onTrack(s), "the tank poured at a mouth that will never take it")
        assertEquals(load, stillInTank(s), "and every gram should still be in the tank")
    }

    /** The control: name the species and the same line drains, all the way to the ledger. */
    @Test
    fun `a named species goes overboard, and both ledgers say so`() {
        val s = run(line(setOf(Species.Iron), pure(Species.Iron)), 40 * RAIL_PERIOD)

        assertEquals(0L, stillInTank(s), "the tank should have emptied down the run")
        assertEquals(0L, onTrack(s), "and nothing should be left standing on it")
        assertEquals(load, ejectorAt(s).ventedMass, "the machine's own running total")
        assertEquals(load, s.ventedMass, "and the vessel's, which move together or the world stops adding up")
    }

    /**
     * ⭐ **Stu's correction, and the case that states it.** Ticking IRON is a statement about *pure*
     * iron. Rock that happens to contain iron is ore, it is a different switch, and naming every
     * species in a blend must not add up to consent to throwing the blend away — a machine whose
     * mistakes are unrecoverable is the wrong place to infer permission from arithmetic.
     */
    @Test
    fun `naming every species in a blend does not consent to the blend`() {
        val s = run(
            line(setOf(Species.Iron, Species.Quartz), blend(Species.Iron, Species.Quartz)),
            40 * RAIL_PERIOD,
        )

        assertEquals(0L, s.ventedMass, "a blend went overboard on the strength of its species' ticks")
        assertEquals(0L, onTrack(s), "and it should never have been routed there at all")
        assertEquals(load, stillInTank(s))
    }

    /** The ore switch is what consents to a blend, and it consents to every blend. */
    @Test
    fun `the ore switch takes a blend, whatever it is made of`() {
        val s = run(line(emptySet(), blend(Species.Iron, Species.Quartz), ore = true), 40 * RAIL_PERIOD)

        assertEquals(load, s.ventedMass, "the ore switch should have taken the blend whole")
        assertEquals(0L, stillInTank(s))
    }

    /**
     * ⛔ **The two never compete for the same lump.** Ore on and iron off must leave pure iron alone;
     * the complementary case is the one above. This is `SpeciesFilter.MIXED`'s own promise, asked of
     * the machine that now depends on it.
     */
    @Test
    fun `the ore switch leaves pure metal alone`() {
        val s = run(line(emptySet(), pure(Species.Iron), ore = true), 40 * RAIL_PERIOD)

        assertEquals(0L, s.ventedMass, "a pure tank was drained by the ORE switch")
        assertEquals(0L, onTrack(s), "and it should never have been routed there at all")
        assertEquals(load, stillInTank(s))
    }

    /**
     * The player changing their mind, through the edit the panel actually queues.
     *
     * ⚠️ **Two runs, not one.** The list is read when the tick builds its acceptance map, so a tune
     * and the delivery it enables cannot be the same tick — which is [OutofspaceReducer]'s ordinary
     * one-tick causality and not a quirk of this machine.
     */
    @Test
    fun `tuning the list opens the door, and clearing it shuts again`() {
        var s = line(emptySet(), pure(Species.Iron))
        val at = s.grid.tile(ejector.first, ejector.second)

        s = run(s, 4 * RAIL_PERIOD, OutofspaceInput(listOf(Edit.TuneFeed(at, setOf(Species.Iron)))))
        assertEquals(setOf(Species.Iron), ejectorAt(s).whitelist, "the tune did not reach the machine")
        s = run(s, 20 * RAIL_PERIOD)
        val venting = ejectorAt(s).ventedMass
        assertTrue(venting > 0L, "a listed ejector should be draining the tank")

        s = run(s, 40 * RAIL_PERIOD, OutofspaceInput(listOf(Edit.TuneFeed(at, emptySet()))))
        assertEquals(emptySet(), ejectorAt(s).whitelist)
        assertEquals(0L, onTrack(s), "clearing the list left cargo stranded on the run")
    }

    /** The switch sets a side rather than flipping one: pressing the lit half is a no-op. */
    @Test
    fun `the switch sets a side rather than flipping it`() {
        val bare = Ejector(TileIndex(0))
        val on = bare.switched(Species.Iron, true)
        assertTrue(on.takes(Species.Iron))
        assertEquals(on, on.switched(Species.Iron, true), "pressing EJECT twice reversed itself")
        assertFalse(on.switched(Species.Iron, false).takes(Species.Iron))
        assertEquals(bare, on.switched(Species.Iron, false), "and KEEP should put it back exactly")

        // And the same of the ore row, which is the switch that has no species to key on.
        assertTrue(bare.isShut, "a bare ejector should be shut")
        assertFalse(bare.switchedOre(true).isShut)
        assertEquals(bare, bare.switchedOre(true).switchedOre(false))
        // ⚠️ Shut is BOTH halves: an ejector naming nothing but taking ore is open for business.
        assertFalse(Ejector(TileIndex(0), ore = true).isShut)
    }

    /**
     * ⭐ **The starter ship's own tailings column, end to end** — and the case that pins the ORE
     * switch to a reason rather than to a preference.
     *
     * The starter's ejector is authored `ore = true` and nothing else. What comes down that column is
     * what the concentrator could not separate, which is a blend and has no species to name — so an
     * ejector with a *species* list, however long, would leave the plant jammed. `WiringTest` and
     * `GaugeTest` both went red when this machine was shut, which is how the requirement was found;
     * this says it directly, so a future change to the starter cannot break it silently.
     *
     * ⚠️ **And the pure metal is safe.** The same switch that takes every blend refuses every pure
     * lump, which is the whole point of drawing the line where the counter draws it.
     */
    @Test
    fun `the starter plant's tailings go overboard, and its concentrate does not`() {
        val grid = Grid(40, 28)
        val start = workingVessel(grid)
        val ejector = start.deck.let { deck ->
            (0 until deck.size).map { TileIndex(it) }.first { deck[it] is Ejector }
        }
        assertTrue((start.deck[ejector] as Ejector).ore, "the starter's ejector should be set to take ore")
        assertEquals(emptySet(), (start.deck[ejector] as Ejector).whitelist, "and to name no species")

        // ⚠️ **Stops as soon as it has its answer** — `GaugeTest`'s idiom, and for its reason: a
        // tick of a 40x28 vessel is not cheap, and the bound is generous rather than measured. The
        // concentrator takes two whole packets before it ships anything, so the first tailings reach
        // the foot of the column somewhere past t=1000.
        val cfg = OutofspaceConfig(initialGrid = grid)
        var s = start
        var ticks = 0
        while (ticks < 1600 && s.ventedMass == 0L) {
            s = OutofspaceReducer.reduce(cfg, s, emptyMap())
            ticks++
        }

        assertTrue(s.ventedMass > 0L, "the tailings column never delivered anything in $ticks ticks")
        assertTrue(s.extractedMass > 0L, "the plant dug nothing, so the vent figure proves nothing")
    }

    // ── The file ──────────────────────────────────────────────────────────────

    @Test
    fun `a whitelist survives a save`() {
        val list = setOf(Species.Iron, Species.Quartz, Species.Water)
        val written = Save.write(line(list, pure(Species.Iron), ore = true))
        assertEquals(list, ejectorAt(Save.read(written)).whitelist)
        assertTrue(ejectorAt(Save.read(written)).ore, "the ore switch did not survive the file")
        // ⚠️ **The same world writes the same bytes.** A `Set` has no order of its own, so a list
        // spelled by ordinal is the only way two identical ejectors cannot produce two different
        // files — see `Save.writeDeckMachine`.
        assertEquals(written, Save.write(Save.read(written)))
    }

    /**
     * ⛔ **A file written before the list existed says the list is EMPTY, and it is read that way.**
     *
     * That is a real behaviour change on load and it is the intended one (Stu): the machine destroys
     * the ship's cargo, and after this change nothing is destroyed that the player has not named —
     * including in a world where they never had the chance to name it. A save's disposal lines jam
     * until each ejector is given its list back.
     */
    @Test
    fun `an old VENT record loads as an ejector that throws nothing away`() {
        val written = Save.write(line(setOf(Species.Iron), pure(Species.Iron), ore = true))
        val asVent = written
            .replace(" Ejector ", " Vent ")
            .replace(Regex(" eject=[A-Za-z,]+"), "")
        assertTrue(asVent.contains(" Vent "), "the fixture did not actually produce an old-style record")

        val loaded = Save.read(asVent)
        assertEquals(emptySet(), ejectorAt(loaded).whitelist, "an old vent should load naming nothing")
        assertFalse(ejectorAt(loaded).ore, "nor taking ore")
        assertTrue(ejectorAt(loaded).isShut)
        assertEquals(0L, run(loaded, 40 * RAIL_PERIOD).ventedMass, "and should therefore throw nothing away")
    }

    /**
     * ⚠️ **`ORE` is a token in the species list and must not be read as a species.** It is uppercase
     * where every species name is mixed case, which is what keeps the two from ever colliding.
     */
    @Test
    fun `the ore token is not mistaken for a species`() {
        val written = Save.write(line(emptySet(), pure(Species.Iron), ore = true))
        assertTrue(written.contains("eject=ORE"), "the ore switch is not written under its own name")
        val loaded = ejectorAt(Save.read(written))
        assertEquals(emptySet(), loaded.whitelist)
        assertTrue(loaded.ore)
    }

    /** A name the game does not know stops the load rather than quietly shortening the list. */
    @Test
    fun `an unknown species in a saved list is refused, not dropped`() {
        val written = Save.write(line(setOf(Species.Iron), pure(Species.Iron)))
            .replace("eject=Iron", "eject=Unobtainium")
        assertFailsWith<SaveError> { Save.read(written) }
    }

    // ── The panel's frozen list ───────────────────────────────────────────────
    //
    // ⛔ **Ranked once, and only the player re-ranks it.** The rows are sorted by mass aboard, which
    // changes every tick — a live list would slide a row out from under a finger reaching for it, and
    // the control on the other end of that press throws cargo away for ever. These cases are the
    // whole of that rule, asked of the HUD directly because it can be built without a screen.

    /**
     * A world holding [cargo] on the track, so the panel's columns have something to rank.
     *
     * Loose matter rather than a stocked tank, because the panel's columns are about what the network
     * could deliver to a mouth and a lump on a belt is the plainest example of it.
     */
    private fun aboard(vararg cargo: Pair<Pair<Int, Int>, Mixture>): Stockpile {
        val grid = cfg.initialGrid
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 2, 12, 5)
        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
        for ((at, mix) in cargo) s = s.riding(grid.tile(at.first, at.second), mix)
        return s.stockpile
    }

    private fun lump(species: Species, mass: Long) = Mixture.of(species to mass, energy = 0).atAmbient()

    @Test
    fun `the sheet ranks by mass aboard, heaviest first`() {
        val hud = OutofspaceHud()
        val stock = aboard(
            (2 to 5) to lump(Species.Iron, 1L * Capacity.PACKET_MASS),
            (3 to 5) to lump(Species.Quartz, 3L * Capacity.PACKET_MASS),
            (4 to 5) to lump(Species.Water, 2L * Capacity.PACKET_MASS),
        )
        hud.refreshFeedRows(Ejector(TileIndex(0)), stock)

        assertEquals(listOf(Species.Quartz, Species.Water, Species.Iron), hud.feedRows)
    }

    /**
     * ⛔ **A whitelisted species keeps its row after the last of it has gone.** Otherwise the only
     * control that could take it off the list disappears with it, leaving a standing permission to
     * throw that species overboard which the player can neither see nor reach.
     */
    @Test
    fun `a species that is named but not aboard still gets a row`() {
        val hud = OutofspaceHud()
        val stock = aboard((2 to 5) to lump(Species.Iron, Capacity.PACKET_MASS))
        hud.refreshFeedRows(Ejector(TileIndex(0), whitelist = setOf(Species.Titanium)), stock)

        assertEquals(listOf(Species.Iron, Species.Titanium), hud.feedRows)
    }

    /**
     * ⭐ **The laziness itself.** Something arrives while the sheet is open; the list the player is
     * reading does not move, and the newcomer waits in its own section below.
     */
    @Test
    fun `a species arriving mid-read joins the NEW section and moves nothing`() {
        val hud = OutofspaceHud()
        val machine = Ejector(TileIndex(0))
        hud.refreshFeedRows(machine, aboard((2 to 5) to lump(Species.Iron, Capacity.PACKET_MASS)))
        val opened = hud.feedRows

        // Titanium arrives, and heavier than the iron — so a live sort would put it at the *top*.
        val later = aboard(
            (2 to 5) to lump(Species.Iron, Capacity.PACKET_MASS),
            (3 to 5) to lump(Species.Titanium, 5L * Capacity.PACKET_MASS),
        )
        assertEquals(opened, hud.feedRows, "the frozen list moved on its own")
        assertEquals(listOf(Species.Titanium), hud.newFeedSpecies(machine, later))

        // REFRESH is the gesture that files it, and it files it by mass.
        hud.refreshFeedRows(machine, later)
        assertEquals(listOf(Species.Titanium, Species.Iron), hud.feedRows)
        assertEquals(emptyList(), hud.newFeedSpecies(machine, later))
    }

    /** The other half of REFRESH: a row with nothing behind it any more stops being a row. */
    @Test
    fun `refresh drops a row that is neither aboard nor named`() {
        val hud = OutofspaceHud()
        val machine = Ejector(TileIndex(0))
        hud.refreshFeedRows(
            machine,
            aboard(
                (2 to 5) to lump(Species.Iron, Capacity.PACKET_MASS),
                (3 to 5) to lump(Species.Quartz, Capacity.PACKET_MASS),
            ),
        )
        assertTrue(Species.Quartz in hud.feedRows)

        hud.refreshFeedRows(machine, aboard((2 to 5) to lump(Species.Iron, Capacity.PACKET_MASS)))
        assertEquals(listOf(Species.Iron), hud.feedRows, "the spent row outlived a refresh")
    }

    /**
     * `Stockpile.aboard` counts a species mixed into ore as well as sitting pure, and counts neither
     * of them twice — the column would otherwise rank a hold of blended rock at nothing at all.
     */
    /**
     * ⭐ **Stu's report, as an assertion.** The column beside IRON is what the ship holds *pure*; the
     * iron dissolved in a hold of rock belongs to the ORE row and appears nowhere else. Summing the
     * two — which the first cut of this panel did — reports a species the machine's own switch will
     * never touch, and ranks the list by it.
     */
    @Test
    fun `the columns keep pure and mixed apart`() {
        val stock = aboard(
            (2 to 5) to lump(Species.Iron, 2L * Capacity.PACKET_MASS),
            (3 to 5) to blend(Species.Iron, Species.Quartz),
        )
        assertEquals(2L * Capacity.PACKET_MASS, stock.buildable(Species.Iron), "the ore's iron leaked into the pure column")
        assertEquals(0L, stock.buildable(Species.Quartz), "quartz is only aboard as ore")
        assertEquals(load, stock.blended.total, "and the whole blend is the ORE row's number")
    }

    /** A blend gets no species row at all — it is the ORE row, and only the ORE row. */
    @Test
    fun `a species held only inside ore gets no row of its own`() {
        val hud = OutofspaceHud()
        val machine = Ejector(TileIndex(0))
        hud.refreshFeedRows(machine, aboard((2 to 5) to blend(Species.Iron, Species.Quartz)))

        assertEquals(emptyList(), hud.feedRows, "ore put species rows on a list of pure metals")
        assertTrue(hud.feedRowsHaveOre, "and the ore aboard earned no ORE row")
    }

    /** The ORE row is frozen alongside the species rows, and arrives in NEW like anything else. */
    @Test
    fun `ore arriving mid-read waits in the NEW section`() {
        val hud = OutofspaceHud()
        val machine = Ejector(TileIndex(0))
        hud.refreshFeedRows(machine, aboard((2 to 5) to lump(Species.Iron, Capacity.PACKET_MASS)))
        assertFalse(hud.feedRowsHaveOre, "there was no ore aboard when the list was taken")

        val later = aboard(
            (2 to 5) to lump(Species.Iron, Capacity.PACKET_MASS),
            (3 to 5) to blend(Species.Iron, Species.Quartz),
        )
        assertFalse(hud.feedRowsHaveOre, "the frozen list grew an ORE row on its own")
        assertTrue(hud.newFeedOre(machine, later))

        hud.refreshFeedRows(machine, later)
        assertTrue(hud.feedRowsHaveOre)
        assertFalse(hud.newFeedOre(machine, later))
    }

    /**
     * ⛔ **An ejector taking ore keeps its ORE row after the last of the rock has gone** — the
     * species rows' rule, for its reason: the switch that is still on has to stay reachable.
     */
    @Test
    fun `the ore row survives when the ore does not, if the switch is on`() {
        val hud = OutofspaceHud()
        hud.refreshFeedRows(Ejector(TileIndex(0), ore = true), Stockpile.EMPTY)
        assertTrue(hud.feedRowsHaveOre)

        hud.refreshFeedRows(Ejector(TileIndex(0)), Stockpile.EMPTY)
        assertFalse(hud.feedRowsHaveOre, "an ORE row outlived both the ore and the switch")
    }
}
