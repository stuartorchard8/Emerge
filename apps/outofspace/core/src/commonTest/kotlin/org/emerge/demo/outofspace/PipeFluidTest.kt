package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.PIPE_VOLUME
import org.emerge.demo.outofspace.world.fluid.VolumeField
import org.emerge.demo.outofspace.world.fluid.pipeApertures
import org.emerge.demo.outofspace.world.fluid.pipeVolumes
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pipes as a second body of fluid: filled by hand, run by the ordinary solver, and accounted for
 * in the same ledger as the air in the rooms.
 *
 * The single most important thing here is the ledger, and it is the one place the pipe layer breaks
 * the project's own rule that things which do not interconvert get separate ledgers. Room gas and
 * pipe gas *do* interconvert — that is what a vent will be — so they share one baseline. Two
 * baselines would disagree the first time a gram crossed, and the disagreement would be
 * indistinguishable from a leak.
 */
class PipeFluidTest {

    private val grid = Grid(16, 10)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** A sealed hull, so the room air has nowhere to go and cannot muddy the ledger. */
    private fun hulled(): List<Machine?> {
        val m = arrayOfNulls<Machine>(grid.size)
        for (x in 0 until grid.width) {
            m[grid.index(x, 0)] = Hull()
            m[grid.index(x, grid.height - 1)] = Hull()
        }
        for (y in 0 until grid.height) {
            m[grid.index(0, y)] = Hull()
            m[grid.index(grid.width - 1, y)] = Hull()
        }
        return m.toList()
    }

