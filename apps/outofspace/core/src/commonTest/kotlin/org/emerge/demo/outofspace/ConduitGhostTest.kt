package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.conduitBillOfMaterials
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.emerge.demo.outofspace.world.materialBefore

/**
 * **Pipes and wires build themselves too**, by the mechanism `GhostTest` pins for rails.
 *
 * The asymmetry these exist to hold down: plumbing cannot carry ingots, so a pipe ghost is not fed
 * by pipes. It is fed by a **rail port on its own tile** — the player runs temporary track over the
 * line, lets it build, and takes the track up again. That is why rail and pipe stopped excluding
 * each other, and why every test here lays two layers on the same tiles.
 *
 * ⛔ **Copper, not iron.** A pipe is copper and a rail is iron, so a tank of iron cannot build one
 * and the door is right to refuse it — the first version of this file measured only that.
 */
class ConduitGhostTest {

    private val grid = Grid(12, 6)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /**
     * A tank of copper feeding a row of finished track, with a pipe ghost laid along the same tiles.
     *
     * `Conduits.of` states finished conduit, so the pipe is made a ghost the way `GhostTest` makes
     * one — by taking the metal back out — rather than by driving the edit path, which is a
     * different thing to measure.
     */
    /**
     * ⚠️ `joinRow` lays `Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))` whatever array it is handed — it is a rail fixture —
     * so a pipe row has to be built here. A layer full of rail-typed segments looks right to
     * everything that indexes by layer and wrong to everything that reads `Segment.conduit`.
     */
    private fun pipeRow(pipes: Array<Segment?>, fromX: Int, toX: Int, y: Int) {
        for (x in fromX..toX) {
            var links = 0
            if (x > fromX) links = links or (1 shl Direction.Left.ordinal)
            if (x < toX) links = links or (1 shl Direction.Right.ordinal)
            pipes[grid.tile(x, y).index] = Segment(Conduit.Pipe, links = links, material = materialBefore(Conduit.Pipe))
        }
    }

