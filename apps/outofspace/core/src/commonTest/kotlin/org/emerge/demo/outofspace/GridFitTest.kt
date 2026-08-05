package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Debris
import org.emerge.demo.outofspace.world.Diverters
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fitGrid
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.MomentumField
import kotlin.test.assertEquals
import kotlin.test.Test

/** Tests for [VesselState.fitGrid]. */
class GridFitTest {

    private fun smallWorld(w: Int = 10, h: Int = 8): VesselState {
        val grid = Grid(w, h)
        val machines = arrayOfNulls<Machine>(grid.size)
        machines[grid.index(3, 3)] = Hull()
        machines[grid.index(4, 3)] = Hull()
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            rocks = emptyList(),
        )
    }

    private fun populatedWorld(w: Int = 20, h: Int = 14): VesselState {
        val grid = Grid(w, h)
        val machines = arrayOfNulls<Machine>(grid.size)
        for (x in 1 until w - 1) {
            machines[grid.index(x, 1)] = Hull()
            machines[grid.index(x, h - 2)] = Hull()
        }
        for (y in 1 until h - 1) {
            machines[grid.index(1, y)] = Hull()
            machines[grid.index(w - 2, y)] = Hull()
        }
        machines[grid.index(5, 5)] = Hull()
        machines[grid.index(10, 5)] = Hull()
        val debris = Debris.of(mapOf(
            grid.index(3, 3) to listOf(Resource(Form.IronIngot, Mixture.of(Pair(Species.Iron, 1000L))))
        ))
        val diverters = Diverters.of(mapOf(grid.index(7, 7) to 1))
        val airGrams = LongArray(grid.size * Species.COUNT) {
            val tile = it / Species.COUNT
            val species = it - tile * Species.COUNT
            if (grid.xOf(tile) in 1 until grid.width - 1 && grid.yOf(tile) in 1 until grid.height - 1)
                (tile + 1).toLong() * (1L shl (species + 1))
            else 0L
        }
        val airJoules = LongArray(grid.size) {
            val tile = it
            if (grid.xOf(tile) in 1 until grid.width - 1 && grid.yOf(tile) in 1 until grid.height - 1)
                airGrams[it * Species.COUNT] * 2L
            else 0L
        }
        val momentumX = LongArray(EdgeGrid(grid).xEdgeCount) { (it + 1).toLong() }
        val momentumY = LongArray(EdgeGrid(grid).yEdgeCount) { (it + 1).toLong() }
        val pipeMomentumX = LongArray(EdgeGrid(grid).xEdgeCount)
        val pipeMomentumY = LongArray(EdgeGrid(grid).yEdgeCount)
        val rock = Rock(width = 3, height = 2, cells = BooleanArray(6) { true },
            positionX = 3 * Flight.PER_TILE, positionY = 5 * Flight.PER_TILE,
            impulseX = 100, impulseY = 200, joules = 0,
            composition = Mixture.of(Pair(Species.Iron, 300)),
        )
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            debris = debris,
            diverters = diverters,
            air = AirField.of(airGrams, airJoules),
            pipeAir = AirField.of(airGrams.copyOf()),
            momentum = MomentumField.of(
                EdgeGrid(grid), momentumX, momentumY),
            pipeMomentum = MomentumField.of(
                EdgeGrid(grid), pipeMomentumX, pipeMomentumY),
            rocks = listOf(rock),
        )
    }

    @Test
    fun `fit is idempotent`() {
        val s0 = smallWorld()
        val s1 = s0.fitGrid(4)
        val s2 = s1.fitGrid(4)
        assertEquals(s1.grid, s2.grid, "fit should be idempotent")
    }

    @Test
    fun `empty vessel returns itself unchanged`() {
        val grid = Grid(20, 10)
        val s = VesselState(
            grid = grid,
            machines = List(grid.size) { null as Machine? },
            rocks = emptyList(),
        )
        val fitted = s.fitGrid(4)
        assertEquals(s, fitted, "empty vessel should return itself")
    }

    @Test
    fun `fit shrinks grid around hull`() {
        val s0 = smallWorld()
        val s1 = s0.fitGrid(4)
        assertEquals(9, s1.grid.width, "fitted width")
        assertEquals(8, s1.grid.height, "fitted height")
    }

    @Test
    fun `machines land at correct positions after fit`() {
        val s0 = smallWorld()
        val s1 = s0.fitGrid(4)
        val h0 = s0.machines[s0.grid.index(3, 3)]
        val tile33 = s1.grid.index(3, 3)
        assertEquals(h0?.kind, s1.machines[tile33]?.kind, "hull at (3,3)")
    }

    @Test
    fun `airBalance preserved across fit`() {
        val s0 = populatedWorld()
        val s1 = s0.fitGrid(4)
        assertEquals(s0.airBalance, s1.airBalance, "airBalance must be preserved")
        assertEquals(s0.airJouleBalance, s1.airJouleBalance, "airJouleBalance must be preserved")
    }

    @Test
    fun `massBalance preserved across fit`() {
        val s0 = populatedWorld()
        val s1 = s0.fitGrid(4)
        fun massBalance(s: VesselState) = s.massGrams + s.ventedGrams + s.extractedGrams
        assertEquals(massBalance(s0), massBalance(s1), "massBalance must be preserved")
    }

    @Test
    fun `momentumBalance preserved across fit`() {
        val s0 = populatedWorld()
        val s1 = s0.fitGrid(4)
        fun momentumX(s: VesselState) =
            s.vesselImpulseX + s.momentum.totalX + s.pipeMomentum.totalX +
                    s.exhaustMomentumX + s.undeliveredImpulseX + s.debugImpulseX
        fun momentumY(s: VesselState) =
            s.vesselImpulseY + s.momentum.totalY + s.pipeMomentum.totalY +
                    s.exhaustMomentumY + s.undeliveredImpulseY + s.debugImpulseY
        assertEquals(momentumX(s0), momentumX(s1), "momentumBalanceX must be preserved")
        assertEquals(momentumY(s0), momentumY(s1), "momentumBalanceY must be preserved")
    }

    @Test
    fun `heatBalance preserved across fit`() {
        val s0 = populatedWorld()
        val s1 = s0.fitGrid(4)
        fun heatBalance(s: VesselState) =
            s.baselineJoules + s.radiatedJoules + s.solidToAirJoules +
                    s.baselineAirJoules + s.baselineAirGrams * 2L +
                    s.airVentedJoules + s.generatedJoules - s.constructionJoules
        assertEquals(heatBalance(s0), heatBalance(s1), "heatBalance must be preserved")
    }
}
