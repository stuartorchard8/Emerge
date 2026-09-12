package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.num.Budget
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **Sibling branches each commit a metered sink's WHOLE appetite.** ⛔ Open defect — the `@Ignore`d
 * cases below are the behaviour that is wanted, not the behaviour there is.
 *
 * `Demand.covered` is a fact about a **route**: each tile's figure is the standing load between that
 * tile and the sink, accumulated by walking upstream. In a line that is exactly right, and the class
 * says why — *"a source three tiles back and a source thirty tiles back are looking at different
 * numbers, and that is the point: the near one sees the loaded run in front of it and holds off, the
 * far one sees nothing and pours."*
 *
 * It has no answer at all for two sources on **sibling** branches. A lump standing on branch A is
 * not on branch B's route, so B cannot see it and commits against an appetite already spoken for.
 * [org.emerge.demo.outofspace.world.Whitelist.promise] closes the hole for exactly one step — it is
 * keyed by `Acceptance` and dies with the whitelist, on the reasoning that by the next step the lump
 * is standing on the track and `covered` counts it. It does: **on its own branch.** So the hole
 * reopens every step, and each branch pours until the load *on that branch alone* covers the lot.
 *
 * ⚠️ **Measured here, against a sink with two packets of room:**
 *
 * | feeding branches | stranded for ever |
 * |---|---|
 * | 1 | nothing |
 * | 2 | 200 kg — one whole appetite |
 * | 3 | 400 kg — two |
 *
 * So the overdraw is `(branches - 1) × appetite`. And it is not merely early delivery: the sink is
 * **full** when the surplus arrives, a full store is a *dead end* rather than a jam, and the network
 * has no reverse gear — it stands in the corridor for the rest of the game.
 *
 * ⛔ **Nothing here is about the furnace.** A locked warehouse is the simplest machine that states a
 * finite appetite, and every other metered sink states one through the same code: a construction
 * site's shortfall, a docking port's sell order, a recipe kiln's reagent hopper. Found from Stu's
 * `over_fill.txt`, where the kiln at (19,10) was fed from (8,16) and (13,17) and took three packets
 * of ferrosilite into a two-packet hopper.
 */
class DemandBranchTest {

    private val grid = Grid(20, 14)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private val sink = grid.tile(16, 3)

    /** One tank per branch, each on a row of its own, all meeting the trunk at (10,3). */
    private val tanks = listOf(grid.tile(2, 3), grid.tile(2, 6), grid.tile(2, 9))

    private val kg = Budget.KILOGRAM

    /** What the sink has room for: two belt-loads, and no more. */
    private val room = 2L * Capacity.PACKET_MASS

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun iron(grams: Long) = Mixture.of(Species.Iron to grams, energy = 0L).atAmbient()

    /**
     * A nearly-full locked buffer at (16,3), fed by [sources] tanks down corridors of their own.
     *
     * ⚠️ **Locked, so the appetite is a plain number and nothing can absorb an overdraw.** An
     * unlocked tank would swallow the surplus and hide the defect; the lock is what makes the
     * surplus material the sink refuses at its own door.
     */
    private fun branched(sources: Int): VesselState {
        val deck = DeckArray(grid)
        deck += fixtureStorage(
            sink,
            Direction.Right,
            filter = SpeciesFilter(Species.Iron, pure = true),
            kind = DeckMachineKind.Buffer,
        )
        for (i in 0 until sources) deck += fixtureStorage(tanks[i], Direction.Right)

        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 3, 16, 3)                     // the first branch, and the trunk
        for (i in 1 until sources) {
            val y = grid.yOf(tanks[i])
            joinRow(grid, rails, 3, 10, y)                 // this branch
            joinCol(grid, rails, 10, 3, y)                 // up into the trunk
        }

        var s = VesselState(
            grid, deck,
            conduits = Conduits.ofRails(rails.toList()),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        )
            // Two packets short of its brim, so the whole appetite is [room] and no more.
            .stocked(sink, iron(1_800 * kg))
        for (i in 0 until sources) s = s.stocked(tanks[i], iron(2_000 * kg))
        return s
    }

    private fun onTrack(s: VesselState): Long {
        var total = 0L
        for (i in 0 until grid.size) total += s.rail.massAt(TileIndex(i))
        return total
    }

    private fun inSink(s: VesselState): Long = s.inStore(sink, BufferRole.Inside)?.total ?: 0L

    /**
     * ✅ The control, and it passes: one source down one corridor commits its two packets and stops.
     *
     * ⚠️ **Worth keeping green once the others are.** This is the half that has always worked, and a
     * fix that made the branched cases pass by making sources *timid* would show up here as a buffer
     * that never fills.
     */
    @Test
    fun `one branch commits exactly the room there is`() {
        val s = run(branched(sources = 1), 40 * RAIL_PERIOD)

        assertEquals(2_000L * kg, inSink(s), "a lone source did not fill the buffer to the brim")
        assertEquals(0L, onTrack(s), "a lone source left material standing in the corridor")
    }

    /** ⛔ **Open defect**: the second branch commits the whole appetite a second time. */
    @Ignore
    @Test
    fun `two branches do not over-commit`() {
        val s = run(branched(sources = 2), 40 * RAIL_PERIOD)

        assertEquals(2_000L * kg, inSink(s), "the buffer did not fill")
        assertEquals(0L, onTrack(s), "${onTrack(s)}g stranded for a sink that had ${room}g of room")
    }

    /** ⛔ **Open defect**: and a third branch commits it a third time — it scales with the branches. */
    @Ignore
    @Test
    fun `three branches do not over-commit`() {
        val s = run(branched(sources = 3), 40 * RAIL_PERIOD)

        assertEquals(2_000L * kg, inSink(s), "the buffer did not fill")
        assertEquals(0L, onTrack(s), "${onTrack(s)}g stranded for a sink that had ${room}g of room")
    }
}