    private fun tankAndPipedRun(
        pipeAt: IntRange = 4..7,
        stored: Mixture = Mixture.of(Species.Copper to 12 * Capacity.PACKET_MASS, energy = 0),
        railUnder: Boolean = true,
    ): VesselState {
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(3, 3), Direction.Right)
        // ⚠️ **Somewhere for the copper to go**, or nothing can ever be deconstructed: a marked
        // segment with no consumer downstream waits rather than dumping its metal on a dead run, so
        // a run whose only machine is a source can only ever be built. A tank takes material in on
        // its **left** — see `Port.kt` — so the receiving one sits past the end of the line.
        deck += fixtureStorage(grid.tile(9, 3), Direction.Right)
        val rails = arrayOfNulls<Segment>(grid.size)
        if (railUnder) joinRow(grid, rails, 4, 8, 3)
        val pipes = arrayOfNulls<Segment>(grid.size)
        pipeRow(pipes, pipeAt.first, pipeAt.last, 3)
        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.of(
                grid.size,
                Conduit.Rail to rails.toList(),
                Conduit.Pipe to pipes.toList(),
            ),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false).stocked(grid.tile(3, 3), stored)
        for (x in pipeAt) s.conduits.tracks[Conduit.Pipe].release(grid.tile(x, 3))
        return s
    }

    private fun pipeMass(s: VesselState, x: Int): Long = s.conduits.massAt(Conduit.Pipe, s.grid.tile(x, 3))

    @Test
    fun `a pipe ghost is built by the rail running over it`() {
        var s = tankAndPipedRun()
        assertEquals(0L, pipeMass(s, 5), "the pipe started with metal in it")

        s = run(s, RAIL_PERIOD * 200)

        assertTrue(
            (4..7).all { s.conduits.isComplete(Conduit.Pipe, s.grid.tile(it, 3)) },
            "the pipe never finished: " + (4..7).joinToString { "$it=${pipeMass(s, it)}g" },
        )
        assertEquals(
            conduitBillOfMaterials(Conduit.Pipe, materialBefore(Conduit.Pipe)).total,
            pipeMass(s, 5),
            "a finished pipe tile does not weigh a pipe tile",
        )
    }

    /**
     * ⛔ **The delivery is refused, not swallowed.** A pipe is copper; iron is not something it can
     * be built from, and the door asks per species against the bill's own share.
     *
     * Without this the build test above proves only that *something* arrives.
     */
    @Test
    fun `a pipe ghost refuses iron`() {
        var s = tankAndPipedRun(stored = Mixture.of(Species.Iron to 4 * Capacity.PACKET_MASS, energy = 0))
        s = run(s, RAIL_PERIOD * 200)

        assertEquals(0L, pipeMass(s, 5), "iron was built into a copper pipe")
    }

    /**
     * ⛔ **This is the bug that prompted the work.** Outside creative, deleting a pipe marked it and
     * nothing ever scrapped it: the mark was set for every conduit but only rail was walked. The tile
     * sat `MARKED FOR DECONSTRUCTION` for ever and the delete silently did nothing, irreversibly.
     */
    @Test
    fun `a marked pipe hands its copper back and goes`() {
        var s = tankAndPipedRun()
        s = run(s, RAIL_PERIOD * 200)
        assertTrue(s.conduits.isComplete(Conduit.Pipe, grid.tile(5, 3)), "nothing to deconstruct")

        s = OutofspaceReducer.reduce(
            cfg,
            s,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(grid.tile(5, 3), DeleteLayer.Pipe)))),
        )
        assertNotNull(s.conduits.at(Conduit.Pipe, grid.tile(5, 3)), "the delete took the tile straight out")

        s = run(s, RAIL_PERIOD * 200)
        assertNull(s.conduits.at(Conduit.Pipe, grid.tile(5, 3)), "the marked pipe never came apart")
    }

    /**
     * ⛔ **A ghost machine must not starve the wire under it.** Found in Stu's save at (10, 19).
     *
     * One address serves one appetite a step, and the plan's reason the loser is not starved by that
     * is that the winner *stops being a ghost when it finishes*. A Sensor is titanium and a wire is
     * copper, so a Sensor ghost standing on a wire ghost finishes from nothing the wire is fed with:
     * it took the tile's turn, refused the copper, and the copper could not move on either, because
     * the tile it stood on was the sink it had been routed to. One packet, one wire, for ever.
     *
     * ⚠️ The two sites are stated on the same tile deliberately — that is the arrangement a player
     * makes by drawing a wire under an instrument, which is the only way to wire one at all.
     */
    @Test
    fun `a wire ghost is fed past the ghost machine standing on it`() {
        var s = tankAndWiredRunUnderSensor()
        assertEquals(0L, wireMass(s, 5), "the wire started with metal in it")

        s = run(s, RAIL_PERIOD * 200)

        assertEquals(
            conduitBillOfMaterials(Conduit.Signal, materialBefore(Conduit.Signal)).total,
            wireMass(s, 5),
            "the wire under the Sensor ghost never finished",
        )
        // ⚠️ And the Sensor is still a ghost: it is titanium, and nothing here is. The wire being
        // fed must not have been the machine quietly eating copper it cannot be made of.
        assertEquals(0L, deckMass(s, 5), "the Sensor ghost took copper into its casing")
    }

    /**
     * ⚠️ **A marked pipe with no road out waits, and stays waiting.**
     *
     * Copper leaves on the rail network, so a pipe with no track on its tile has nowhere to put what
     * it is made of. The refusal has to be the reversible wait a rail already has — not a vanishing
     * act, which would destroy the metal, and not a crash.
     */
    @Test
    fun `a marked pipe with no rail under it waits rather than vanishing`() {
        var s = tankAndPipedRun(railUnder = false)
        // State it finished, since nothing can build it without track to deliver on.
        for (x in 4..7) s.conduits.tracks.lay(Conduit.Pipe, grid.tile(x, 3), materialBefore(Conduit.Pipe))
        val before = pipeMass(s, 5)
        assertTrue(before > 0L, "the fixture did not fill the pipe")

        s = OutofspaceReducer.reduce(
            cfg,
            s,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(grid.tile(5, 3), DeleteLayer.Pipe)))),
        )
        s = run(s, RAIL_PERIOD * 100)

        assertNotNull(s.conduits.at(Conduit.Pipe, grid.tile(5, 3)), "the pipe went without handing anything back")
        assertEquals(before, pipeMass(s, 5), "the copper left by a road that does not exist")
    }

    private fun wireMass(s: VesselState, x: Int): Long = s.conduits.massAt(Conduit.Signal, s.grid.tile(x, 3))

    private fun deckMass(s: VesselState, x: Int): Long = s.deck.stuff.massAt(s.grid.tile(x, 3))

    /**
     * The piped run again, with a **wire** rather than a pipe and a **Sensor ghost** standing on one
     * tile of it — a player wiring an instrument, which is the arrangement that found the bug.
     *
     * The Sensor is stated as a ghost the way `MachineGhostTest` states its own, rather than placed:
     * a fixture says what the world is.
     */
    private fun tankAndWiredRunUnderSensor(): VesselState {
        val deck = DeckArray(grid)
        deck += fixtureStorage(grid.tile(3, 3), Direction.Right)
        deck += fixtureStorage(grid.tile(9, 3), Direction.Right)
        deck.standGhost(fixtureSensor(grid.tile(5, 3), Direction.Right))
        val rails = arrayOfNulls<Segment>(grid.size)
        joinRow(grid, rails, 4, 8, 3)
        val wires = arrayOfNulls<Segment>(grid.size)
        for (x in 4..7) {
            var links = 0
            if (x > 4) links = links or (1 shl Direction.Left.ordinal)
            if (x < 7) links = links or (1 shl Direction.Right.ordinal)
            wires[grid.tile(x, 3).index] = Segment(Conduit.Signal, links = links, material = materialBefore(Conduit.Signal))
        }
        val s = VesselState(
            grid,
            deck,
            conduits = Conduits.of(
                grid.size,
                Conduit.Rail to rails.toList(),
                Conduit.Signal to wires.toList(),
            ),
            buffers = BufferLayer.forDeck(grid, deck),
            rail = RailLayer.empty(grid.size),
        ).copy(creative = false).stocked(
            grid.tile(3, 3),
            Mixture.of(Species.Copper to 12 * Capacity.PACKET_MASS, energy = 0),
        )
        for (x in 4..7) s.conduits.tracks[Conduit.Signal].release(grid.tile(x, 3))
        return s
    }
}