    private fun lay(state: VesselState, from: Int, to: Int): VesselState =
        OutofspaceReducer.reduce(
            cfg, state, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Lay(from, to, Conduit.Pipe)))),
        )

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** A pipe run along row [y] from [fromX] to [toX], with gas put into its first cell. */
    private fun charged(y: Int = 4, fromX: Int = 3, toX: Int = 11, grams: Long = 400L): VesselState {
        var s = VesselState(grid, hulled())
        for (x in fromX until toX) s = lay(s, grid.index(x, y), grid.index(x + 1, y))

        val head = grid.index(fromX, y)
        val pipe = s.pipeAir.copyGrams()
        pipe[head * Species.COUNT + Species.Nitrogen.ordinal] = grams
        return s.copy(
            pipeAir = AirField.of(pipe),
            // Charging by hand puts gas into the world, so the baseline has to move with it or the
            // ledger reads the fixture itself as a leak.
            baselineAirGrams = s.baselineAirGrams + grams,
            baselineAirJoules = AirField.of(pipe).totalJoules + s.air.totalJoules,
        )
    }

    private fun pipeMass(s: VesselState, tile: Int): Long {
        var sum = 0L
        for (sp in Species.GASES) sum += s.pipeAir.gramsOf(tile, sp)
        return sum
    }

    @Test
    fun `gas put into a pipe spreads along the run and stays in it`() {
        val start = charged()
        val after = run(start, 200)

        assertTrue(pipeMass(after, grid.index(10, 4)) > 0L, "nothing reached the far end of the run")
        assertEquals(
            start.pipeAir.totalGrams,
            after.pipeAir.totalGrams,
            "the pipes gained or lost gas with nothing connected to them",
        )
        assertEquals(0L, after.airVentedGrams, "a sealed pipe network vented")
    }

    @Test
    fun `the air ledger spans both fields`() {
        val after = run(charged(), 200)
        assertEquals(
            after.baselineAirGrams,
            after.atmosphereGrams + after.airVentedGrams,
            "rooms plus pipes plus vented no longer accounts for the air the world started with",
        )
    }

    @Test
    fun `a pipe cannot exchange gas with the room it runs through`() {
        val start = charged()
        val roomBefore = start.air.totalGrams
        val after = run(start, 200)

        assertEquals(roomBefore, after.air.totalGrams, "gas crossed between the layers with no vent")
        assertEquals(start.pipeAir.totalGrams, after.pipeAir.totalGrams, "the pipes leaked into the room")
    }

    /**
     * A pipe cell is a fraction of a tile, and the field that says so is what the solver is handed.
     *
     * Written against the derivation rather than against a pressure reading, because the obvious
     * version — scale the pipe's pressure up by the volume and check it beats a roomful — asserts
     * only the arithmetic the test itself just did. What is worth pinning is that a laid pipe gets
     * [PIPE_VOLUME] and everything else gets a whole tile, since a field of [VolumeField.FULL]
     * everywhere would leave the pipes behaving exactly like corridors and nothing would fail.
     */
    @Test
    fun `a laid pipe gets a fraction of a tile and everything else gets a whole one`() {
        val s = charged()
        val volumes = pipeVolumes(grid, s.conduits)

        assertEquals(PIPE_VOLUME, volumes.at(grid.index(3, 4)), "the head of the run is not narrow")
        assertEquals(PIPE_VOLUME, volumes.at(grid.index(11, 4)), "the far end of the run is not narrow")
        assertTrue(PIPE_VOLUME < VolumeField.FULL, "a pipe cell is not actually smaller than a tile")
        assertEquals(VolumeField.FULL, volumes.at(grid.index(3, 7)), "a tile with no pipe was narrowed")
    }

    @Test
    fun `no pipe means no open face anywhere`() {
        val bare = VesselState(grid, hulled())
        val edges = EdgeGrid(grid)
        val apertures = pipeApertures(edges, bare.conduits)

        for (e in 0 until edges.xEdgeCount) assertTrue(!apertures.isXOpen(e), "x face $e open with no pipe")
        for (e in 0 until edges.yEdgeCount) assertTrue(!apertures.isYOpen(e), "y face $e open with no pipe")
    }

    @Test
    fun `pipes laid side by side without being drawn together stay separate`() {
        var s = VesselState(grid, hulled())
        // Two parallel runs one tile apart, each drawn on its own.
        for (x in 3 until 8) s = lay(s, grid.index(x, 4), grid.index(x + 1, 4))
        for (x in 3 until 8) s = lay(s, grid.index(x, 5), grid.index(x + 1, 5))

        val pipe = s.pipeAir.copyGrams()
        pipe[grid.index(3, 4) * Species.COUNT + Species.Nitrogen.ordinal] = 400L
        s = s.copy(
            pipeAir = AirField.of(pipe),
            baselineAirGrams = s.baselineAirGrams + 400L,
            baselineAirJoules = AirField.of(pipe).totalJoules + s.air.totalJoules,
        )

        val after = run(s, 200)

        var lower = 0L
        for (x in 3..8) lower += pipeMass(after, grid.index(x, 5))
        assertEquals(0L, lower, "gas crossed into a run the player never drew a join to")
    }

    @Test
    fun `cutting a pipe lets what was in it out into the room`() {
        val start = charged()
        val head = grid.index(3, 4)
        val roomBefore = start.air.totalGrams
        val inPipe = pipeMass(start, head)
        assertTrue(inPipe > 0L, "the fixture put nothing in the pipe")

        val after = OutofspaceReducer.reduce(
            cfg, start, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(head)))),
        )

        assertEquals(0L, pipeMass(after, head), "the removed cell kept its gas")
        assertTrue(after.air.totalGrams > roomBefore, "the gas did not arrive in the room")
        assertEquals(
            after.baselineAirGrams,
            after.atmosphereGrams + after.airVentedGrams,
            "cutting a pipe minted or destroyed gas",
        )
    }

    @Test
    fun `what is in the pipes survives a save`() {
        val after = run(charged(), 50)
        val back = Save.read(Save.write(after))

        assertEquals(after.pipeAir, back.pipeAir, "the pipes came back holding something else")
        assertEquals(after.pipeMomentum, back.pipeMomentum, "the pipes came back becalmed")
        assertEquals(after.baselineAirGrams, back.baselineAirGrams, "the shared baseline did not survive")
    }
}
