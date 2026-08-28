package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machineBillOfMaterials
import org.emerge.demo.outofspace.world.material
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.species

/**
 * A hull that finishes building displaces the air it now stands in — and nothing may put any back.
 *
 * The displacement happens during the rail step, which runs *after* the tick's [org.emerge.demo
 * .outofspace.world.StructureMap] was derived. If that map is not re-derived, the fluid step later
 * in the same tick still believes the tile is a ghost — permeable — and diffuses air straight back
 * into the plate, where the next tick's map buries it.
 */
class GhostCompletionAirTest {

    private val grid = Grid(16, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** A sealed room with a stocked tank, a run of track, and a ghost hull plate at the far end. */
    private fun roomWithGhostPlate(at: TileIndex): VesselState {
        val deck = DeckArray(grid)
        for (x in 0..<grid.width) { deck += Hull(grid.tile(x, 0)); deck += Hull(grid.tile(x, grid.height - 1)) }
        for (y in 1..<grid.height - 1) { deck += Hull(grid.tile(0, y)); deck += Hull(grid.tile(grid.width - 1, y)) }
        deck += Storage(grid.tile(3, 4), Direction.Right)
        deck.standGhost(Hull(at))
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, grid.xOf(at), 4)
        return VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(
            grid.tile(3, 4),
            Hull(at).kind.material.composition
                .scaledTo(machineBillOfMaterials(Hull(at).kind, 1, Hull(at).kind.material.species).total * 4)
                .atAmbient(),
        ).copy(creative = false)
    }

    @Test
    fun `air does not flow into the plate on the tick it is finished`() {
        val at = grid.tile(10, 4)
        var s = roomWithGhostPlate(at)
        assertTrue(s.deck.isGhost(at), "the fixture stood a finished plate")
        assertTrue(s.atmosphereMass > 0L, "the room starts with air in it")

        var finishedAt = -1L
        repeat(OutofspaceReducer.RAIL_PERIOD * 80) {
            s = OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput.EMPTY))
            if (finishedAt < 0 && !s.deck.isGhost(at)) finishedAt = s.tick
            if (finishedAt >= 0) {
                assertEquals(
                    0L, s.air.densityAt(at),
                    "tick ${s.tick} (finished at $finishedAt): air inside a solid plate",
                )
            }
        }
        assertTrue(finishedAt >= 0, "the plate never finished")
    }
}
