package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.logistics.Capacity

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.MACHINE_OUTPUT_CAP
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.Concentrator
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The concentrator's **port contract**, and the backpressure that gives it teeth.
 *
 * Concentrate leaves by the front port, tailings by the one in the floor. Getting these the wrong way
 * round would silently invert the whole refining game, and it is not something you can see by looking
 * at a running world — hence a test that measures purity on each side by name.
 *
 * These layouts are drawn to scale now that machines are rooms, with track threaded underneath them
 * to reach their ports. Each machine's output starts a **new run** — its input and its output are
 * different networks, and one continuous line under everything would put a concentrator's concentrate
 * back onto the pipe feeding its own input.
 */
class ConcentratorChainTest {

    /**
     * Ticks to watch a primed chain for, to be sure of catching every stage holding a packet.
     *
     * A stage's cycle is short — it fills its product buffer and empties it into the belt within a
     * few ticks — so this only has to be longer than one cycle, not tuned to it. Ten is comfortably
     * that and still costs nothing.
     */
    private val HANDOVER_WINDOW = 10

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = OutofspaceConfig(initialGrid = state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** Purity of the dominant species, as a percentage. */
    private fun purity(r: Mixture?): Int {
        if (r == null || r.isEmpty) return 0
        val d = r.dominant!!
        return (r[d] * 100 / r.total).toInt()
    }

    /**
     * A tank of ore and a tank of pure iron, both upstream of one concentrator.
     *
     * The concentrator is the only sink either can reach, so what ends up inside it is entirely a
     * statement about what it asked for.
     */
    private fun twoTanksFeeding(feed: Mixture): VesselState {
        val grid = Grid(12, 10)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Concentrator(grid.tile(8, 3), Direction.Right)   // covers x 7..9
        deck += fixtureStorage(grid.tile(2, 3), Direction.Right) // pours right, from (3,3)
        // Under the machine as well as up to it, the way `DockingPortTest` threads its mouth.
        joinRow(grid, rails, 3, 9, 3)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(2, 3), feed)
    }

    @Test
    fun `a concentrator is never sent metal that is already pure`() {
        // ⛔ **A concentrator has nothing to do to a pure lump** — it spends a charge and a tick of
        // heat handing back what went in, and it puts the ship's own refined metal at the back of a
        // queue behind the ore that actually needs the work. So it asks for [SpeciesFilter.MIXED]
        // and the network never routes pure metal to it.
        //
        // ⚠️ **Refused by the DEMAND and not at the door**, which is this file's rule: refusing at
        // the door would let the belt fill solid against a mouth that will never take what is on it.
        // So the assertion is that the run stays EMPTY, not merely that the machine stays empty.
        val pure = Mixture.of(Species.Iron to 6L * Capacity.PACKET_MASS, energy = 0L).atAmbient()
        val s = run(twoTanksFeeding(pure), 400)

        assertEquals(
            null, s.inStore(s.grid.tile(8, 3), BufferRole.Input),
            "pure iron was routed into a concentrator",
        )
        var onTrack = 0L
        for (i in 0 until s.grid.size) onTrack += s.rail.massAt(TileIndex(i))
        assertEquals(0L, onTrack, "the run filled up against a mouth that will never take it")
    }

    @Test
    fun `a concentrator is still sent ore`() {
        // The other half of the gate: the appetite is a ceiling and not a lock, so anything that is
        // actually a blend still gets in. Without this the test above passes on a machine that has
        // simply stopped working.
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(6 * Capacity.PACKET_MASS)
        val s = run(twoTanksFeeding(ore), 400)
        var onTrack = 0L
        for (i in 0 until s.grid.size) onTrack += s.rail.massAt(TileIndex(i))
        val inside = s.inStore(s.grid.tile(8, 3), BufferRole.Input)?.total ?: 0L
        val working = s.inStore(s.grid.tile(8, 3), BufferRole.Inside)?.total ?: 0L
        assertTrue(
            onTrack + inside + working > 0L,
            "ore never left the tank either, so the test above proves nothing",
        )
    }

