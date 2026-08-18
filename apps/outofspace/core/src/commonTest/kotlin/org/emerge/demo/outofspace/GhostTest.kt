package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Storage
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
import kotlin.test.assertNull
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

    // ── A ghost draws material to itself ──────────────────────────────────────

    /**
     * A tank of iron at (3, 3) pushing onto a run of track from (4, 3) to (7, 3), and nothing at the
     * far end. The only thing that can make the belt move is a **sink**, and the only candidate is
     * whatever is at (7, 3).
     */
    private fun tankAndRun(
        ghostAt: Int?,
        stored: Resource = Resource(Form.IronIngot, Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS, energy = 0)),
    ): VesselState {
        val grid = Grid(12, 6)
        val deck = DeckArray(grid)
        deck += Storage(grid.tile(3, 3), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 7, 3)
        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).stocked(grid.tile(3, 3), stored)
        // `Conduits.ofRails` states finished track, so a ghost has to be made by taking the metal
        // back out — which is exactly the state a drawn run arrives in.
        if (ghostAt != null) s.conduits.tracks[Conduit.Rail].release(grid.tile(ghostAt, 3))
        return s
    }

    private fun onTheRun(s: VesselState): Long =
        (4..7).sumOf { s.rail.massAt(s.grid.tile(it, 3)) }

    @Test
    fun `a run of finished track with nothing at the end never advances`() {
        // The control. Without it the test below proves only that belts work.
        //
        // A tank pushes onto its own output tile whether or not anything is drawing, so (4, 3) is
        // loaded either way and the question is never "did the tank let go". It is whether the load
        // *travels*, and with no sink anywhere the flow graph gives it nowhere to go.
        val s = run(tankAndRun(ghostAt = null), RAIL_PERIOD * 8)
        assertTrue(s.rail.massAt(s.grid.tile(4, 3)) > 0L, "the tank did not even load its own output tile")
        assertEquals(
            0L,
            (5..7).sumOf { s.rail.massAt(s.grid.tile(it, 3)) },
            "the load travelled down a run with no sink at the end of it",
        )
    }

    @Test
    fun `a ghost at the end of a run draws material down it and builds itself`() {
        val s = run(tankAndRun(ghostAt = 7), RAIL_PERIOD * 8)
        val ghost = s.grid.tile(7, 3)
        assertTrue(onTheRun(s) > 0L, "the tank held on: a ghost is not pulling as a sink")
        assertTrue(
            s.conduits.massAt(Conduit.Rail, ghost) > 0L,
            "material reached (7, 3) but the track there is still made of nothing",
        )
        assertTrue(s.conduits.isComplete(Conduit.Rail, ghost), "the ghost never finished building")
        assertTrue(s.builtMass > 0L, "a length of track was built and the ledger did not hear")
    }

    /**
     * The whole point, stated end to end: iron in a tank becomes a length of track, and the world
     * neither gains nor loses a gram doing it.
     */
    @Test
    fun `building a rail conserves mass`() {
        val before = tankAndRun(ghostAt = 7)
        val opening = before.inTransitMass + before.builtMass
        val after = run(before, RAIL_PERIOD * 8)
        assertTrue(after.builtMass > 0L, "nothing was built, so this proves nothing")
        assertEquals(
            opening,
            after.inTransitMass + after.builtMass,
            "grams went missing between the cargo ledger and the fabric",
        )
    }

    // ── ...but only material it can be built from ─────────────────────────────

    /**
     * ⛔ The anti-exploit. If anything at all could cross a ghost's tile, a player would draw a whole
     * network, run slag over it, and never pay a gram of iron for any of it. The refusal is at the
     * door: material that a rail cannot be built from does not *enter*, whatever the ghost would
     * like to keep once it is past.
     */
    @Test
    fun `a ghost refuses material it cannot be built from`() {
        val s = run(tankAndRun(ghostAt = 7, stored = slag()), RAIL_PERIOD * 8)
        assertEquals(0L, s.rail.massAt(s.grid.tile(7, 3)), "slag walked onto a ghost's tile")
        assertEquals(0L, s.conduits.massAt(Conduit.Rail, s.grid.tile(7, 3)), "a ghost built itself out of slag")
    }

    @Test
    fun `a mostly-pure delivery is admitted whole`() {
        // 95% iron, 5% something else. The slack is what stops a rail demanding perfectly separated
        // material before there is anything aboard that can separate it — and what comes with the
        // iron is baked into the tile rather than picked out of the lump.
        val nearly = Resource(
            Form.IronIngot,
            Mixture.of(
                Species.Iron to 95 * Capacity.PACKET_MASS / 100,
                Species.Silicon to 5 * Capacity.PACKET_MASS / 100,
                energy = 0,
            ),
        )
        val s = run(tankAndRun(ghostAt = 7, stored = nearly), RAIL_PERIOD * 8)
        assertTrue(
            s.conduits.massAt(Conduit.Rail, s.grid.tile(7, 3)) > 0L,
            "a 95% delivery was turned away",
        )
        // And what came with the iron is in the tile, not picked out of the lump and discarded.
        assertTrue(
            s.conduits.tracks[Conduit.Rail][s.grid.tile(7, 3), Species.Silicon] > 0L,
            "the silicon that came with the iron went nowhere",
        )
    }

    @Test
    fun `a delivery just under the bar is refused`() {
        val dirty = Resource(
            Form.IronIngot,
            Mixture.of(
                Species.Iron to 90 * Capacity.PACKET_MASS / 100,
                Species.Silicon to 10 * Capacity.PACKET_MASS / 100,
                energy = 0,
            ),
        )
        val s = run(tankAndRun(ghostAt = 7, stored = dirty), RAIL_PERIOD * 8)
        assertEquals(0L, s.conduits.massAt(Conduit.Rail, s.grid.tile(7, 3)), "a 90% delivery got in")
    }

    private fun slag(): Resource =
        Resource(Form.Slag, Mixture.of(Species.Silicon to 4 * Capacity.PACKET_MASS, energy = 0))

    // ── Taking it apart again ─────────────────────────────────────────────────

    private fun remove(state: VesselState, tile: TileIndex): VesselState =
        OutofspaceReducer.reduce(
            cfg,
            state,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(tile, DeleteLayer.Rail)))),
        )

    @Test
    fun `outside creative mode deleting a rail marks it rather than removing it`() {
        val laid = drag(VesselState.empty(grid).copy(creative = false), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        val tile = grid.tile(5, 3)
        val s = remove(laid, tile)

        val segment = s.conduits.at(Conduit.Rail, tile)
        assertNotNull(segment, "the rail vanished instead of being marked")
        assertTrue(segment.deconstructing, "the rail was not marked for deconstruction")
    }

    @Test
    fun `in creative mode deleting a rail removes it outright`() {
        // Conjuring track out of nothing and making it vanish into nothing are the same privilege.
        val laid = drag(VesselState.empty(grid), Conduit.Rail, y = 3, fromX = 2, toX = 8)
        val s = remove(laid, grid.tile(5, 3))
        assertNull(s.conduits.at(Conduit.Rail, grid.tile(5, 3)), "the rail was only marked")
    }

    /**
     * A bare run from (4, 3) to (8, 3) with the far tile emptied of metal — the ghost the player has
     * just drawn. No machines at all, so the only source and the only sink are the track itself.
     */
    private fun runWithGhostAtTheFarEnd(): VesselState {
        val deck = DeckArray(grid)
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 8, 3)
        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false)
        s.conduits.tracks[Conduit.Rail].release(grid.tile(8, 3))
        return s
    }

    @Test
    fun `a marked rail hands its metal back and then ceases to be`() {
        val before = runWithGhostAtTheFarEnd()
        val doomed = grid.tile(4, 3)
        assertTrue(before.conduits.massAt(Conduit.Rail, doomed) > 0L, "the fixture laid no metal to hand back")

        val s = run(remove(before, doomed), RAIL_PERIOD * 24)
        assertNull(s.conduits.at(Conduit.Rail, doomed), "the marked rail is still there")
    }

    /**
     * ⚠️ The two halves of the feature eat each other unless a marked segment is exempt from being a
     * ghost. It is short of its bill from the first load it hands back, so it reads as unbuilt and
     * absorbs its own metal straight off the belt — perfectly stable, entirely stationary, and from
     * outside it looks like deconstruction quietly doing nothing at all.
     */
    @Test
    fun `a rail being taken apart does not build itself back up`() {
        val doomed = grid.tile(4, 3)
        val s = run(remove(runWithGhostAtTheFarEnd(), doomed), RAIL_PERIOD)
        assertTrue(
            s.conduits.massAt(Conduit.Rail, doomed) < s.conduits.massAt(Conduit.Rail, grid.tile(5, 3)),
            "the marked rail is still holding as much metal as its untouched neighbour",
        )
    }

    @Test
    fun `deconstruction conserves mass`() {
        val before = runWithGhostAtTheFarEnd()
        val opening = before.inTransitMass + before.builtMass
        val s = run(remove(before, grid.tile(4, 3)), RAIL_PERIOD * 24)
        assertEquals(
            opening,
            s.inTransitMass + s.builtMass,
            "grams went missing between the fabric and the cargo ledger",
        )
    }

    /**
     * ⛔ The lock. Track under a deck machine's port cannot be taken up while the machine stands.
     *
     * It is what makes the port rules tractable — a locked run can never be deconstructing, so the
     * two awkward priority cases cannot arise — and it stops a player stranding a machine by pulling
     * up the very thing feeding it.
     */
    @Test
    fun `track under a machine's port cannot be marked`() {
        val built = tankAndRun(ghostAt = null).copy(creative = false)
        // The tank at (3, 3) faces right, so its output port stands on (4, 3).
        val underPort = built.grid.tile(4, 3)
        val s = remove(built, underPort)
        val segment = s.conduits.at(Conduit.Rail, underPort)
        assertNotNull(segment, "the locked rail was removed")
        assertFalse(segment.deconstructing, "the rail under a machine's port was marked anyway")
    }

    /**
     * The claim the whole design rests on: a length of track can be made to **walk**. Draw a ghost
     * ahead of it, mark the tile behind, and the same atoms travel down the line to the new tile.
     */
    @Test
    fun `a rail walks along the run when one end is drawn and the other marked`() {
        val before = runWithGhostAtTheFarEnd()
        val ghost = grid.tile(8, 3)
        val doomed = grid.tile(4, 3)
        val had = before.conduits.massAt(Conduit.Rail, doomed)
        assertTrue(before.conduits.isGhost(Conduit.Rail, ghost), "the fixture did not make a ghost")

        val s = run(remove(before, doomed), RAIL_PERIOD * 24)

        assertNull(s.conduits.at(Conduit.Rail, doomed), "the near end never finished going")
        assertTrue(s.conduits.isComplete(Conduit.Rail, ghost), "the far end never finished arriving")
        // The same atoms, to the unit. Four tiles of travel and two ledger crossings later.
        assertEquals(had, s.conduits.massAt(Conduit.Rail, ghost), "the rail lost mass on the way")
    }

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }
}
