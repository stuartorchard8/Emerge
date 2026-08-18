package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.machine.DeckMachineKind
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.world.BufferLayer
import org.emerge.demo.outofspace.OutofspaceReducer.FLUID_PERIOD
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.PIPE_VOLUME
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.VolumeField
import org.emerge.demo.outofspace.world.heatCapacityAt
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The valve: the one place gas crosses between the rooms and the plumbing.
 *
 * The pipe layer has been sealed since it was built, which made its ledger easy — nothing crossed, so
 * nothing could be lost crossing. This is the increment that opens it, and the ledger is now doing
 * real work: every test here that runs the world checks it, because a transfer between two fields is
 * exactly where a gram goes missing.
 */
class ValveTest {

    private val grid = Grid(20, 12)
    private val cfg = OutofspaceConfig(initialGrid = grid)

    /** A sealed hull, so room air stays put and the only way out of anywhere is the valve. */
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

    private fun edit(state: VesselState, vararg edits: Edit): VesselState =
        OutofspaceReducer.reduce(cfg, state, mapOf(PlayerId(0) to OutofspaceInput(edits.toList())))

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    private fun pipeRun(state: VesselState, y: Int, fromX: Int, toX: Int): VesselState {
        var s = state
        for (x in fromX until toX) s = edit(s, Edit.Lay(grid.tile(x, y), grid.tile(x + 1, y), Conduit.Pipe))
        return s
    }

    private fun valveAt(state: VesselState, tile: TileIndex): VesselState =
        edit(state, Edit.Place(tile, Brush.Building(DeckMachineKind.Valve), Direction.Right))

    /** Whether the pipe at [tile] is open to the room — a valve standing on a length of pipe. */
    private fun isOpen(s: VesselState, tile: TileIndex): Boolean =
        s.deck[tile] is Valve && s.conduits.at(Conduit.Pipe, tile) != null

    private fun pipeMass(s: VesselState, tile: TileIndex): Long {
        var sum = 0L
        for (sp in Species.ALL) sum += s.pipeAir.massOf(tile, sp)
        return sum
    }

    private fun roomMass(s: VesselState, tile: TileIndex): Long {
        var sum = 0L
        for (sp in Species.ALL) sum += s.air.massOf(tile, sp)
        return sum
    }

    /**
     * The ledgers that span both fields. Every run in this file has to leave both closed.
     *
     * Both, and the energy one is the reason this is a helper rather than a line. It was missing when
     * the valve was first built — the mass side had summed both fields since the pipes existed, and
     * the heat side was still reading the rooms alone, which no test could catch while the pipe layer
     * was sealed. It went wrong the instant a joule crossed.
     *
     * ⚠️ The energy half is **PARKED** for the unit rescale — see [EnergyLedgers]. The mass half is
     * not, and is what actually guards this file for the moment.
     */
    private fun assertBalanced(s: VesselState, what: String) {
        assertEquals(
            s.baselineAirMass,
            s.atmosphereMass + s.airVentedMass,
            "$what: rooms plus pipes plus vented no longer accounts for the air the world started with",
        )
        EnergyLedgers.assertAirBalanced(s, what)
    }

