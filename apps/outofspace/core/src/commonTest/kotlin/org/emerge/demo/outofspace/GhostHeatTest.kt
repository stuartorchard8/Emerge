package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.starterVessel
import kotlin.test.Test
import kotlin.test.assertEquals

class GhostHeatTest {

    private val cfg = OutofspaceConfig(initialGrid = Grid(40, 28))

    @Test
    fun `a ghost deck machine comes back holding no heat`() {
        val grid = Grid(8, 6)
        val deck = DeckArray(grid)
        val at = grid.tile(4, 3)
        deck.stand(
            Storage(at, Direction.Up, autoLock = true, autoUnlock = true),
            withCasing = false, material = Species.Iron,
        )
        val state = VesselState(
            grid, deck, buffers = BufferLayer.forDeck(grid, deck), rail = RailLayer.empty(grid.size),
        )
        val tiles = state.deck[at]!!.tiles(grid)
        assertEquals(0L, tiles.sumOf { state.deck.stuff.energyAt(it) }, "a fresh ghost holds heat")

        val back = Save.read(Save.write(state))
        assertEquals(0L, tiles.sumOf { back.deck.stuff.energyAt(it) }, "heat across the file")
    }

    @Test
    fun `a ghost length of track comes back holding no heat`() {
        val played = run(starterVessel(cfg.initialGrid), 20)
        val laid = played.grid.tiles.first { played.conduits.at(Conduit.Rail, it) != null }
        val stuff = played.conduits.tracks[Conduit.Rail]
        // Strip it back to a bare site: no metal, and so no heat.
        for (s in Species.ALL) stuff[laid, s] = 0L
        stuff.setEnergy(laid, 0L)
        assertEquals(0L, stuff.energyAt(laid), "a fresh site holds heat")

        val back = Save.read(Save.write(played))
        assertEquals(0L, back.conduits.tracks[Conduit.Rail].energyAt(laid), "heat across the file")
    }

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }
}
