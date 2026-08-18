package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.Structure
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A deck machine placed outside creative mode is a **ghost**: standing there, made of nothing, doing
 * nothing — increment 5b of `apps/outofspace/PLAN_self_building_rails.md`.
 *
 * These pin the identity break at the deck layer and nothing more. Nothing here builds a machine up,
 * because the construction port does not exist yet; what is pinned is that a placed machine arrives
 * empty, that it weighs nothing, that it does not run, and that it does not hold pressure.
 */
class MachineGhostTest {

    private val grid = Grid(16, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun place(state: VesselState, tile: TileIndex, kind: DeckMachineKind): VesselState =
        OutofspaceReducer.reduce(
            cfg, state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(
                Edit.Place(tile, Brush.Building(kind), Direction.Right),
            ))),
        )

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, mapOf(PlayerId(0) to OutofspaceInput(emptyList()))) }
        return s
    }

    @Test
    fun `a machine placed outside creative mode arrives with no metal in it`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Smelter)

        val m = s.deck[at]
        assertNotNull(m, "the smelter did not go down at all")
        assertTrue(m is Smelter)
        assertTrue(s.deck.isGhost(at), "the smelter arrived with its casing")
        for (tile in m.tiles(grid)) {
            assertEquals(0L, s.deck.stuff.massAt(tile), "casing at $tile")
        }
    }

    @Test
    fun `a machine placed in creative mode is finished`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = true), at, DeckMachineKind.Smelter)

        assertFalse(s.deck.isGhost(at), "creative placement is supposed to conjure the whole machine")
        assertTrue(s.deck.stuff.massAt(at) > 0L, "and its casing is real matter")
    }

    /** Nothing arrived from off-world, so the ledger has nothing to book. */
    @Test
    fun `a ghost machine costs the world nothing`() {
        val at = grid.tile(8, 5)
        val ghost = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Smelter)
        assertEquals(0L, ghost.insertedEnergy, "a ghost brought heat into the world from nowhere")

        val real = place(VesselState.empty(grid).copy(creative = true), at, DeckMachineKind.Smelter)
        assertTrue(real.insertedEnergy > 0L, "creative placement is an insertion and is booked as one")
    }

    /**
     * ⚠️ The accepted consequence of a massless frame: a room is open to space until its *last* hull
     * tile is finished. Stated so that softening it later has to be a deliberate act.
     */
    @Test
    fun `a hull ghost does not hold pressure`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Hull)

        assertTrue(s.deck[at] is Hull, "the hull went down")
        assertTrue(s.deck.isGhost(at), "and it is a ghost")
        assertFalse(
            s.structure.isImpermeable(at),
            "a frame with no metal in it is holding air out",
        )
    }

    @Test
    fun `a finished hull does hold pressure`() {
        val at = grid.tile(8, 5)
        val s = place(VesselState.empty(grid).copy(creative = true), at, DeckMachineKind.Hull)
        assertEquals(Structure.Hull, s.structure[at.index])
    }

    /** A ghost weighs nothing, so a vessel gains no mass by having one drawn on it. */
    @Test
    fun `a ghost machine weighs nothing`() {
        val empty = VesselState.empty(grid).copy(creative = false)
        val withGhost = place(empty, grid.tile(8, 5), DeckMachineKind.Smelter)
        assertEquals(
            empty.deck.stuff.totalMass,
            withGhost.deck.stuff.totalMass,
            "the deck got heavier for a machine made of nothing",
        )
    }

    /**
     * The placement restriction still governs a ghost even though the ghost displaces nothing.
     *
     * Air must have somewhere to go before a solid machine may be drawn, or a player would frame out
     * a machine in a sealed pocket and be told only at completion that it could never have been
     * built there. The question is asked at placement; the displacing waits for the metal.
     */
    @Test
    fun `a ghost is refused where the air would have nowhere to go`() {
        val g = Grid(5, 5)
        val cfg = OutofspaceConfig(initialGrid = g)
        val deck = DeckArray(g)
        val pocket = g.tile(2, 2)
        for (d in Direction.ALL) deck += Hull(g.neighbour(pocket, d))
        val sealed = VesselState(
            g, deck = deck, buffers = BufferLayer.forDeck(g, deck), rail = RailLayer.empty(g.size),
        ).copy(creative = false)

        val after = OutofspaceReducer.reduce(
            cfg, sealed,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(
                Edit.Place(pocket, Brush.Building(DeckMachineKind.Hull), Direction.Right),
            ))),
        )
        assertNull(after.deck[pocket], "a ghost went down in a pocket the air cannot leave")
    }

    /** Accepted, it still pushes nothing aside: there is no metal in it yet to push with. */
    @Test
    fun `placing a ghost moves no air`() {
        val g = Grid(40, 28)
        val cfg = OutofspaceConfig(initialGrid = g)
        var start = starterVessel(g)
        repeat(20) { start = OutofspaceReducer.reduce(cfg, start, mapOf(PlayerId(0) to OutofspaceInput(emptyList()))) }
        val open = g.tiles.first { start.air.pressureAt(it) > 0L && start.deck[it] == null }
        val before = start.atmosphereMass
        val air = start.air.pressureAt(open)

        val after = OutofspaceReducer.reduce(
            cfg, start.copy(creative = false),
            mapOf(PlayerId(0) to OutofspaceInput(listOf(
                Edit.Place(open, Brush.Building(DeckMachineKind.Hull), Direction.Right),
            ))),
        )
        assertTrue(after.deck.isGhost(open), "it went down as a ghost")
        assertEquals(before, after.atmosphereMass, "the ship's air changed")
        assertEquals(air, after.air.pressureAt(open), "the air under the frame was pushed aside")
    }

    /**
     * ⛔ The anti-exploit. Let a ghost run and the casing is a formality — the player already has
     * everything the machine was for.
     */
    @Test
    fun `a ghost machine does not run`() {
        val at = grid.tile(8, 5)
        var s = place(VesselState.empty(grid).copy(creative = false), at, DeckMachineKind.Extractor)
        val before = s.extractedMass
        s = run(s, 60)
        assertEquals(before, s.extractedMass, "a ghost extractor bit something")
    }
}
