package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A ghost is track with a representation and no mass — see `apps/outofspace/PLAN_self_building_rails.md`.
 *
 * These pin the first increment only: **laying no longer conjures**. Nothing here builds a ghost up,
 * because nothing can yet; what is pinned is that a drawn run outside creative mode arrives empty,
 * that a *stated* world still arrives finished, and that the two are told apart the same way
 * everywhere — by the bill of materials, per species.
 */
class GhostTest {

    private val grid = Grid(12, 6)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun lay(state: VesselState, conduit: Conduit, from: TileIndex, to: TileIndex): VesselState =
        OutofspaceReducer.reduce(
            cfg,
            state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Lay(from, to, conduit)))),
        )

    private fun drag(state: VesselState, conduit: Conduit, y: Int, fromX: Int, toX: Int): VesselState {
        var s = state
        for (x in fromX until toX) s = lay(s, conduit, grid.tile(x, y), grid.tile(x + 1, y))
        return s
    }

    @Test
    fun `a run drawn outside creative mode is laid as ghosts`() {
        val s = drag(VesselState.empty(grid).copy(creative = false), Conduit.Rail, y = 3, fromX = 2, toX = 8)

        for (x in 2..8) {
            val tile = grid.tile(x, 3)
            assertNotNull(s.conduits.at(Conduit.Rail, tile), "no rail was laid at ($x, 3) at all")
            assertTrue(s.conduits.isGhost(Conduit.Rail, tile), "the rail at ($x, 3) arrived with metal in it")
            assertEquals(0L, s.conduits.massAt(Conduit.Rail, tile), "mass at ($x, 3)")
        }
    }

    @Test
    fun `a run drawn in creative mode is finished track`() {
        val s = drag(VesselState.empty(grid), Conduit.Rail, y = 3, fromX = 2, toX = 8)

        for (x in 2..8) {
            val tile = grid.tile(x, 3)
            assertTrue(s.conduits.isComplete(Conduit.Rail, tile), "the rail at ($x, 3) arrived a ghost")
            assertTrue(s.conduits.massAt(Conduit.Rail, tile) > 0L, "mass at ($x, 3)")
        }
    }

    /**
     * The ledger is the reason the inversion goes first. Conjured metal is an *insertion* and has to
     * be booked as one; a ghost is not conjured, so nothing may be booked for it. Booking either way
     * round would read as the world spontaneously gaining or losing heat.
     */
    @Test
    fun `laying ghosts inserts no energy, and laying finished track does`() {
        val ghosts = drag(VesselState.empty(grid).copy(creative = false), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        assertEquals(0L, ghosts.insertedEnergy, "a ghost cost the world energy it never received")

        val real = drag(VesselState.empty(grid), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        assertTrue(real.insertedEnergy > 0L, "creative track arrived with heat nobody booked")
    }

    @Test
    fun `a stated vessel is built, not drawn`() {
        // The starting ship is a description of a finished vessel, so every length of track on it is
        // real. If this ever fails, `Conduits.finished` has stopped being said by a stated world and
        // the player wakes up aboard a ghost.
        val s = starterVessel(OutofspaceConfig().initialGrid)
        var laid = 0
        s.conduits.all { conduit, tile, _ ->
            laid++
            assertTrue(s.conduits.isComplete(conduit, tile), "$conduit at $tile is a ghost on the starter vessel")
        }
        assertTrue(laid > 0, "the starter vessel has no conduit on it to check")
    }

    /**
     * ⚠️ Completeness is per species, not against a total. A ghost admits a few percent of whatever
     * came with the material it was fed, so a tile can carry more mass than its bill while still
     * being short of the one thing it is made of — and a total-mass test would call it finished and
     * hand the player a free rail.
     */
    @Test
    fun `a heavy tile short of its iron is still a ghost`() {
        val s = drag(VesselState.empty(grid), Conduit.Rail, y = 3, fromX = 2, toX = 4)
        val tile = grid.tile(3, 3)
        val stuff = s.conduits.tracks[Conduit.Rail]
        val iron = stuff[tile, Species.Iron]
        assertTrue(iron > 1L, "a length of rail should be made of some iron, got $iron")

        stuff[tile, Species.Iron] = iron - 1
        stuff[tile, Species.Oxygen] = iron * 10

        assertTrue(s.conduits.massAt(Conduit.Rail, tile) > iron, "the fixture did not make the tile heavier")
        assertFalse(s.conduits.isComplete(Conduit.Rail, tile), "a tile short of its iron passed as finished")
    }
}
