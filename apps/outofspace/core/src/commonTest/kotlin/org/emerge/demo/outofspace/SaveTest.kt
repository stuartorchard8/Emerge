package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Debris
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Miner
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
import kotlin.test.assertTrue

/**
 * Saving and loading.
 *
 * The headline test is [`a loaded world runs on identically`]: two copies of the same vessel, one of
 * which went through a save file, must still agree after another few seconds of simulation. That is
 * a much sharper check than comparing the two states directly — a state comparison would pass while
 * quietly ignoring a field the format forgot, whereas anything the save loses shows up as divergence
 * the moment the sim reads it. A miner's fractional carry, a diverter's cursor and a gauge's last
 * reading are all invisible in a screenshot and all change the future.
 */
class SaveTest {

    private val cfg = OutofspaceConfig(grid = Grid(40, 28))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    // ── The contract ──────────────────────────────────────────────────────────

    @Test
    fun `a loaded world runs on identically`() {
        // Long enough for the miners to accrue a carry, the belts to fill and the first jam to form.
        val played = run(starterVessel(cfg.grid), 400)

        val reloaded = Save.read(Save.write(played))
        assertEquals(Save.write(played), Save.write(reloaded), "the reload differs at tick ${played.tick}")

        // And it keeps agreeing, which is the part a field-by-field comparison cannot promise.
        assertEquals(Save.write(run(played, 200)), Save.write(run(reloaded, 200)))
    }

    @Test
    fun `writing a loaded save gives back the same text`() {
        val text = Save.write(starterVessel(cfg.grid))
        assertEquals(text, Save.write(Save.read(text)))
    }

    @Test
    fun `the ledgers survive, so a leak cannot be laundered by saving`() {
        val played = run(starterVessel(cfg.grid), 300)
        val reloaded = Save.read(Save.write(played))

        assertEquals(played.minedGrams, reloaded.minedGrams)
        assertEquals(played.ventedGrams, reloaded.ventedGrams)
        assertEquals(played.inTransitGrams, reloaded.inTransitGrams)
        assertEquals(played.baselineJoules, reloaded.baselineJoules)
        assertEquals(played.baselineAirGrams, reloaded.baselineAirGrams)
        assertEquals(
            reloaded.minedGrams,
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

        val state = VesselState(grid, List(grid.size) { null }, rails = rails.toList())
        val reloaded = Save.read(Save.write(state))
        for (i in 0 until grid.size) {
            assertEquals(rails[i]?.links, reloaded.rails[i]?.links, "links differ at tile $i")
        }
    }

    @Test
    fun `a gauge keeps its channel and its last reading`() {
        val grid = Grid(6, 4)
        val rails = arrayOfNulls<Segment>(grid.size)
        val ore = Resource(Form.Ore, Mixture.of(Species.Iron to 410L, Species.Silica to 590L))
        rails[grid.index(2, 2)] = Segment(org.emerge.demo.outofspace.world.Conduit.Rail, channel = Channel.Cyan)
            .reading(SolidPacket(ore))

        val state = VesselState(grid, List(grid.size) { null }, rails = rails.toList())
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
        machines[grid.index(4, 4)] = Miner(
            Direction.Right,
            composition = Mixture.of(Species.Iron to 700L, Species.Carbon to 300L),
            buffer = Resource(Form.Ore, Mixture.of(Species.Iron to 123L)),
            carry = 37L,
        ).withWiring(starterVessel(cfg.grid)[cfg.grid.index(5, 19)]!!.wiring)
        machines[grid.index(7, 4)] = Storage(Direction.Left, Resource(Form.IronIngot, Mixture.of(Species.Iron to 900L)))

        val state = VesselState(grid, machines.toList())
        val back = Save.read(Save.write(state))

        val miner = back[grid.index(4, 4)] as Miner
        assertEquals(37L, miner.carry)
        assertEquals(123L, miner.buffer.mass)
        assertEquals(700L, miner.composition[Species.Iron])
        // `ALWAYS - RED`: two terms, and the negative one is the whole behaviour.
        assertEquals(2, miner.wiring.triggers(org.emerge.demo.outofspace.world.Action.Run).size)
        assertEquals(-1000, miner.wiring.triggers(org.emerge.demo.outofspace.world.Action.Run)[1].weightPermille)

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
        val played = run(starterVessel(cfg.grid), 200)
        val back = Save.read(Save.write(played))
        assertEquals(played.storedJoules, back.storedJoules)
        assertEquals(played.atmosphereGrams, back.atmosphereGrams)
        for (tile in 0 until cfg.grid.size) {
            assertEquals(played.heat.joulesAt(tile), back.heat.joulesAt(tile), "joules differ at tile $tile")
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
        val text = Save.write(starterVessel(cfg.grid)).replaceFirst("outofspace ${Save.VERSION}", "outofspace 99")
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
        val miner = assertNotNull(Save.read(v1).machines[20] as? Miner)
        assertEquals(250L, miner.gramsPerTick, "1000 g/s at 4 ticks a second is 250 g/tick")
    }

    @Test
    fun `a version 1 save with no rate at all gets the current default`() {
        val v1 = "outofspace 1\ngrid 8 6\nmachine 20 Miner facing=Right ore=Iron=1000\n"
        val miner = assertNotNull(Save.read(v1).machines[20] as? Miner)
        assertEquals(Miner(Direction.Right, Mixture.EMPTY).gramsPerTick, miner.gramsPerTick)
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
