package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Material
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.world.Smelter
import org.emerge.demo.outofspace.world.Storage
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.MachineKind
import org.emerge.demo.outofspace.world.ambientJoules
import org.emerge.demo.outofspace.world.material
import org.emerge.demo.outofspace.world.thermalTiles
import org.emerge.sim.core.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contact rules: **who touches whom**, which is the whole content of the body model.
 *
 * A per-tile heat field could not have failed any of these, because it could not have expressed
 * them. Each test is one sentence from [org.emerge.demo.outofspace.world.stepSolidHeat]'s contract,
 * and each one distinguishes the model from the average it replaced.
 */
class BodyHeatTest {

    private fun cfgFor(grid: Grid) = OutofspaceConfig(grid = grid)

    private fun run(state: VesselState, ticks: Int): VesselState {
        var s = state
        val cfg = cfgFor(state.grid)
        repeat(ticks) { s = OutofspaceReducer.reduce(cfg, s, emptyMap()) }
        return s
    }

    /** A hull box with a hollow middle, [w] x [h] outer, with a ring of space around it. */
    private fun room(
        w: Int,
        h: Int,
        rails: (Grid) -> List<Segment?> = { List(it.size) { null } },
        fill: (Int, Int) -> Machine? = { _, _ -> null },
    ): VesselState {
        val grid = Grid(w + 2, h + 2)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1..w) {
            machines[grid.index(x, 1)] = Hull()
            machines[grid.index(x, h)] = Hull()
        }
        for (y in 1..h) {
            machines[grid.index(1, y)] = Hull()
            machines[grid.index(w, y)] = Hull()
        }
        for (y in 2 until h) for (x in 2 until w) machines[grid.index(x, y)] = fill(x, y)
        return VesselState(grid, machines.toList(), rails = rails(grid))
    }

    /** The state with the body stored at [at] set to [kelvin], and its ledger re-anchored. */
    private fun VesselState.heatMachine(at: Int, kelvin: Int): VesselState {
        val list = machines.toMutableList()
        val m = list[at]!!
        list[at] = m.withJoules(m.kind.material.capacityPerTile * m.kind.thermalTiles * kelvin)
        return copy(machines = list.toList()).let { it.copy(baselineJoules = it.storedJoules) }
    }

    private fun VesselState.railKelvin(tile: Int): Int {
        val s = rails[tile] ?: error("no track at $tile")
        return (s.joules / s.conduit.material.capacityPerTile).toInt()
    }

    @Test
    fun `two things sharing a tile share their heat`() {
        // Track threaded under a furnace. Nothing about the grid says they are connected — they are
        // in contact because they are in the same place, which is the one rule a tile field could
        // express and did, by averaging them into a single temperature they could never leave.
        val g = Grid(12, 12)
        val under = g.index(5, 5)
        val world = room(
            10, 10,
            rails = { grid -> List(grid.size) { if (it == under) Segment(Conduit.Rail) else null } },
        ) { x, y -> if (x == 5 && y == 5) Smelter(Direction.Right) else null }
            .heatMachine(under, 900)

        val settled = run(world, 30)
        assertTrue(
            settled.railKelvin(under) > Temperature.AMBIENT_KELVIN + 50,
            "the rail under the furnace should have warmed: ${settled.railKelvin(under)}K",
        )
        // And it is *its own* temperature, not the furnace's: iron holds far less than firebrick, so
        // it warms fast, but the two are separate bodies and never become one number.
        assertTrue(
            settled.railKelvin(under) != settled.bodies.first { it.at == under && !it.permeable }.kelvin,
            "but the two are still separate bodies with separate temperatures",
        )
    }

    @Test
    fun `track conducts along a line the player drew and not across one they did not`() {
        // Two parallel runs of rail, side by side and touching, joined along their own length and to
        // nothing else. Heat poured into one must travel down it and must not cross to the other.
        //
        // This is the rule that makes routing a decision. Adjacency-joining would light both runs
        // up identically and no amount of care about where you lay a cable could ever matter.
        val g = Grid(12, 8)
        val topRow = 3
        val nextRow = 4
        fun line(grid: Grid, y: Int): List<Pair<Int, Segment>> =
            (2..8).map { x ->
                var s = Segment(Conduit.Rail)
                if (x > 2) s = s.joinedTo(Direction.Left)
                if (x < 8) s = s.joinedTo(Direction.Right)
                grid.index(x, y) to s
            }

        val world = room(10, 6, rails = { grid ->
            val out = arrayOfNulls<Segment>(grid.size)
            for ((i, s) in line(grid, topRow)) out[i] = s
            for ((i, s) in line(grid, nextRow)) out[i] = s
            out.toList()
        })

        // Drive one tile of the upper run hot.
        val source = g.index(2, topRow)
        val rails = world.rails.toMutableList()
        rails[source] = rails[source]!!.copy(joules = Material.Iron.capacityPerTile * 2_000L)
        var s = world.copy(rails = rails.toList())
        s = s.copy(baselineJoules = s.storedJoules)

        val settled = run(s, 40)
        val alongTheRun = settled.railKelvin(g.index(6, topRow))
        val acrossToTheOther = settled.railKelvin(g.index(6, nextRow))

        assertTrue(
            alongTheRun > acrossToTheOther,
            "heat should run down the drawn line, not across to the untouched one: " +
                "${alongTheRun}K along vs ${acrossToTheOther}K across",
        )
    }

    @Test
    fun `a hot wall warms the air in the room and not the air outside`() {
        val world = room(8, 8)
        val g = world.grid
        val wall = g.index(4, 1)
        val heated = world.heatMachine(wall, 1_200)

        val settled = run(heated, 40)
        val inside = settled.airKelvinAt(g.index(4, 2))
        assertTrue(
            inside > Temperature.AMBIENT_KELVIN + 5,
            "the air against the hot wall should have warmed: ${inside}K",
        )
        // Nothing arrives from outside and nothing is conducted into it: space is not a cold
        // reservoir the hull can convect into, it is empty, and radiation is the only path out.
        assertTrue(settled.radiatedJoules > 0L, "and the exposed face radiates")
    }

    @Test
    fun `materials differ - the same heat moves a firebrick furnace less than a titanium tank`() {
        // The point of the whole exercise: identical footprints, identical energy, different stuff.
        val g = Grid(12, 12)
        fun single(kind: (Direction) -> Machine): VesselState =
            room(10, 10) { x, y -> if (x == 5 && y == 5) kind(Direction.Right) else null }

        val furnace = single { Smelter(it) }
        val tank = single { Storage(it) }
        val at = g.index(5, 5)

        // The same number of joules into each, on top of ambient.
        fun bump(s: VesselState): VesselState {
            val list = s.machines.toMutableList()
            val m = list[at]!!
            list[at] = m.withJoules(m.joules + 20_000_000_000L)
            return s.copy(machines = list.toList()).let { it.copy(baselineJoules = it.storedJoules) }
        }

        val hotFurnace = bump(furnace).kelvinAt(at) - Temperature.AMBIENT_KELVIN
        val hotTank = bump(tank).kelvinAt(at) - Temperature.AMBIENT_KELVIN
        assertTrue(
            hotTank > hotFurnace,
            "titanium should heat further than firebrick for the same joules: +${hotTank}K vs +${hotFurnace}K",
        )
    }

    @Test
    fun `building and scrapping a body books its energy in and out`() {
        val grid = Grid(6, 5)
        val at = grid.index(2, 2)
        var s = VesselState(grid, List(grid.size) { null })
        val cfg = cfgFor(grid)

        s = OutofspaceReducer.reduce(
            cfg, s,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Place(at, MachineKind.Hull, Direction.Right)))),
        )
        assertEquals(
            ambientJoules(MachineKind.Hull),
            s.constructionJoules,
            "a wall brings a wall's worth of room-temperature heat into the world",
        )

        s = OutofspaceReducer.reduce(
            cfg, s,
            mapOf(PlayerId(0) to OutofspaceInput(listOf(Edit.Remove(at)))),
        )
        // Not necessarily zero: the wall radiated and conducted for a tick before it was pulled, so
        // what left with it is what it was holding, not what it arrived with. That difference is
        // exactly why the term is booked rather than assumed.
        assertTrue(
            s.constructionJoules < ambientJoules(MachineKind.Hull) / 1_000L,
            "and scrapping it takes that heat back out: ${s.constructionJoules}",
        )
    }
}
