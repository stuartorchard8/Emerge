package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.SignalField
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.SaveError
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

/**
 * Saving and loading.
 *
 * The headline test is [`a loaded world runs on identically`]: two copies of the same vessel, one of
 * which went through a save file, must still agree after another few seconds of simulation. That is
 * a much sharper check than comparing the two states directly — a state comparison would pass while
 * quietly ignoring a field the format forgot, whereas anything the save loses shows up as divergence
 * the moment the sim reads it. An extractor's fractional carry, a diverter's cursor and a gauge's last
 * reading are all invisible in a screenshot and all change the future.
 */
class SaveTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(40, 28))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    // ── The contract ──────────────────────────────────────────────────────────

    @Test
    fun `a loaded world runs on identically`() {
        // Long enough for the extractors to accrue a carry, the belts to fill and the first jam to form.
        val played = run(starterVessel(cfg.initialGrid), 400)

        val reloaded = Save.read(Save.write(played))
        assertEquals(Save.write(played), Save.write(reloaded), "the reload differs at tick ${played.tick}")

        // And it keeps agreeing, which is the part a field-by-field comparison cannot promise.
        assertEquals(Save.write(run(played, 200)), Save.write(run(reloaded, 200)))
    }

    /**
     * Air temperature survives the trip, and does so as a *run-on* rather than a field comparison.
     *
     * Worth its own case because a uniformly room-temperature world cannot fail it: the air's energy
     * defaults to exactly ambient, so a save that dropped the temperature line entirely would round
     * trip perfectly and only diverge once something was hot. This warms one room first.
     */
    /**
     * Step 7 of `PLAN_unit_rescale.md`: a save states the unit it was written in, and a file from a
     * coarser unit is multiplied up on read.
     *
     * ### Why it is written this way
     *
     * The obvious test — hand-write a file in an old unit and assert the numbers that come out — has
     * to restate the list of which fields are mass-dimensioned, which is precisely the knowledge
     * being tested. It would agree with the migration by construction and catch nothing.
     *
     * So this changes **one token**: a unit in the header, and nothing else in the file. The same
     * bytes are then read as a coarse-unit world and as a native one, and every quantity in *that
     * dimension* must be exactly `factor` times the second while everything else is untouched. A
     * field the migration forgot fails the first half. A field it scales that it should not — a
     * rock's composition, a tile index, a tick count — fails the second.
     *
     * ⚠️ Done **once per dimension** since v14 split the mass and energy units apart. That is
     * strictly stronger than the single pass it replaces: a field assigned to the *wrong* dimension
     * now fails, where before the two factors were the same number and nothing could tell them
     * apart. It is also what caught the split being incomplete — `radiated` is energy and was still
     * riding the mass factor.
     */
    @Test
    fun `a save from a coarser unit is multiplied up, and only where it should be`() {
        val factor = 1_000L
        val played = run(starterVessel(cfg.initialGrid), 200)
        val native = Save.write(played)
        val here = Save.read(native)

        /** The same file, claiming the unit in header slot [slot] was worth [factor] times as much. */
        fun coarsenedAt(slot: Int): VesselState {
            val header = native.lineSequence().first().split(' ')
            val edited = header.toMutableList()
            edited[slot] = (header[slot].toLong() * factor).toString()
            return Save.read(native.replaceFirst(header.joinToString(" "), edited.joinToString(" ")))
        }

        val heavier = coarsenedAt(2)
        val hotter = coarsenedAt(3)

        // ── Mass and momentum move with the mass unit, and only with it ──
        for ((what, read) in listOf<Pair<String, (VesselState) -> Long>>(
            "extracted" to { it.extractedMass },
            "vented" to { it.ventedMass },
            "baseline air" to { it.baselineAirMass },
        )) {
            assertEquals(read(here) * factor, read(heavier), "$what under a coarser mass unit")
            assertEquals(read(here), read(hotter), "$what is not an energy")
        }

        // ── Energy moves with the energy unit, and only with it ──
        for ((what, read) in listOf<Pair<String, (VesselState) -> Long>>(
            "generated" to { it.generatedEnergy },
            "radiated" to { it.radiatedEnergy },
            "baseline energy" to { it.baselineEnergy },
            "inserted" to { it.insertedEnergy },
            "solid to air" to { it.solidToAirEnergy },
        )) {
            assertEquals(read(here) * factor, read(hotter), "$what under a coarser energy unit")
            assertEquals(read(here), read(heavier), "$what is not a mass")
        }
        // ⚠️ NOT `mass` or `storedEnergy`, and finding that out is what this test was for.
        // A vessel's mass is *derived* from `Material.composition` and the species densities — it is
        // never written to the file at all — so it is already in this build's units and must not
        // move. Solid heat is the subtler case: `k=` is omitted for any machine sitting at ambient,
        // and an omitted field is **reconstructed from a current-unit default** rather than read and
        // scaled. Both are correct, and both would look like a migration bug to anyone who assumed
        // "every mass in the world scales" without asking where each number came from.

        // Guards the guard: if the world were empty these would all be 0 == 0.
        assertTrue(here.extractedMass > 0 || here.baselineAirMass > 0, "the fixture must have mass in it")

        // Air, tile by tile, is the biggest mass field in the game and goes through readMixture.
        var airTiles = 0
        for (tile in here.grid.tiles) {
            val a = here.air.mixtureAt(tile)
            if (a.isEmpty) continue
            airTiles++
            assertEquals(a.total * factor, heavier.air.mixtureAt(tile).total, "air at tile $tile")
        }
        assertTrue(airTiles > 0, "the fixture must have air in it")

        // ── Dimensionless: identical ──
        assertEquals(here.tick, heavier.tick, "a tick count is not a mass")
        assertEquals(here.grid, heavier.grid, "nor is a grid")
        assertEquals(here.positionX, heavier.positionX, "nor is a position — it is in tiles")
        assertEquals(here.positionY, heavier.positionY, "nor is a position — it is in tiles")
        assertEquals(
            here.grid.tiles.count { here.deck[it] != null },
            heavier.grid.tiles.count { heavier.deck[it] != null },
            "machine count",
        )
        // The trap this whole family of bugs lives in: same syntax as air, entirely different meaning.
        for (i in here.bodies.indices) {
            assertEquals(
                here.bodies[i].oreComposition,
                heavier.bodies[i].oreComposition,
                "a rock's composition is proportions, not mass",
            )
        }
    }

    /**
     * A file written in a **finer** unit than this build is rounded, not refused.
     *
     * ⚠️ This asserted the opposite until v14, on the stated grounds that dividing "would round
     * every mass in the world, and silently halving somebody's cargo is worse than declining to open
     * the file". The reasoning was sound and the premise was not: it assumed a unit only ever gets
     * finer, which was true of mass and had no evidence at all for energy. Making the energy unit ten
     * times coarser then made every save the game had ever written unreadable — including one
     * written by the same build a line earlier.
     *
     * What is actually true is narrower and worth stating. Narrowing rounds, and what it discards is
     * **smaller than one integer of the unit doing the reading** — that is, below anything this build
     * could have represented in the first place. That is not silent loss of cargo; it is the same
     * rounding any store into a coarser field takes. So the assertion is on the size of the error,
     * which is the property that actually matters.
     */
    @Test
    fun `a save finer than this build is rounded, and by less than one unit`() {
        val played = run(starterVessel(cfg.initialGrid), 60)
        val native = Save.write(played)
        val header = native.lineSequence().first().split(' ')
        val factor = 10L

        val mass = listOf<Pair<String, (VesselState) -> Long>>(
            "extracted" to { it.extractedMass },
            "vented" to { it.ventedMass },
        )
        val energy = listOf<Pair<String, (VesselState) -> Long>>(
            "radiated" to { it.radiatedEnergy },
            "baseline energy" to { it.baselineEnergy },
        )

        for ((slot, dimension) in listOf(2 to ("mass" to mass), 3 to ("energy" to energy))) {
            val (what, fields) = dimension
            // ⚠️ Only meaningful while this build's own unit has room to be divided. At the finest
            // unit the plan targets — one microgram — there is no whole number below it, so there is
            // no such thing as a finer file and nothing to assert. Skipped rather than faked, since
            // a fabricated "finer" unit of zero tests the argument parser and not the migration.
            if (header[slot].toLong() < factor) continue
            val edited = header.toMutableList()
            edited[slot] = (header[slot].toLong() / factor).toString()
            val finer = Save.read(native.replaceFirst(header.joinToString(" "), edited.joinToString(" ")))
            val here = Save.read(native)

            // Every quantity in that dimension comes back a factor smaller, to within the rounding —
            // never refused, and never off by more than the one unit the division cannot represent.
            for ((name, read) in fields) {
                val expected = read(here) / factor
                val actual = read(finer)
                assertTrue(
                    actual in (expected - 1)..(expected + 1),
                    "$name from a $what unit ${factor}x finer: expected about $expected, got $actual",
                )
            }
        }
    }

    /** A unit that is not a number, or not positive, is still a broken file rather than a rounding. */
    @Test
    fun `an unreadable unit is refused`() {
        val native = Save.write(starterVessel(cfg.initialGrid))
        val header = native.lineSequence().first().split(' ')
        for (bad in listOf("nonsense", "0", "-1000")) {
            val edited = header.toMutableList()
            edited[2] = bad
            assertFailsWith<SaveError>("a mass unit of '$bad' should not load") {
                Save.read(native.replaceFirst(header.joinToString(" "), edited.joinToString(" ")))
            }
        }
    }

    @Test
    fun `air temperature survives a save`() {
        val start = starterVessel(cfg.initialGrid)
        val energy = start.air.copyEnergy()
        val hot = cfg.initialGrid.tile(cfg.initialGrid.width / 2, cfg.initialGrid.height / 2)
        energy[hot] *= 3
        val played = run(start.copy(air = Stuff.from(start.air.copyMass(), energy)), 60)

        val reloaded = Save.read(Save.write(played))
        assertEquals(played.air.kelvinAt(hot), reloaded.air.kelvinAt(hot), "the air reloaded at a different temperature")
        assertEquals(Save.write(run(played, 60)), Save.write(run(reloaded, 60)), "the reload diverged once it ran on")
    }

    @Test
    fun `writing a loaded save gives back the same text`() {
        val text = Save.write(starterVessel(cfg.initialGrid))
        assertEquals(text, Save.write(Save.read(text)))
    }

    @Test
    fun `the ledgers survive, so a leak cannot be laundered by saving`() {
        val played = run(starterVessel(cfg.initialGrid), 300)
        val reloaded = Save.read(Save.write(played))

        assertEquals(played.extractedMass, reloaded.extractedMass)
        assertEquals(played.ventedMass, reloaded.ventedMass)
        assertEquals(played.inTransitMass, reloaded.inTransitMass)
        assertEquals(played.baselineEnergy, reloaded.baselineEnergy)
        assertEquals(played.baselineAirMass, reloaded.baselineAirMass)
        assertEquals(played.baselineCargoMass, reloaded.baselineCargoMass)
        assertEquals(played.builtMass, reloaded.builtMass)
        assertEquals(
            reloaded.extractedMass + reloaded.baselineCargoMass,
            reloaded.inTransitMass + reloaded.ventedMass + reloaded.builtMass,
            "the mass balance did not survive the round trip",
        )
    }

    // ── The things that are easy to lose ──────────────────────────────────────

    /**
     * Links are the whole topology now, so this is the field a save most cannot afford to drop.
     *
     * Two runs crossing without touching is the case that proves it: get the links wrong in either
     * direction and the reload either merges them or severs one, and both look like track.
     */
    @Test
    fun `two runs that cross without touching still do after a reload`() {
        val grid = Grid(8, 8)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        fun lay(x: Int, y: Int) { rails[grid.tile(x, y).index] = rails[grid.tile(x, y).index] ?: Segment(org.emerge.demo.outofspace.world.Conduit.Rail, material = materialBefore(org.emerge.demo.outofspace.world.Conduit.Rail)) }
        fun join(a: TileIndex, b: TileIndex, dir: Direction) {
            rails[a.index] = rails[a.index]!!.joinedTo(dir)
            rails[b.index] = rails[b.index]!!.joinedTo(dir.opposite)
        }
        for (x in 1..5) lay(x, 3)
        for (y in 1..5) lay(3, y)
        for (x in 1..4) join(grid.tile(x, 3), grid.tile(x + 1, 3), Direction.Right)
        for (y in 1..4) join(grid.tile(3, y), grid.tile(3, y + 1), Direction.Down)

        val state = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
        val reloaded = Save.read(Save.write(state))
        for (tile in grid.tiles) {
            assertEquals(rails[tile.index]?.links, reloaded.railAt(tile)?.links, "links differ at tile $tile")
        }
    }

    @Test
    fun `both layers of a crossing survive a save`() {
        val grid = Grid(8, 6)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val wires = arrayOfNulls<Segment>(grid.size)
        val crossing = grid.tile(4, 3)
        // A wire under the rail, not a pipe: track and plumbing compete for the floor and cannot
        // share a tile at all now, so the crossing that has to round-trip is rail over wire.
        for (x in 2..6) rails[grid.tile(x, 3).index] = Segment(Conduit.Rail, links = 1 shl Direction.Right.ordinal, material = materialBefore(Conduit.Rail))
        for (y in 1..5) wires[grid.tile(4, y).index] = Segment(Conduit.Signal, links = 1 shl Direction.Down.ordinal, material = materialBefore(Conduit.Signal))

        val state = VesselState(
            grid,
            deck,
            conduits = Conduits.of(
                grid.size,
                Conduit.Rail to rails.toList(),
                Conduit.Signal to wires.toList(),
            ),
            buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        val back = Save.read(Save.write(state))

        assertEquals(Conduit.Rail, back.conduits.at(Conduit.Rail, crossing)?.conduit, "rail lost at the crossing")
        assertEquals(Conduit.Signal, back.conduits.at(Conduit.Signal, crossing)?.conduit, "wire lost at the crossing")
        assertEquals(state.conduits, back.conduits, "a layer changed somewhere across the round trip")
    }

    /**
     * ⛔ **This used to assert the refusal.** A belt and a pipe on one tile was an unrepresentable
     * world, rejected at load so a hand-edited file could not express it; the rule is gone, because
     * a pipe is built by running temporary track over it. See the note in `Conduits.swept`.
     *
     * Kept, pointing the other way, because the loader is where the old invariant was enforced: this
     * is what proves the crossing survives a file rather than merely being legal in memory.
     */
    @Test
    fun `a save may cross a rail with a pipe`() {
        val text = """
            outofspace 1
            grid 6 4
            conduit 9 Rail links=1
            conduit 9 Pipe links=1
        """.trimIndent()
        val back = Save.read(text)
        val crossing = TileIndex(9)
        assertEquals(Conduit.Rail, back.conduits.at(Conduit.Rail, crossing)?.conduit, "rail lost at the crossing")
        assertEquals(Conduit.Pipe, back.conduits.at(Conduit.Pipe, crossing)?.conduit, "pipe lost at the crossing")
    }

    /**
     * Version 5 wrote `rail <tile> <CONDUIT> …` for every layer, because there was one segment list
     * and the keyword named the list rather than the network. The record always carried its own
     * conduit, so an old file needs no migration — only for the loader to read the record instead of
     * the keyword. This pins that, since the alternative reading would silently file every pipe in a
     * legacy save onto the rail layer.
     */
    @Test
    fun `a version 5 save files each segment by the conduit it names`() {
        val text = """
            outofspace 5
            grid 6 4
            rail 8 Rail links=2
            rail 9 Pipe links=1
        """.trimIndent() + "\n"

        val back = Save.read(text)
        assertEquals(Conduit.Rail, back.conduits.at(Conduit.Rail, TileIndex(8))?.conduit)
        assertEquals(Conduit.Pipe, back.conduits.at(Conduit.Pipe, TileIndex(9))?.conduit)
        assertNull(back.conduits.at(Conduit.Pipe, TileIndex(8)), "a rail was filed on the pipe layer")
        assertNull(back.conduits.at(Conduit.Rail, TileIndex(9)), "a pipe was filed on the rail layer")
    }

    // ── The bell migration ────────────────────────────────────────────────────

    /**
     * A version 17 motor was one tile. It comes back two, with a bell in front of it.
     *
     * The whole of the migration when there is room for it: the chamber keeps everything the file
     * said about it, and the tile it fires through becomes part of the machine.
     */
    @Test
    fun `a version 17 thruster comes back with a bell`() {
        val grid = Grid(8, 6)
        val at = grid.tile(4, 2)
        val state = Save.read(
            """
            outofspace 17 ${Budget.MICROGRAMS_PER_UNIT} ${Budget.NANOJOULES_PER_UNIT}
            grid 8 6
            deckmachine ${at.index} Thruster facing=Right in=Water=5000,energy=0
            """.trimIndent(),
        )
        val motor = state.deck[at] as? Thruster
        assertNotNull(motor, "the motor did not survive the load")
        val bell = grid.tile(5, 2)
        assertEquals(setOf(at, bell), motor.tiles(grid).toSet(), "it did not grow a bell")
        assertEquals(at, state.occupancy[bell], "the bell is not registered to the machine on it")
        assertTrue(state.deck.stuff.massAt(bell) > 0L, "the bell came back made of nothing")
        // Its chamber is untouched: the store is where it always was, holding what it always held.
        assertEquals(
            5000L,
            state.buffers.resourceAt(bufferTile(grid, motor, at, BufferRole.Input)!!)?.total,
            "the propellant did not come back",
        )
    }

    /**
     * A version 17 motor whose bell would land on something already standing is **dropped**.
     *
     * Stu's call, and the two ledgers are re-anchored so that the loss is stated rather than turning
     * up on the next tick as a leak. What is asserted here is the *ledgers*: the machine going is
     * the easy half, and a silent hole in the mass balance is the half that would cost a day.
     *
     * ⚠️ The blocking hull is written **after** the motor on purpose. A migration applied where the
     * record is read would have had to decide before it could know, and would have refused the file
     * — this is the ordering that proves it does not.
     */
    @Test
    fun `a version 17 thruster with nowhere for its bell is dropped, and the ledgers close`() {
        val grid = Grid(8, 6)
        val at = grid.tile(4, 2)
        val blocker = grid.tile(5, 2)
        val motorEnergy = 7_000_000L
        val hullEnergy = 3_000_000L
        val state = Save.read(
            """
            outofspace 17 ${Budget.MICROGRAMS_PER_UNIT} ${Budget.NANOJOULES_PER_UNIT}
            grid 8 6
            deckmachine ${at.index} Thruster facing=Right in=Water=5000,energy=0
            deckmachine ${blocker.index} Hull
            deckheat ${at.index}=$motorEnergy ${blocker.index}=$hullEnergy
            baselinejoules ${motorEnergy + hullEnergy}
            baselinecargo 5000
            """.trimIndent(),
        )
        assertNull(state.deck[at], "a motor was nosed into a hull plate")
        assertNotNull(state.deck[blocker], "the plate that blocked it went too")
        assertTrue(state.occupancy.isFree(at), "the dropped motor is still holding its tile")

        assertEquals(
            0L,
            state.storedEnergy - state.baselineEnergy,
            "the dropped motor's casing heat left the world without the baseline being told",
        )
        assertEquals(
            0L,
            state.inTransitMass + state.ventedMass + state.builtMass - state.extractedMass - state.baselineCargoMass,
            "the dropped motor's propellant left the world without the mass ledger being told",
        )
    }

    /**
     * The bell a migration mints is metal the file never described, so the thermal baseline has to
     * move with it.
     *
     * The mirror of the drop case above, and the easier one to get wrong: nothing is *missing* here,
     * something has *appeared*, and a baseline read straight off the file would report the new
     * casing as heat the world generated on the tick after the load.
     */
    @Test
    fun `the bell a migration mints is added to the thermal baseline`() {
        val grid = Grid(8, 6)
        val at = grid.tile(4, 2)
        val motorEnergy = 7_000_000L
        val state = Save.read(
            """
            outofspace 17 ${Budget.MICROGRAMS_PER_UNIT} ${Budget.NANOJOULES_PER_UNIT}
            grid 8 6
            deckmachine ${at.index} Thruster facing=Right
            deckheat ${at.index}=$motorEnergy
            baselinejoules $motorEnergy
            """.trimIndent(),
        )
        assertNotNull(state.deck[at], "the motor did not survive, so this proved nothing")
        assertEquals(motorEnergy, state.deck.stuff.energyAt(at), "the chamber did not keep its own heat")
        assertTrue(state.deck.stuff.energyAt(grid.tile(5, 2)) > 0L, "the bell came back stone cold")
        assertEquals(
            0L,
            state.storedEnergy - state.baselineEnergy,
            "the minted bell is heat the world cannot account for",
        )
    }

    /**
     * A file this build wrote states both tiles, and must not be migrated a second time.
     *
     * The version gate, asserted the only way that matters: round-trip a motor and check it is still
     * one motor on two tiles rather than two motors, a refusal, or a bell that moved.
     */
    @Test
    fun `a current save round-trips a thruster without migrating it`() {
        val grid = Grid(8, 6)
        val at = grid.tile(4, 2)
        val deck = DeckArray(grid)
        deck += Thruster(at, facing = Direction.Right)
        val state = VesselState(
            grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        val back = Save.read(Save.write(state))
        assertEquals(state.deck[at], back.deck[at], "the motor changed across the file")
        assertEquals(
            (state.deck[at] as Thruster).tiles(grid).toSet(),
            (back.deck[at] as Thruster).tiles(grid).toSet(),
            "its footprint changed across the file",
        )
        assertEquals(state.deck.stuff.massAt(grid.tile(5, 2)), back.deck.stuff.massAt(grid.tile(5, 2)),
            "the bell's metal did not survive the file")
    }

    @Test
    fun `a gauge keeps that it is one, and its last reading`() {
        val grid = Grid(6, 4)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val ore = Mixture.of(Species.Iron to 410L, Species.Quartz to 590L, energy = 0)
        rails[grid.tile(2, 2).index] = Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))
        deck += Gauge(grid.tile(2, 2)).reading(SolidPacket(ore))

        val state = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
        val back = Save.read(Save.write(state)).deck[grid.tile(2, 2)] as? Gauge
        assertNotNull(back)
        assertEquals(Species.Quartz, back.lastDominant)
        assertEquals(590, back.lastPurity)
        assertEquals(1000L, back.lastMass)
    }

    /**
     * Every file up to this one wrote a gauge as `gauge=1` on the tile's `conduit` record, and a
     * valve as `valve=1`. Both are buildings now, so an old file has to put one on the deck.
     */
    @Test
    fun `a legacy gauge or valve flag loads as the building it became`() {
        val state = Save.read(
            """
            outofspace 1
            grid 6 4
            conduit 8 Rail links=1 gauge=1 lastspecies=Quartz lastpurity=590 lastmass=1000
            conduit 9 Pipe links=1 valve=1
            """.trimIndent(),
        )
        val gauge = state.deck[TileIndex(8)] as? Gauge
        assertNotNull(gauge, "the gauge flag did not become a gauge")
        assertEquals(Species.Quartz, gauge.lastDominant, "its reading came back")
        assertEquals(590, gauge.lastPurity)
        assertNotNull(state.railAt(TileIndex(8)), "and the track it stands on is still track")
        assertTrue(state.deck[TileIndex(9)] is Valve, "the valve flag did not become a valve")
    }

    @Test
    fun `a machine keeps its wiring, its buffers and its fractional carry`() {
        val grid = Grid(10, 10)
        val deck = DeckArray(grid)
        val starter = starterVessel(cfg.initialGrid)
        deck += Extractor(
            grid.tile(4, 4),
            Direction.Right,
            // Any non-default wiring will do; the starter vessel's second extractor is the one that has
            // some. Found rather than indexed, because the layout is free to move — it was pinned at
            // (5,19) until the vessel was centred in its grid, and then this broke.
        ).withWiring(
            starter.grid.tiles
                .mapNotNull { starter.deck[it] }
                .first { it is Extractor && it.wiring != Wiring.RUNNING }
                .wiring,
        )
        // At (8,4), not (7,4): the extractor is five across and covers x 2..6, and both of them
        // are deck machines now — a warehouse whose footprint overlapped it used to be legal only
        // because the two lived on different lists.
        deck += fixtureStorage(grid.tile(8, 4), Direction.Left)

        val state = VesselState(grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .stocked(grid.tile(8, 4), Mixture.of(Species.Iron to 900L, energy = 0))
            .stocked(grid.tile(4, 4), Mixture.of(Species.Iron to 123L, energy = 0), BufferRole.Product)
        val back = Save.read(Save.write(state))

        val extractor = assertNotNull(back.deck[grid.tile(4, 4)] as? Extractor)
        // One store now: the jaws are gone, and with them `carry`. See [Extractor].
        assertEquals(123L, back.inStore(grid.tile(4, 4), BufferRole.Product)?.total)
        // `ALWAYS - RED`: two terms, and the negative one is the whole behaviour. The weight it was
        // written with is gone — a term has a sign now — but the sign is what carried the meaning.
        assertEquals(2, extractor.wiring.triggers(org.emerge.demo.outofspace.world.Action.Run).size)
        assertTrue(extractor.wiring.triggers(org.emerge.demo.outofspace.world.Action.Run)[1].negated)

        // The contents survive in the buffer layer; the record still carries `stored`, so the file
        // format is unchanged and an older save loads into the new home unaltered.
        val tank = back.buffers.resourceAt(grid.tile(8, 4))
        assertEquals(900L, tank?.total)
    }

    @Test
    fun `heat and air come back tile by tile, not just in total`() {
        val played = run(starterVessel(cfg.initialGrid), 200)
        val back = Save.read(Save.write(played))
        assertEquals(played.storedEnergy, back.storedEnergy)
        assertEquals(played.atmosphereMass, back.atmosphereMass)
        // Tile by tile: a casing's heat is in the deck layer, addressed by the tile the metal is on.
        for (tile in played.grid.tiles) {
            assertEquals(
                played.deck.energyAt(tile), back.deck.energyAt(tile),
                "deck energy differs at tile $tile",
            )
            assertEquals(
                played.conduits.energyAt(Conduit.Rail, tile),
                back.conduits.energyAt(Conduit.Rail, tile),
                "segment energy differ at tile $tile",
            )
            assertEquals(played.air.mixtureAt(tile), back.air.mixtureAt(tile), "air differs at tile $tile")
        }
    }

    @Test
    fun `a casing that chemistry has altered comes back altered`() {
        val played = run(starterVessel(cfg.initialGrid), 20)
        // Stand in for the reaction that does not exist yet: rust one plate. The point is that the
        // deck's composition is no longer derivable from the machine's kind, which is the whole
        // reason it has to be in the file.
        val altered = played.grid.tiles.first { played.deck[it] != null }
        val before = played.deck.stuff[altered, Species.Iron]
        played.deck.stuff[altered, Species.Iron] = before / 2
        played.deck.stuff[altered, Species.Oxygen] = before / 2

        val back = Save.read(Save.write(played))
        assertEquals(before / 2, back.deck.stuff[altered, Species.Iron], "iron at $altered")
        assertEquals(before / 2, back.deck.stuff[altered, Species.Oxygen], "oxygen at $altered")
        // And nothing else moved: every other deck tile is still what its kind is made of.
        for (tile in played.grid.tiles) {
            assertEquals(
                played.deck.stuff.mixtureAt(tile),
                back.deck.stuff.mixtureAt(tile),
                "deck matter differs at tile $tile",
            )
        }
    }

    @Test
    fun `a half-built length of track comes back half-built`() {
        // The case the ghost depends on: a segment holding less than its bill of materials. Before
        // `trackstuff` the loader re-derived every segment's metal from its kind, so a ghost saved
        // as a finished rail and the player got their iron back for free.
        val played = run(starterVessel(cfg.initialGrid), 20)
        val laid = played.grid.tiles.first { played.conduits.at(Conduit.Rail, it) != null }
        val stuff = played.conduits.tracks[Conduit.Rail]
        val before = stuff[laid, Species.Iron]
        assertTrue(before > 1L, "a length of rail should be made of some iron, got $before")
        stuff[laid, Species.Iron] = before / 2

        val back = Save.read(Save.write(played))
        assertEquals(before / 2, back.conduits.tracks[Conduit.Rail][laid, Species.Iron], "iron at $laid")
        // And nothing else moved: every other length is still what its kind is made of.
        for (conduit in Conduit.entries) {
            for (tile in played.grid.tiles) {
                assertEquals(
                    played.conduits.tracks[conduit].mixtureAt(tile),
                    back.conduits.tracks[conduit].mixtureAt(tile),
                    "$conduit matter differs at tile $tile",
                )
            }
        }
    }

    @Test
    fun `an untouched vessel writes no track matter at all`() {
        val text = Save.write(run(starterVessel(cfg.initialGrid), 20))
        assertTrue(text.lines().none { it.startsWith("trackstuff") }, "unaltered track wrote matter")
    }

    @Test
    fun `an untouched vessel writes no deck matter at all`() {
        // Absence means "made of what its kind is made of". If a plain vessel wrote a line per plate
        // the file would grow by thousands of lines that say nothing the machine record does not.
        val text = Save.write(run(starterVessel(cfg.initialGrid), 20))
        assertTrue(text.lines().none { it.startsWith("deckstuff") }, "an unaltered deck wrote matter")
    }

    // ── Reading things that are not saves ─────────────────────────────────────

    @Test
    fun `a hand-written world is a legitimate save`() {
        // The point of a text format: this is a whole vessel, typed.
        val state = Save.read(
            """
            outofspace 1
            grid 6 4          # a small one
            machine 8 Sensor facing=Up
            rail 9 Rail links=1
            rail 10 Rail links=4 held=S:Ore/Iron=250,Carbon=250
            """.trimIndent(),
        )
        assertEquals(Grid(6, 4), state.grid)
        // Still written as a `machine` record by every file that predates the move — the reader
        // routes a deck kind onward by name, so a hand-typed world keeps working — but it lands on
        // the deck, which is where a sensor lives now.
        assertEquals(Direction.Up, (state.deck[TileIndex(8)] as? Sensor)?.facing)
        // The save says 250 and 250, and a version-1 save is written in mass — so what comes back is
        // half a kilogram in whatever this build's unit is, not the digits on the page.
        assertEquals(500L * Budget.GRAM, state.rail.massAt(TileIndex(10)))
        assertTrue(state.railAt(TileIndex(9))!!.linkedTo(Direction.Right))
    }

    @Test
    fun `a save from a future version is refused rather than misread`() {
        val text = Save.write(starterVessel(cfg.initialGrid)).replaceFirst("outofspace ${Save.VERSION}", "outofspace 99")
        val error = assertFailsWith<SaveError> { Save.read(text) }
        assertTrue(error.message!!.contains("version 99"), error.message!!)
    }

    @Test
    fun `a version 1 save keeps the throughput it was built with`() {
        // Version 1 wrote mass per *second* at four ticks a second. Read as per-tick it would run
        // the whole factory four times too fast, which is a save that loads and is still wrong.
        val v1 = """
            outofspace 1
            grid 8 6
            machine 20 Miner facing=Right ore=Iron=1000 rate=1000 carry=0
        """.trimIndent() + "\n"
        // The `rate=` this file carries no longer has anywhere to land — an extractor is metered by
        // the belt — but the record still has to load, which is what the rename and the move onto the
        // deck are for.
        assertNotNull(Save.read(v1).deck[TileIndex(20)] as? Extractor)
    }

    @Test
    fun `a version 1 save with no rate at all still loads`() {
        val v1 = "outofspace 1\ngrid 8 6\nmachine 20 Miner facing=Right ore=Iron=1000\n"
        // A `machine … Miner` record from v1, landing on the deck: the rename and the move are
        // applied on one path, so the oldest file in the game still loads.
        assertNotNull(Save.read(v1).deck[TileIndex(20)] as? Extractor)
    }

    /**
     * Every file ever written spells a wiring term `SOURCE@weight`, and the weight is gone.
     *
     * ⛔ **The sign is the whole of what survives, and the whole of what those files decided.** A
     * term carries no strength now — see [Trigger] — so `@500` cannot come back as half of anything.
     * Against a network that only ever carries 0 or [SignalField.FULL] that costs nothing a player
     * could observe: what `ALWAYS@1000 + WIRE@-1000` did was stop when the wire went live, and that
     * is exactly what it still does. There is no version gate on this, because the sign is
     * recoverable from every file in existence and a migration would have nothing else to do.
     */
    @Test
    fun `an old file's weighted terms come back as signs`() {
        val old = """
            outofspace 21
            grid 8 6
            machine 20 Extractor facing=Right wire=Run:ALWAYS@1000,WIRE@-1000
        """.trimIndent() + "\n"
        val terms = assertNotNull(Save.read(old).deck[TileIndex(20)]).wiring.triggers(Action.Run)

        assertEquals(listOf(Trigger(SignalSource.Always), Trigger(SignalSource.Wire, negated = true)), terms)
        // And it behaves as it always did: running until the wire goes live, then stopping.
        val wiring = Wiring(mapOf(Action.Run to terms))
        assertTrue(wiring.isOn(Action.Run, 0))
        assertFalse(wiring.isOn(Action.Run, SignalField.FULL))
    }

    /** A half-weighted term is not half a machine any more; it is a machine that runs. */
    @Test
    fun `an old file's fractional weight comes back as a plain positive term`() {
        val old = "outofspace 21\ngrid 8 6\nmachine 20 Extractor facing=Right wire=Run:ALWAYS@500\n"
        val terms = assertNotNull(Save.read(old).deck[TileIndex(20)]).wiring.triggers(Action.Run)

        assertEquals(listOf(Trigger(SignalSource.Always)), terms)
        assertTrue(Wiring(mapOf(Action.Run to terms)).isOn(Action.Run, 0), "at full rate, not half of one")
    }

    @Test
    fun `a broken line says which line`() {
        val error = assertFailsWith<SaveError> {
            Save.read("outofspace 1\ngrid 6 4\nmachine 3 Wombat facing=Up\n")
        }
        assertTrue(error.message!!.startsWith("line 3"), error.message!!)
        assertTrue(error.message!!.contains("Wombat"), error.message!!)
    }

    @Test
    fun `a solid in an air record is refused rather than dropped`() {
        // The invariant `PLAN_ambient_chemistry.md` asks for at the one place it can still be
        // broken. The field is typed now, but a save names its species in text, so this line is the
        // last way a solid can try to get into the atmosphere.
        //
        // ⚠️ **Refused, not dropped.** Reading it and quietly discarding the mass is the worse
        // failure: the ledger loses matter and the symptom turns up somewhere else entirely. This is
        // the test that would have caught the missing invariant in the first place.
        val error = assertFailsWith<SaveError> {
            Save.read("outofspace 1\ngrid 4 4\nair 5 Serpentine=1000\n")
        }
        assertTrue(error.message!!.contains("Serpentine"), error.message!!)

        // And the same line in a pipe, which shares the store and so shares the rule.
        assertFailsWith<SaveError> {
            Save.read("outofspace 1\ngrid 4 4\npipeair 5 Iron=1000\n")
        }

        // A gas on the same line loads exactly as it always did — the refusal is about the species,
        // not about the record.
        val fine = Save.read("outofspace 1\ngrid 4 4\nair 5 Oxygen=1000\n")
        assertEquals(1000L * Budget.GRAM, fine.air.massOf(TileIndex(5), Fluid.Oxygen))
    }

    @Test
    fun `a tile outside the grid is refused`() {
        val error = assertFailsWith<SaveError> { Save.read("outofspace 1\ngrid 4 4\nrail 99 Rail links=0\n") }
        assertTrue(error.message!!.contains("outside"), error.message!!)
    }
}