    /** A pipe run across the middle of a pressurised hull, with one valve on it. */
    private fun plumbed(valveX: Int = 6, y: Int = 6): VesselState {
        var s = VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size))
        s = pipeRun(s, y, 4, 15)
        return valveAt(s, grid.tile(valveX, y))
    }

    @Test
    fun `a valve lets the room fill the pipe it opens onto`() {
        val start = plumbed()
        val postFluidTick = run(start, FLUID_PERIOD)
        // Not zero: placing the valve is an edit, and an edit runs a tick, so one tick's worth has
        // already crossed by the time the fixture hands the world back. That is the valve working.
        val first = postFluidTick.pipeAir.totalMass
        assertTrue(first > 0L, "nothing crossed the valve on the tick it was opened")

        val after = run(start, 200*FLUID_PERIOD)

        assertTrue(
            after.pipeAir.totalMass > first * 4,
            "the pipe took ${first}g on the first tick and held ${after.pipeAir.totalMass}g after " +
                "two hundred more — gas crossed once and then stopped",
        )
        assertTrue(pipeMass(after, grid.tile(13, 6)) > 0L, "gas crossed but never ran along the pipe")
        assertBalanced(after, "filling a pipe from a room")
    }

    /**
     * The equilibrium is equal **pressure**, and a pipe cell is an eighth of a tile — so a settled
     * network holds about an eighth of what the room beside it holds, not the same amount.
     *
     * This is the assertion that would catch the whole exchange being written as a mass relaxation,
     * which is the obvious wrong version: it would keep moving gas until the two cells held the same
     * mass, emptying a room into a network of far smaller cells. Checked as a ratio against
     * [PIPE_VOLUME] rather than against a measured number, so the tuning dial can move without
     * re-baselining the physics.
     */
    @Test
    fun `gas stops crossing when the pressures match, not when the masses do`() {
        val after = run(plumbed(), 400)

        val roomTile = grid.tile(6, 5)
        val pipeTile = grid.tile(6, 6)
        val room = roomMass(after, roomTile)
        val pipe = pipeMass(after, pipeTile)
        assertTrue(pipe > 0L && room > 0L, "one side of the valve ended up empty")

        // Same gas, same temperature, so equal pressure means mass in proportion to volume.
        val expected = room * PIPE_VOLUME / VolumeField.FULL
        assertTrue(
            pipe in (expected * 6 / 10)..(expected * 16 / 10),
            "a pipe cell settled at ${pipe}g beside a room cell's ${room}g; equal pressure in an " +
                "eighth of a tile wants about ${expected}g",
        )
    }

    /**
     * The valve brush on bare deck lays the pipe it needs, rather than doing nothing or failing.
     *
     * A valve is a property of a length of pipe, so there is no such thing as a valve without one —
     * which is the main thing being a segment rather than a machine bought. The brush therefore has
     * two jobs: open a run that is already there, and lay an open tile where there is none.
     */
    @Test
    fun `a valve opens the pipe it stands on, and nothing on bare deck`() {
        // ⚠️ The brush used to lay its own pipe, because the valve *was* the pipe. It is a building
        // over a run now: on bare deck it stands there opening nothing, exactly as a sensor with no
        // wire under it drives nothing.
        val bare = valveAt(VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size)), grid.tile(6, 6))
        assertTrue(bare.deck[grid.tile(6, 6)] is Valve, "the valve was not placed at all")
        assertEquals(null, bare.conduits.at(Conduit.Pipe, grid.tile(6, 6)), "the brush laid pipe of its own")
        assertTrue(!isOpen(bare, grid.tile(6, 6)), "a valve on bare deck opened something")

        var run = pipeRun(VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size)), 6, 4, 15)
        val before = run.conduits.at(Conduit.Pipe, grid.tile(6, 6))!!
        assertTrue(!isOpen(run, grid.tile(6, 6)), "the fixture laid a run that was already open")
        run = valveAt(run, grid.tile(6, 6))
        assertTrue(isOpen(run, grid.tile(6, 6)), "the valve did not open the pipe under it")
        val after = run.conduits.at(Conduit.Pipe, grid.tile(6, 6))!!
        assertEquals(before.links, after.links, "placing a valve tore up the run it stands on")
    }

    /**
     * Which lengths of pipe are open has to survive a save, or a reloaded vessel is sealed plumbing
     * full of gas that can never get back out.
     */
    @Test
    fun `a valve is still a valve after a save`() {
        val after = run(plumbed(), 50)
        val back = org.emerge.demo.outofspace.world.Save.read(org.emerge.demo.outofspace.world.Save.write(after))

        val tile = grid.tile(6, 6)
        assertTrue(isOpen(back, tile), "the valve came back sealed")
        assertTrue(!isOpen(back, grid.tile(7, 6)), "an ordinary length of pipe came back open")
        assertEquals(after.pipeAir, back.pipeAir, "the pipes came back holding something else")
    }

    @Test
    fun `a sealed pipe run with no valve on it still takes nothing`() {
        val s = pipeRun(VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size)), 6, 4, 15)
        val after = run(s, 200)

        assertEquals(0L, after.pipeAir.totalMass, "gas got into a pipe with no way in")
        assertBalanced(after, "a pipe with no valve")
    }

    /**
     * What crosses is a share of a well-mixed cell, so it arrives with the room's composition rather
     * than sorted.
     *
     * The trap this guards is a transfer written species by species against each species' own
     * pressure, which is a defensible-sounding thing to write and would make a valve a **separator** —
     * every valve in the ship quietly enriching its pipe in whichever gas happened to be lightest.
     */
    @Test
    fun `what crosses has the composition of what it left`() {
        val after = run(plumbed(), 300)

        val roomTile = grid.tile(6, 5)
        val pipeTile = grid.tile(6, 6)
        for (sp in Species.ALL) {
            val room = after.air.massOf(roomTile, sp)
            val pipe = after.pipeAir.massOf(pipeTile, sp)
            if (room == 0L) {
                assertEquals(0L, pipe, "$sp appeared in the pipe having never been in the room")
                continue
            }
            // Per species, in the same volume proportion the mixture as a whole settles at.
            val expected = room * PIPE_VOLUME / VolumeField.FULL
            assertTrue(
                pipe in (expected * 5 / 10)..(expected * 18 / 10),
                "$sp settled at ${pipe}g in the pipe against ${room}g in the room, which is not the " +
                    "share the rest of the mixture took — the valve is sorting the gas",
            )
        }
    }

    /** Heat rides across on the gas: a hot room warms the pipe it is filling. */
    @Test
    fun `gas carries its heat through the valve`() {
        var s = plumbed()
        // Heat the whole room's air by half again. Done to the field rather than with a furnace, so
        // the test measures the valve and not the smelter.
        val energy = s.air.copyEnergy()
        for (i in 0 until energy.size) energy[TileIndex(i)] = energy[TileIndex(i)] * 3 / 2
        val warmed = Stuff.from(s.air.copyMass(), energy)
        // The baseline moves by what the fixture ADDED, rather than being restated from the room
        // field. Restating it is the obvious version and it is wrong twice over: it drops the energy
        // already in the pipes, and it discards the `solidToAirEnergy` the world has booked so far.
        // Writing it as a delta cannot make either mistake.
        s = s.copy(
            air = warmed,
            baselineAirEnergy = s.baselineAirEnergy + (warmed.totalEnergy - s.air.totalEnergy),
        )

        val after = run(s, 200)
        val pipeTile = grid.tile(6, 6)
        val capacity = heatCapacityAt(after.pipeAir.copyMass(), pipeTile)
        assertTrue(capacity > 0L, "no gas reached the pipe, so there is no temperature to read")

        val kelvin = (after.pipeAir.copyEnergy()[pipeTile] / capacity).toInt()
        assertTrue(
            kelvin > Temperature.AMBIENT_KELVIN + 10,
            "the pipe filled from a hot room and came out at ${kelvin}K — the gas crossed without " +
                "its energy, which mints cold gas and destroys energy",
        )
        assertBalanced(after, "a hot room filling a pipe")
    }

    /**
     * Mirror the plumbing about a column and the result must mirror with it.
     *
     * This detector has now caught three separate in-place sweeps being accidentally Gauss-Seidel —
     * `applySpeciesDrift`, the temperature clamp, and the thermal update — each of which conserved
     * its total perfectly and so could not be caught by any ledger. The valve exchange is another
     * in-place sweep over tiles in index order, and it edits **four** arrays that later passes read,
     * so it is exactly the shape of the bug.
     *
     * It also settles, by measurement rather than by argument, the question left open when the second
     * field was built: whether the exchange runs before or after the projection. It runs before, and
     * a left-right bias is what running it in the wrong place would look like.
     */
    @Test
    fun `a mirrored pair of valves fills a mirrored pair of pipes the same`() {
        val axis = grid.width / 2
        val y = 6
        var s = VesselState(grid, hulled(), buffers = BufferLayer.empty(grid.size), rail = RailLayer.empty(grid.size))
        // Two separate runs, one either side of the axis, each with its own valve at the same
        // distance out. Midships rather than at the bow: the rim is where transport asymmetries
        // live, and this test is about the exchange rather than about the boundary.
        s = pipeRun(s, y, axis - 6, axis - 2)
        s = pipeRun(s, y, axis + 2, axis + 6)
        s = valveAt(s, grid.tile(axis - 4, y))
        s = valveAt(s, grid.tile(axis + 4, y))

        val after = run(s, 200)

        var left = 0L
        var right = 0L
        for (d in 2..6) {
            left += pipeMass(after, grid.tile(axis - d, y))
            right += pipeMass(after, grid.tile(axis + d, y))
        }
        assertTrue(left > 0L, "neither side filled, so the test measured nothing")

        val lean = (left - right) * 100 / (left + right)
        assertTrue(
            lean in -4..4,
            "the two sides of a mirrored vessel filled unequally: ${left}g left, ${right}g right " +
                "(${lean}% lean). An in-place sweep in index order reads tiles it has already " +
                "written and biases toward one end — see the header of this test.",
        )
        assertBalanced(after, "a mirrored pair of valves")
    }
}