    @Test
    fun `the concentrate leaves forward and the tailings leave downward`() {
        val grid = Grid(12, 10)
        val ore = OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(40 * Capacity.PACKET_MASS)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        deck += Concentrator(grid.tile(3, 3), Direction.Right)                // covers x 2..4
        // Forward of the concentrator's product port, and below its tailings port.
        deck += fixtureStorage(grid.tile(7, 3), Direction.Right)          // input port at (6, 3)
        // Facing Down, so its input port is on top at (3, 7), under the end of the tailings run.
        // A tank has one input now, not two, so which way it faces is the whole of how you feed it.
        deck += fixtureStorage(grid.tile(3, 8), Direction.Down)
        joinRow(grid, rails, 4, 6, 3)   // product run
        joinCol(grid, rails, 3, 4, 7)   // tailings run
        var s = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
            .stocked(grid.tile(3, 3), ore)
        s = run(s, 800)

        val forward = s.buffers.resourceAt(grid.tile(7, 3))
        val below = s.buffers.resourceAt(grid.tile(3, 8))

        assertEquals(Species.Iron, forward!!.dominant, "the concentrate keeps the ore's own metal")
        // Against the *feed*, which is the claim: 41% ore in, appreciably richer out.
        //
        // Stated as a margin over the feed rather than a fixed figure, because the claim really is
        // comparative — this is the test for "concentrating does something", and the exact numbers
        // belong to the chain test below. It used to be loose for a worse reason: the figure moved
        // with the tick rate (65% at 1Hz, 79% at 120Hz) because `process` floors its impurity split
        // once per chunk and the chunk was a chunk-per-second-divided-by-the-rate. Rates are per
        // tick now, so the chunk is a constant and so is the result.
        val fed = purity(OutofspaceReducer.DEFAULT_ORE_BODY.scaledTo(Capacity.PACKET_MASS))
        assertTrue(
            purity(forward) > fed + 20,
            "forward should be well above the ${fed}% it was fed, was ${purity(forward)}%",
        )
        assertTrue(
            forward[Species.Iron] * 100 / forward.total > below!![Species.Iron] * 100 / below.total,
            "forward must be richer in iron than the tailings",
        )
    }

    /**
     * Before output buffers were capped, a concentrator with nowhere to put its tailings simply hoarded
     * them — one machine sat on 77kg — so connecting the waste side was effectively optional and the
     * direction contract meant nothing. Now it backs up, like every other blockage in the game.
     */
    @Test
    fun `a concentrator with nowhere to put its tailings backs up instead of hoarding them`() {
        val grid = Grid(28, 10)
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        val feed = feedExtractor(grid, deck, 2, 3, bodies = 8)
        val stages = listOf(6, 11, 16)
        for (x in stages) deck += Concentrator(grid.tile(x, 3), Direction.Right)   // no waste runs anywhere
        deck += fixtureStorage(grid.tile(21, 3), Direction.Right)
        joinRow(grid, rails, 4, 5, 3)
        joinRow(grid, rails, 7, 10, 3)
        joinRow(grid, rails, 12, 15, 3)
        joinRow(grid, rails, 17, 20, 3)
        var s = VesselState(grid, deck, conduits = Conduits.ofRails(rails.toList()), bodies = feed, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size))
        // 1800, not the 1200 this used to need: a rock cell is four tonnes now and the extractor
        // chews it at 250 kg a tick, so priming a three-stage chain takes about a third longer.
        s = run(s, 1800)

        for (x in stages) {
            val held = s.inStore(grid.tile(x, 3), BufferRole.Waste)?.total ?: 0L
            assertTrue(
                held <= MACHINE_OUTPUT_CAP + Capacity.PACKET_MASS,
                "stage at $x is hoarding ${held}g of tailings; the cap is $MACHINE_OUTPUT_CAP",
            )
        }
        assertEquals(
            s.extractedMass,
            s.inTransitMass + s.ventedMass,
            "and a stalled chain still conserves mass",
        )
    }
}
