package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a building is made of has to be able to **fail**, or material choice is a menu with no wrong
 * answers on it.
 *
 * ⛔ **Melting is expressed as the order a player would have given: mark it for deconstruction.** A
 * structure is not a thing the sim may quietly delete — and everything after the mark is machinery
 * that already worked. A marked machine grows an output port, hands its casing back to the network
 * and ceases to be once it is holding nothing; a volatile among what it hands back is off-gassed the
 * moment it reaches a belt. Nothing about a melting ice hull needed writing except the sentence
 * that notices.
 */
class MeltingTest {

    private val grid = Grid(10, 8)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput(emptyList()))) }
        return s
    }

    /** A vessel with one hull plate, made of [material], sitting at room temperature. */
    private fun hullOf(material: Species, at: TileIndex): VesselState {
        val deck = DeckArray(grid)
        deck.stand(Hull(at), withCasing = true, material = material)
        return VesselState(
            grid, deck,
            conduits = Conduits.ofRails(arrayOfNulls<Segment>(grid.size).toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
    }

    /**
     * ⛔ **A hull of water ice cannot exist in a warm room**, which is the case Stu named when he
     * chose to allow building out of anything: ice is a perfectly good structural material at 200 K
     * and not one at 293 K, and the game has to say so rather than pretend a wall is a wall.
     */
    @Test
    fun `an ice hull in a warm room is condemned`() {
        val at = grid.tile(4, 4)
        val start = hullOf(Species.Water, at)
        assertTrue(
            start.deck.stuff.kelvinAt(at) > Species.Water.meltingKelvin,
            "fixture: ambient is supposed to be above the melting point of ice",
        )
        assertFalse(at in start.scrapping, "fixture: nobody has marked it")

        val s = run(start, OutofspaceReducer.HEAT_PERIOD * 3)
        assertTrue(at in s.scrapping, "the ice hull was not condemned")
    }

    /** ⚠️ And the control, or the test above passes against a rule that condemns everything. */
    @Test
    fun `a steel hull in the same room is left alone`() {
        val at = grid.tile(4, 4)
        val s = run(hullOf(Species.Steel, at), OutofspaceReducer.HEAT_PERIOD * 3)
        assertFalse(at in s.scrapping, "a steel hull was condemned at room temperature")
        assertTrue(s.deck[at] != null, "and it should still be standing")
    }

    /**
     * The margin is the material's own number and not a global one: carbon dioxide gives up at 217 K
     * where water lasts to 273, so a room cold enough for one is not cold enough for the other.
     */
    @Test
    fun `each material fails at its own temperature`() {
        val at = grid.tile(4, 4)
        // A room at 250 K: past dry ice, and not past water ice.
        fun coldHull(material: Species): VesselState {
            val start = hullOf(material, at)
            val capacity = start.deck.stuff.heatCapacityAt(at)
            start.deck.stuff.setEnergy(at, capacity * 250L)
            return run(start, OutofspaceReducer.HEAT_PERIOD * 3)
        }
        assertTrue(
            at in coldHull(Species.CarbonDioxide).scrapping,
            "dry ice survived 250 K, which is above its 217 K",
        )
        assertFalse(
            at in coldHull(Species.Water).scrapping,
            "water ice was condemned at 250 K, which is below its 273 K",
        )
    }

    /**
     * ⚠️ Track fails the same way, and it carries its own mark rather than being named in a set —
     * so this is a separate path and a separate test, exactly as building it was.
     */
    @Test
    fun `a run of ice track is condemned too`() {
        val at = grid.tile(4, 4)
        val rails = arrayOfNulls<Segment>(grid.size)
        rails[at.index] = Segment(Conduit.Rail, material = Species.Water)
        // ⚠️ `ofRails` states *finished* track, so the metal is already down — the tile is
        // re-stocked rather than laid a second time, which throws.
        val conduits = Conduits.ofRails(rails.toList())
        val stuff = conduits.tracks[Conduit.Rail]
        val mass = stuff.massAt(at)
        stuff.release(at)
        stuff[at, Species.Water] = mass
        stuff.setEnergy(at, stuff.heatCapacityAt(at) * Temperature.AMBIENT_KELVIN.toLong())
        val deck = DeckArray(grid)
        val start = VesselState(
            grid, deck,
            conduits = conduits,
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)

        assertFalse(
            start.conduits.at(Conduit.Rail, at)?.deconstructing == true,
            "fixture: nobody has marked it",
        )
        val s = run(start, OutofspaceReducer.HEAT_PERIOD * 3)
        assertTrue(
            s.conduits.at(Conduit.Rail, at)?.deconstructing == true,
            "ice track survived a room at ${Temperature.AMBIENT_KELVIN} K",
        )
    }
}
