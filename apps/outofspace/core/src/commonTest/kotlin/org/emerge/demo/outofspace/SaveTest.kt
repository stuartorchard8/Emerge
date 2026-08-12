package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.SignalSource
import org.emerge.demo.outofspace.world.Trigger
import org.emerge.demo.outofspace.world.Action
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.Extractor
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.SaveError
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Sensor
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
     * So this changes **one token**: the unit in the header, and nothing else in the file. The same
     * bytes are then read as a coarse-unit world and as a native one, and every mass in the first
     * must be exactly `factor` times the second while everything dimensionless is untouched. A field
     * the migration forgot fails the first half. A field it scales that it should not — a rock's
     * composition, a tile index, a tick count — fails the second.
     */
    @Test
    fun `a save from a coarser mass unit is multiplied up, and only where it should be`() {
        val factor = 1_000L
        val played = run(starterVessel(cfg.initialGrid), 200)
        val native = Save.write(played)

        // The one edit: same numbers, but the file now claims each was worth 1000x as much.
        val header = native.lineSequence().first().split(' ')
        val coarse = native.replaceFirst(
            header.joinToString(" "),
            "outofspace ${header[1]} ${header[2].toLong() * factor}",
        )

        val here = Save.read(native)
        val there = Save.read(coarse)

        // ── Mass, energy and momentum: all up by the factor ──
        assertEquals(here.extractedGrams * factor, there.extractedGrams, "extracted")
        assertEquals(here.ventedGrams * factor, there.ventedGrams, "vented")
        assertEquals(here.generatedJoules * factor, there.generatedJoules, "generated")
        assertEquals(here.radiatedJoules * factor, there.radiatedJoules, "radiated")
        assertEquals(here.baselineJoules * factor, there.baselineJoules, "baseline joules")
        assertEquals(here.baselineAirGrams * factor, there.baselineAirGrams, "baseline air")
        assertEquals(here.insertedJoules * factor, there.insertedJoules, "inserted")
        assertEquals(here.solidToAirJoules * factor, there.solidToAirJoules, "solid to air")
        // ⚠️ NOT `massGrams` or `storedJoules`, and finding that out is what this test was for.
        // A vessel's mass is *derived* from `Material.composition` and the species densities — it is
        // never written to the file at all — so it is already in this build's units and must not
        // move. Solid heat is the subtler case: `k=` is omitted for any machine sitting at ambient,
        // and an omitted field is **reconstructed from a current-unit default** rather than read and
        // scaled. Both are correct, and both would look like a migration bug to anyone who assumed
        // "every mass in the world scales" without asking where each number came from.

        // Guards the guard: if the world were empty these would all be 0 == 0.
        assertTrue(here.extractedGrams > 0 || here.baselineAirGrams > 0, "the fixture must have mass in it")

        // Air, tile by tile, is the biggest mass field in the game and goes through readMixture.
        var airTiles = 0
        for (t in 0 until here.grid.size) {
            val a = here.air.mixtureAt(t)
            if (a.isEmpty) continue
            airTiles++
            assertEquals(a.total * factor, there.air.mixtureAt(t).total, "air at tile $t")
        }
        assertTrue(airTiles > 0, "the fixture must have air in it")

        // ── Dimensionless: identical ──
        assertEquals(here.tick, there.tick, "a tick count is not a mass")
        assertEquals(here.grid, there.grid, "nor is a grid")
        assertEquals(here.positionX, there.positionX, "nor is a position — it is in tiles")
        assertEquals(here.positionY, there.positionY, "nor is a position — it is in tiles")
        assertEquals(here.machines.count { it != null }, there.machines.count { it != null }, "machine count")
        // The trap this whole family of bugs lives in: same syntax as air, entirely different meaning.
        for (i in here.bodies.indices) {
            assertEquals(
                here.bodies[i].oreComposition,
                there.bodies[i].oreComposition,
                "a rock's composition is proportions, not grams",
            )
        }
    }

    @Test
    fun `a save finer than this build is refused rather than rounded`() {
        // The one direction that cannot be migrated: dividing would round every mass in the world,
        // and silently halving somebody's cargo is worse than declining to open the file.
        val native = Save.write(starterVessel(cfg.initialGrid))
        val header = native.lineSequence().first().split(' ')
        val finer = native.replaceFirst(
            header.joinToString(" "),
            "outofspace ${header[1]} ${header[2].toLong() / 10}",
        )
        assertFailsWith<SaveError> { Save.read(finer) }
    }

    @Test
    fun `air temperature survives a save`() {
        val start = starterVessel(cfg.initialGrid)
        val joules = start.air.copyJoules()
        val hot = cfg.initialGrid.index(cfg.initialGrid.width / 2, cfg.initialGrid.height / 2)
        joules[hot] *= 3
        val played = run(start.copy(air = AirField.of(start.air.copyGrams(), joules)), 60)

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

        assertEquals(played.extractedGrams, reloaded.extractedGrams)
        assertEquals(played.ventedGrams, reloaded.ventedGrams)
        assertEquals(played.inTransitGrams, reloaded.inTransitGrams)
        assertEquals(played.baselineJoules, reloaded.baselineJoules)
        assertEquals(played.baselineAirGrams, reloaded.baselineAirGrams)
        assertEquals(
            reloaded.extractedGrams,
            reloaded.inTransitGrams + reloaded.ventedGrams,
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
        val rails = arrayOfNulls<Segment>(grid.size)
        fun lay(x: Int, y: Int) { rails[grid.index(x, y)] = rails[grid.index(x, y)] ?: Segment(org.emerge.demo.outofspace.world.Conduit.Rail) }
        fun join(a: Int, b: Int, dir: Direction) {
            rails[a] = rails[a]!!.joinedTo(dir)
            rails[b] = rails[b]!!.joinedTo(dir.opposite)
        }
        for (x in 1..5) lay(x, 3)
        for (y in 1..5) lay(3, y)
        for (x in 1..4) join(grid.index(x, 3), grid.index(x + 1, 3), Direction.Right)
        for (y in 1..4) join(grid.index(3, y), grid.index(3, y + 1), Direction.Down)

        val state = VesselState(grid, List(grid.size) { null }, conduits = Conduits.ofRails(rails.toList()))
        val reloaded = Save.read(Save.write(state))
        for (i in 0 until grid.size) {
            assertEquals(rails[i]?.links, reloaded.rails[i]?.links, "links differ at tile $i")
        }
    }

    @Test
    fun `both layers of a crossing survive a save`() {
        val grid = Grid(8, 6)
        val rails = arrayOfNulls<Segment>(grid.size)
        val pipes = arrayOfNulls<Segment>(grid.size)
        val crossing = grid.index(4, 3)
        for (x in 2..6) rails[grid.index(x, 3)] = Segment(Conduit.Rail, links = 1 shl Direction.Right.ordinal)
        for (y in 1..5) pipes[grid.index(4, y)] = Segment(Conduit.Pipe, links = 1 shl Direction.Down.ordinal)

        val state = VesselState(
            grid,
            List(grid.size) { null },
            conduits = Conduits.of(
                grid.size,
                Conduit.Rail to rails.toList(),
                Conduit.Pipe to pipes.toList(),
            ),
        )
        val back = Save.read(Save.write(state))

        assertEquals(Conduit.Rail, back.conduits.at(Conduit.Rail, crossing)?.conduit, "rail lost at the crossing")
        assertEquals(Conduit.Pipe, back.conduits.at(Conduit.Pipe, crossing)?.conduit, "pipe lost at the crossing")
        assertEquals(state.conduits, back.conduits, "a layer changed somewhere across the round trip")
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
        assertEquals(Conduit.Rail, back.conduits.at(Conduit.Rail, 8)?.conduit)
        assertEquals(Conduit.Pipe, back.conduits.at(Conduit.Pipe, 9)?.conduit)
        assertNull(back.conduits.at(Conduit.Pipe, 8), "a rail was filed on the pipe layer")
        assertNull(back.conduits.at(Conduit.Rail, 9), "a pipe was filed on the rail layer")
    }

    @Test
    fun `a gauge keeps that it is one, and its last reading`() {
        val grid = Grid(6, 4)
        val rails = arrayOfNulls<Segment>(grid.size)
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 410L, Species.Silica to 590L))
        rails[grid.index(2, 2)] = Segment(org.emerge.demo.outofspace.world.Conduit.Rail, isGauge = true)
            .reading(SolidPacket(ore))

        val state = VesselState(grid, List(grid.size) { null }, conduits = Conduits.ofRails(rails.toList()))
        val back = Save.read(Save.write(state)).rails[grid.index(2, 2)]
        assertNotNull(back)
        assertTrue(back.isGauge)
        assertEquals(Form.Ore, back.lastForm)
        assertEquals(Species.Silica, back.lastDominant)
        assertEquals(590, back.lastPurity)
        assertEquals(1000L, back.lastMass)
    }

    @Test
    fun `a machine keeps its wiring, its buffers and its fractional carry`() {
        val grid = Grid(10, 10)
        val machines = arrayOfNulls<Machine>(grid.size)
        machines[grid.index(4, 4)] = Extractor(
            Direction.Right,
            input = Resource(Form.Ore, Mixture.of(Species.Iron to 700L, Species.Carbon to 300L)),
            buffer = Resource(Form.Ore, Mixture.of(Species.Iron to 123L)),
            carry = 37L,
            // Any non-default wiring will do; the starter vessel's second extractor is the one that has
            // some. Found rather than indexed, because the layout is free to move — it was pinned at
            // (5,19) until the vessel was centred in its grid, and then this broke.
        ).withWiring(starterVessel(cfg.initialGrid).machines.first { it is Extractor && it.wiring != Wiring.RUNNING }!!.wiring)
        machines[grid.index(7, 4)] = Storage(Direction.Left, Resource(Form.IronIngot, Mixture.of(Species.Iron to 900L)))

        val state = VesselState(grid, machines.toList())
        val back = Save.read(Save.write(state))

        val extractor = back[grid.index(4, 4)] as Extractor
        assertEquals(37L, extractor.carry)
        assertEquals(123L, extractor.buffer.mass)
        assertEquals(700L, extractor.input?.mixture?.get(Species.Iron), "the cell in the jaws too")
        // `ALWAYS - RED`: two terms, and the negative one is the whole behaviour.
        assertEquals(2, extractor.wiring.triggers(org.emerge.demo.outofspace.world.Action.Run).size)
        assertEquals(-1000, extractor.wiring.triggers(org.emerge.demo.outofspace.world.Action.Run)[1].weightPermille)

        val tank = back[grid.index(7, 4)] as Storage
        assertEquals(Form.IronIngot, tank.contents?.form)
        assertEquals(900L, tank.contents?.mass)
    }

    @Test
    fun `heat and air come back tile by tile, not just in total`() {
        val played = run(starterVessel(cfg.initialGrid), 200)
        val back = Save.read(Save.write(played))
        assertEquals(played.storedJoules, back.storedJoules)
        assertEquals(played.atmosphereGrams, back.atmosphereGrams)
        // Body by body rather than tile by tile: solid heat lives on the machine and the segment
        // now, so the thing that has to survive a round trip is each object's own energy.
        for (tile in 0 until played.grid.size) {
            assertEquals(played.machines[tile]?.joules, back.machines[tile]?.joules, "machine joules differ at tile $tile")
            assertEquals(played.rails[tile]?.joules, back.rails[tile]?.joules, "segment joules differ at tile $tile")
            assertEquals(played.air.mixtureAt(tile), back.air.mixtureAt(tile), "air differs at tile $tile")
        }
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
        assertEquals(Direction.Up, (state[8] as Sensor).facing)
        assertEquals(500L, state.rails[10]?.held?.mass)
        assertTrue(state.rails[9]!!.linkedTo(Direction.Right))
    }

    @Test
    fun `a save from a future version is refused rather than misread`() {
        val text = Save.write(starterVessel(cfg.initialGrid)).replaceFirst("outofspace ${Save.VERSION}", "outofspace 99")
        val error = assertFailsWith<SaveError> { Save.read(text) }
        assertTrue(error.message!!.contains("version 99"), error.message!!)
    }

    @Test
    fun `a version 1 save keeps the throughput it was built with`() {
        // Version 1 wrote grams per *second* at four ticks a second. Read as per-tick it would run
        // the whole factory four times too fast, which is a save that loads and is still wrong.
        val v1 = """
            outofspace 1
            grid 8 6
            machine 20 Miner facing=Right ore=Iron=1000 rate=1000 carry=0
        """.trimIndent() + "\n"
        val extractor = assertNotNull(Save.read(v1).machines[20] as? Extractor)
        assertEquals(250L, extractor.gramsPerTick, "1000 g/s at 4 ticks a second is 250 g/tick")
    }

    @Test
    fun `a version 1 save with no rate at all gets the current default`() {
        val v1 = "outofspace 1\ngrid 8 6\nmachine 20 Miner facing=Right ore=Iron=1000\n"
        val extractor = assertNotNull(Save.read(v1).machines[20] as? Extractor)
        assertEquals(Extractor(Direction.Right).gramsPerTick, extractor.gramsPerTick)
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
    fun `a tile outside the grid is refused`() {
        val error = assertFailsWith<SaveError> { Save.read("outofspace 1\ngrid 4 4\nrail 99 Rail links=0\n") }
        assertTrue(error.message!!.contains("outside"), error.message!!)
    }
}
