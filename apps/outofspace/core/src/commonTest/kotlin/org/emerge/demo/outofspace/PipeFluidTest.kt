package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.Save
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.PIPE_VOLUME
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VolumeField
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.pipeApertures
import org.emerge.demo.outofspace.world.pipeVolumes
import org.emerge.sim.core.PlayerId
import org.emerge.demo.outofspace.OutofspaceReducer.FLUID_OFFSET
import org.emerge.demo.outofspace.OutofspaceReducer.FLUID_PERIOD
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
    private fun hulled(): DeckArray {
        val deck = DeckArray(grid)
        for (x in 0 until grid.width) {
            deck += Hull(grid.tile(x, 0))
            deck += Hull(grid.tile(x, grid.height - 1))
        }
        for (y in 1 until grid.height - 1) {
            deck += Hull(grid.tile(0, y))
            deck += Hull(grid.tile(grid.width - 1, y))
        }
        return deck
    }

    private fun lay(state: VesselState, from: TileIndex, to: TileIndex): VesselState =
        OutofspaceReducer.reduce(
            cfg, state, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Lay(from, to, Conduit.Pipe)))),
        )

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** A pipe run along row [y] from [fromX] to [toX], with gas put into its first cell. */
    private fun charged(y: Int = 4, fromX: Int = 3, toX: Int = 11, mass: Long = 400L): VesselState {
        var s = VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size), creative=true)
        for (x in fromX until toX) s = lay(s, grid.tile(x, y), grid.tile(x + 1, y))

        val tile = grid.tile(fromX, y)
        val pipe = s.pipeAir.copyMass()
        pipe[MassIndex(tile, Fluid.Nitrogen)] = mass
        return s.copy(
            pipeAir = Stuff.gas(pipe),
            // Charging by hand puts gas into the world, so the baseline has to move with it or the
            // ledger reads the fixture itself as a leak.
            baselineAirMass = s.baselineAirMass + mass,
            baselineAirEnergy = Stuff.gas(pipe).totalEnergy + s.air.totalEnergy,
        )
    }

    private fun pipeMass(s: VesselState, tile: TileIndex): Long {
        var sum = 0L
        for (sp in Species.ALL) sum += s.pipeAir.massOf(tile, sp)
        return sum
    }

    @Test
    fun `gas put into a pipe spreads along the run and stays in it`() {
        val start = charged()
        val after = run(start, 200)

        assertTrue(pipeMass(after, grid.tile(10, 4)) > 0L, "nothing reached the far end of the run")
        assertEquals(
            start.pipeAir.totalMass,
            after.pipeAir.totalMass,
            "the pipes gained or lost gas with nothing connected to them",
        )
        assertEquals(0L, after.airVentedMass, "a sealed pipe network vented")
    }

    @Test
    fun `the air ledger spans both fields`() {
        val after = run(charged(), 200)
        assertEquals(
            after.baselineAirMass,
            after.atmosphereMass + after.airVentedMass,
            "rooms plus pipes plus vented no longer accounts for the air the world started with",
        )
    }

    @Test
    fun `a pipe cannot exchange gas with the room it runs through`() {
        val start = charged()
        val roomBefore = start.air.totalMass
        val after = run(start, 200)

        assertEquals(roomBefore, after.air.totalMass, "gas crossed between the layers with no vent")
        assertEquals(start.pipeAir.totalMass, after.pipeAir.totalMass, "the pipes leaked into the room")
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

        assertEquals(PIPE_VOLUME, volumes.at(grid.tile(3, 4)), "the head of the run is not narrow")
        assertEquals(PIPE_VOLUME, volumes.at(grid.tile(11, 4)), "the far end of the run is not narrow")
        assertTrue(PIPE_VOLUME < VolumeField.FULL, "a pipe cell is not actually smaller than a tile")
        assertEquals(VolumeField.FULL, volumes.at(grid.tile(3, 7)), "a tile with no pipe was narrowed")
    }

    @Test
    fun `no pipe means no open face anywhere`() {
        val bare = VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size))
        val edges = EdgeGrid(grid)
        val apertures = pipeApertures(edges, bare.conduits)

        for (e in 0 until edges.xEdgeCount) assertTrue(!apertures.isXOpen(e), "x face $e open with no pipe")
        for (e in 0 until edges.yEdgeCount) assertTrue(!apertures.isYOpen(e), "y face $e open with no pipe")
    }

    @Test
    fun `pipes laid side by side without being drawn together stay separate`() {
        var s = VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size))
        // Two parallel runs one tile apart, each drawn on its own.
        for (x in 3 until 8) s = lay(s, grid.tile(x, 4), grid.tile(x + 1, 4))
        for (x in 3 until 8) s = lay(s, grid.tile(x, 5), grid.tile(x + 1, 5))

        val pipe = s.pipeAir.copyMass()
        pipe[MassIndex(grid.tile(3, 4), Fluid.Nitrogen)] = 400L
        s = s.copy(
            pipeAir = Stuff.gas(pipe),
            baselineAirMass = s.baselineAirMass + 400L,
            baselineAirEnergy = Stuff.gas(pipe).totalEnergy + s.air.totalEnergy,
        )

        val after = run(s, 200)

        var lower = 0L
        for (x in 3..8) lower += pipeMass(after, grid.tile(x, 5))
        assertEquals(0L, lower, "gas crossed into a run the player never drew a join to")
    }

    @Test
    fun `a pipe emptied on a tick the fluid step skips still empties`() {
        // ⛔ The pipe layer used to be written back to the state **only inside the fluid block**,
        // while the room air was written back on every tick. Anything that moved gas out of a pipe
        // on any other tick therefore put it in the room and then had the pipe's copy restored from
        // last tick's state -- the gas arrived without leaving, and the ledger says so.
        //
        // It stayed hidden because every subsystem that touches the pipes used to fire on the same
        // tick the fluid did. They no longer do (see `OutofspaceReducer`'s offsets), so this is now
        // the ordinary case rather than the awkward one; it was always a bug.
        val head = grid.tile(3, 4)
        // Every phase of the period, so this cannot pass by landing on a lucky tick -- which is
        // exactly how the test below missed it.
        for (delay in 0 until OutofspaceReducer.FLUID_PERIOD) {
            val start = run(charged(), delay)
            val roomBefore = start.air.totalMass
            val after = OutofspaceReducer.reduce(
                cfg, start, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(head)))),
            )
            assertEquals(0L, pipeMass(after, head), "the removed cell kept its gas, $delay ticks in")
            assertTrue(after.air.totalMass > roomBefore, "the gas did not arrive in the room, $delay ticks in")
            assertEquals(
                after.baselineAirMass,
                after.atmosphereMass + after.airVentedMass,
                "cutting a pipe $delay ticks in minted or destroyed gas",
            )
        }
    }

    @Test
    fun `cutting a pipe lets what was in it out into the room`() {
        val start = charged()
        val head = grid.tile(3, 4)
        val roomBefore = start.air.totalMass
        val inPipe = pipeMass(start, head)
        assertTrue(inPipe > 0L, "the fixture put nothing in the pipe")

        val after = OutofspaceReducer.reduce(
            cfg, start, mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(head)))),
        )

        assertEquals(0L, pipeMass(after, head), "the removed cell kept its gas")
        assertTrue(after.air.totalMass > roomBefore, "the gas did not arrive in the room")
        assertEquals(
            after.baselineAirMass,
            after.atmosphereMass + after.airVentedMass,
            "cutting a pipe minted or destroyed gas",
        )
    }

    @Test
    fun `what is in the pipes survives a save`() {
        val after = run(charged(), 50)
        val back = Save.read(Save.write(after))

        assertEquals(after.pipeAir, back.pipeAir, "the pipes came back holding something else")
        assertEquals(after.pipeMomentum, back.pipeMomentum, "the pipes came back becalmed")
        assertEquals(after.baselineAirMass, back.baselineAirMass, "the shared baseline did not survive")
    }

    /**
     * The stamp the air, pressure and density overlays fade against — see
     * [org.emerge.demo.outofspace.world.Cadence].
     *
     * Pinned against the schedule rather than a literal so the two cannot drift apart. Nothing in
     * the sim reads it, which is exactly why it needs a test: a stamp that quietly went wrong would
     * show up only as an overlay that fades oddly, and nothing else would ever complain.
     */
    @Test
    fun `the fluid pass stamps the tick it ran on`() {
        val s = run(charged(), FLUID_PERIOD * 4)
        assertEquals(
            FLUID_OFFSET.toLong(), s.cadences.fluid.writtenAtTick % FLUID_PERIOD,
            "fluid fires on tick $FLUID_OFFSET of its period and stamped ${s.cadences.fluid.writtenAtTick}",
        )
        assertEquals(FLUID_PERIOD, s.cadences.fluid.spanTicks, "a fade lasts until the next fluid pass")
        assertTrue(s.cadences.fluid.writtenAtTick < s.tick, "stamped in the future")
    }
}
