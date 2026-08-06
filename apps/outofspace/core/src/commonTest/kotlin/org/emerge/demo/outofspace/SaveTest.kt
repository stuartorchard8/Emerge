package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Debris
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
    fun `a gauge keeps its channel and its last reading`() {
        val grid = Grid(6, 4)
        val rails = arrayOfNulls<Segment>(grid.size)
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 410L, Species.Silica to 590L))
        rails[grid.index(2, 2)] = Segment(org.emerge.demo.outofspace.world.Conduit.Rail, channel = Channel.Cyan)
            .reading(SolidPacket(ore))

        val state = VesselState(grid, List(grid.size) { null }, conduits = Conduits.ofRails(rails.toList()))
        val back = Save.read(Save.write(state)).rails[grid.index(2, 2)]
        assertNotNull(back)
        assertEquals(Channel.Cyan, back.channel)
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
    fun `spilled material on the deck survives`() {
        val grid = Grid(6, 6)
        val piles = mapOf(
            grid.index(2, 2) to listOf(
                Resource(Form.Ore, Mixture.of(Species.Iron to 500L)),
                Resource(Form.IronIngot, Mixture.of(Species.Iron to 250L)),
            ),
        )
        val state = VesselState(grid, List(grid.size) { null }, debris = Debris.of(piles))
        val back = Save.read(Save.write(state))
        assertEquals(750L, back.debrisGrams)
        assertEquals(2, back.debris[grid.index(2, 2)].size)
        assertEquals(Form.IronIngot, back.debris[grid.index(2, 2)][1].form)
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
            machine 8 Sensor facing=Up channel=Green
            rail 9 Rail links=1
            rail 10 Rail links=4 held=S:Ore/Iron=250,Carbon=250
            """.trimIndent(),
        )
        assertEquals(Grid(6, 4), state.grid)
        assertEquals(Channel.Green, (state[8] as Sensor).channel)
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
