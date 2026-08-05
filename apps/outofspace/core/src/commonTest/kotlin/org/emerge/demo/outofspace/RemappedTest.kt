package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.AirField
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.remapped
import org.emerge.demo.outofspace.world.Debris
import org.emerge.demo.outofspace.world.Diverters
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Hull
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.Rock
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.fluid.EdgeGrid
import org.emerge.demo.outofspace.world.fluid.MomentumField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [VesselState.remapped] — the function that moves the entire world onto a different
 * grid, translated by (dx, dy) tiles.
 *
 * The plan calls this "the phase that must not be rushed — it is the only one where a bug is cheap
 * to find." Every field in VesselState must be remapped correctly, or the ledger breaks.
 */
class RemappedTest {

    private fun simpleWorld(w: Int, h: Int): VesselState {
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
        return VesselState(grid, machines.toList())
    }

    private fun populatedWorld(w: Int = 20, h: Int = 14): VesselState {
        val grid = Grid(w, h)
        val machines = arrayOfNulls<Machine>(grid.size)
        // Hull
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
        // Debris
        val debris = Debris.of(mapOf(
            grid.index(3, 3) to listOf(Resource(Form.IronIngot, Mixture.of(Species.Iron to 1000L)))
        ))
        // Diverter
        val diverters = Diverters.of(mapOf(grid.index(7, 7) to 1))
        // Air with uniform grams and joules
        val airGrams = LongArray(grid.size * Species.COUNT) {
            val tile = it / Species.COUNT
            if (tile < grid.size) 100L else 0L
        }
        val airJoules = LongArray(grid.size) { 500L }
        val air = AirField.of(airGrams, airJoules)
        // Momentum with non-zero values
        val xEdges = EdgeGrid(grid).xEdgeCount
        val yEdges = EdgeGrid(grid).yEdgeCount
        val momX = LongArray(xEdges) { 10L }
        val momY = LongArray(yEdges) { 20L }
        val momentum = MomentumField.of(EdgeGrid(grid), momX, momY)
        // Pipe air: empty
        val pipeAir = AirField.of(LongArray(grid.size * Species.COUNT) { 0L })
        val pipeMomentum = MomentumField.of(EdgeGrid(grid), LongArray(xEdges), LongArray(yEdges))
        // One rock
        val rocks = listOf(
            Rock.blob(
                radius = 2,
                positionX = 3L * Flight.PER_TILE,
                positionY = 3L * Flight.PER_TILE,
                composition = OutofspaceReducer.DEFAULT_ORE_BODY
            )
        )
        return VesselState(
            grid = grid,
            machines = machines.toList(),
            debris = debris,
            diverters = diverters,
            air = air,
            momentum = momentum,
            pipeAir = pipeAir,
            pipeMomentum = pipeMomentum,
            rocks = rocks,
        )
    }

    // ── Identity (zero offset) ───────────────────────────────────────────

    @Test
    fun `zero offset is the identity`() {
        val s0 = simpleWorld(20, 14)
        val s1 = s0.remapped(s0.grid, 0, 0)
        assertEquals(s0.grid, s1.grid)
        assertEquals(s0.machines.size, s1.machines.size)
        assertEquals(s0.machines, s1.machines)
        assertEquals(s0.bridges, s1.bridges)
        assertEquals(s0.conduits, s1.conduits)
        assertEquals(s0.debris, s1.debris)
        assertEquals(s0.diverters.cursor, s1.diverters.cursor)
        assertEquals(s0.air.copyGrams().contentToString(), s1.air.copyGrams().contentToString())
        assertEquals(s0.air.copyJoules().contentToString(), s1.air.copyJoules().contentToString())
        assertEquals(s0.pipeAir.copyGrams().contentToString(), s1.pipeAir.copyGrams().contentToString())
        assertEquals(s0.pipeAir.copyJoules().contentToString(), s1.pipeAir.copyJoules().contentToString())
        assertTrue(s0.momentum.copyX().contentEquals(s1.momentum.copyX()), "momentum X")
        assertTrue(s0.momentum.copyY().contentEquals(s1.momentum.copyY()), "momentum Y")
        assertTrue(s0.pipeMomentum.copyX().contentEquals(s1.pipeMomentum.copyX()), "pipeMomentum X")
        assertTrue(s0.pipeMomentum.copyY().contentEquals(s1.pipeMomentum.copyY()), "pipeMomentum Y")
        assertEquals(s0.rocks, s1.rocks)
        assertEquals(s0.baselineAirGrams, s1.baselineAirGrams)
        assertEquals(s0.baselineAirJoules, s1.baselineAirJoules)
        assertEquals(s0.baselineJoules, s1.baselineJoules)
        assertEquals(s0.baselineRockGrams, s1.baselineRockGrams)
    }

    // ── Positive offset: grow left and up ────────────────────────────────

    @Test
    fun `machines remap correctly with positive dx and dy`() {
        val s0 = simpleWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // Old machine at (5, 5) should now be at (9, 8)
        val oldTile = oldGrid.index(5, 5)
        val newTile = newGrid.index(9, 8)
        assertEquals(s0.machines[oldTile], s1.machines[newTile])
        // Edge machine
        val edgeTile = oldGrid.index(0, 0)
        val edgeNewTile = newGrid.index(dx, dy)
        assertEquals(s0.machines[edgeTile], s1.machines[edgeNewTile])
    }

    @Test
    fun `conduits remap correctly`() {
        val s0 = populatedWorld(12, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (c in Conduit.entries) {
            val oldLayer = s0.conduits[c]
            val newLayer = s1.conduits[c]
            for (x in 0 until oldGrid.width) {
                for (y in 0 until oldGrid.height) {
                    val oldTile = oldGrid.index(x, y)
                    val newTile = newGrid.index(x + dx, y + dy)
                    assertEquals(oldLayer[oldTile], newLayer[newTile],
                        "conduit $c at ($x,$y) -> ($x+$dx,$y+$dy)")
                }
            }
        }
    }

    @Test
    fun `bridges remap correctly`() {
        val s0 = simpleWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 2, oldGrid.height + 2)
        val dx = 2
        val dy = 2
        val bridgeTile = oldGrid.index(5, 5)
        val s0withBridge = s0.copy(machines = s0.machines.toMutableList().also {
            it[bridgeTile] = Hull()
        })
        val s1 = s0withBridge.remapped(newGrid, dx, dy)
        val newTile = newGrid.index(5 + dx, 5 + dy)
        assertEquals(s0withBridge.machines[bridgeTile], s1.machines[newTile])
    }

    @Test
    fun `debris remaps correctly`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 5, oldGrid.height + 4)
        val dx = 5
        val dy = 4

        val s1 = s0.remapped(newGrid, dx, dy)

        // Debris at (3, 3) should move to (8, 7)
        val oldTile = oldGrid.index(3, 3)
        val newTile = newGrid.index(8, 7)
        assertEquals(s0.debris[oldTile], s1.debris[newTile])
    }

    @Test
    fun `diverters remap correctly`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        // Diverter at (7, 7) should move to (10, 9)
        val oldTile = oldGrid.index(7, 7)
        val newTile = newGrid.index(10, 9)
        assertEquals(1, s1.diverters[newTile])
    }

    @Test
    fun `air field remaps correctly`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        for (x in 0 until oldGrid.width) {
            for (y in 0 until oldGrid.height) {
                val oldTile = oldGrid.index(x, y)
                val newTile = newGrid.index(x + dx, y + dy)
                for (s in Species.entries) {
                    val oldGrams = s0.air.gramsOf(oldTile, s)
                    val newGrams = s1.air.gramsOf(newTile, s)
                    assertEquals(oldGrams, newGrams, "air grams at ($x,$y) species=$s")
                }
                assertEquals(s0.air.copyJoules()[oldTile], s1.air.copyJoules()[newTile],
                    "air joules at ($x,$y)")
            }
        }
    }

    @Test
    fun `pipeAir field remaps correctly`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 2, oldGrid.height + 2)
        val dx = 2
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (x in 0 until oldGrid.width) {
            for (y in 0 until oldGrid.height) {
                val oldTile = oldGrid.index(x, y)
                val newTile = newGrid.index(x + dx, y + dy)
                for (s in Species.entries) {
                    val oldGrams = s0.pipeAir.gramsOf(oldTile, s)
                    val newGrams = s1.pipeAir.gramsOf(newTile, s)
                    assertEquals(oldGrams, newGrams)
                }
            }
        }
    }

    // ── Edge fields: momentum ────────────────────────────────────────────

    @Test
    fun `momentum x-faces remap correctly`() {
        val s0 = populatedWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (oy in 0 until oldGrid.height) {
            for (ox in 0..oldGrid.width) {
                val nx = ox + dx
                val ny = oy + dy
                if (ny >= 0 && ny < newGrid.height && nx >= 0 && nx <= newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).xEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).xEdge(nx, ny)
                    assertEquals(s0.momentum.copyX()[oldEdge], s1.momentum.copyX()[newEdge],
                        "momentum x-face at ($ox,$oy)")
                }
            }
        }
    }

    @Test
    fun `momentum y-faces remap correctly`() {
        val s0 = populatedWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        for (ox in 0 until oldGrid.width) {
            for (oy in 0..oldGrid.height) {
                val nx = ox + dx
                val ny = oy + dy
                if (ny >= 0 && ny <= newGrid.height && nx >= 0 && nx < newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).yEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).yEdge(nx, ny)
                    assertEquals(s0.momentum.copyY()[oldEdge], s1.momentum.copyY()[newEdge],
                        "momentum y-face at ($ox,$oy)")
                }
            }
        }
    }

    @Test
    fun `pipeMomentum remaps correctly on both axes`() {
        val s0 = populatedWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 2, oldGrid.height + 3)
        val dx = 2
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // x-faces
        for (oy in 0 until oldGrid.height) {
            for (ox in 0..oldGrid.width) {
                val nx = ox + dx
                val ny = oy + dy
                if (newGrid.inBounds(nx, ny) && ny < newGrid.height && nx <= newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).xEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).xEdge(nx, ny)
                    assertEquals(s0.pipeMomentum.copyX()[oldEdge], s1.pipeMomentum.copyX()[newEdge])
                }
            }
        }
        // y-faces
        for (ox in 0 until oldGrid.width) {
            for (oy in 0..oldGrid.height) {
                val nx = ox + dx
                val ny = oy + dy
                if (ny >= 0 && ny <= newGrid.height && nx >= 0 && nx < newGrid.width) {
                    val oldEdge = EdgeGrid(oldGrid).yEdge(ox, oy)
                    val newEdge = EdgeGrid(newGrid).yEdge(nx, ny)
                    assertEquals(s0.pipeMomentum.copyY()[oldEdge], s1.pipeMomentum.copyY()[newEdge])
                }
            }
        }
    }

    // ── Rocks ────────────────────────────────────────────────────────────

    @Test
    fun `rocks shift by dx_PER_TILE and dy_PER_TILE`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        for (i in s0.rocks.indices) {
            val expectedX = s0.rocks[i].positionX + dx * Flight.PER_TILE
            val expectedY = s0.rocks[i].positionY + dy * Flight.PER_TILE
            assertEquals(expectedX, s1.rocks[i].positionX, "rock $i positionX")
            assertEquals(expectedY, s1.rocks[i].positionY, "rock $i positionY")
        }
    }

    // ── Ledger identities ────────────────────────────────────────────────

    @Test
    fun `airBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 5, oldGrid.height + 4)
        val dx = 5
        val dy = 4

        val s1 = s0.remapped(newGrid, dx, dy)

        assertEquals(s0.airBalance, s1.airBalance, "airBalance must be preserved")
    }

    @Test
    fun `airJouleBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        assertEquals(s0.airJouleBalance, s1.airJouleBalance, "airJouleBalance must be preserved")
    }

    @Test
    fun `momentumBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // The full momentum identity:
        // vesselImpulse + momentum + pipeMomentum + exhaust + undelivered + rock - debug == 0
        fun momentumX(s: VesselState) =
            s.vesselImpulseX + s.momentum.totalX + s.pipeMomentum.totalX +
                s.exhaustMomentumX + s.undeliveredImpulseX + s.rockImpulseX - s.debugImpulseX

        fun momentumY(s: VesselState) =
            s.vesselImpulseY + s.momentum.totalY + s.pipeMomentum.totalY +
                s.exhaustMomentumY + s.undeliveredImpulseY + s.rockImpulseY - s.debugImpulseY

        assertEquals(momentumX(s0), momentumX(s1), "momentumBalanceX must be preserved")
        assertEquals(momentumY(s0), momentumY(s1), "momentumBalanceY must be preserved")
    }

    @Test
    fun `massBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        // mass = massGrams + ventedGrams + extractedGrams (should be invariant)
        fun massBalance(s: VesselState) = s.massGrams + s.ventedGrams + s.extractedGrams
        assertEquals(massBalance(s0), massBalance(s1), "massBalance must be preserved")
    }

    @Test
    fun `rockBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 4, oldGrid.height + 3)
        val dx = 4
        val dy = 3

        val s1 = s0.remapped(newGrid, dx, dy)

        // rock: baselineRockGrams + capturedGrams - extractedGrams == rocks.sumOf { massGrams }
        fun rockBalance(s: VesselState) =
            s.baselineRockGrams + s.capturedGrams - s.extractedGrams - s.rocks.sumOf { it.massGrams }
        assertEquals(rockBalance(s0), rockBalance(s1), "rockBalance must be preserved")
    }

    @Test
    fun `heatBalance is preserved across remap`() {
        val s0 = populatedWorld()
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 3, oldGrid.height + 2)
        val dx = 3
        val dy = 2

        val s1 = s0.remapped(newGrid, dx, dy)

        // heat: stored + radiated + solidToAir - generated - construction - baseline == 0
        fun heatBalance(s: VesselState) =
            s.storedJoules + s.radiatedJoules + s.solidToAirJoules -
                s.generatedJoules - s.constructionJoules - s.baselineJoules
        assertEquals(heatBalance(s0), heatBalance(s1), "heatBalance must be preserved")
    }

    // ── Round trip ───────────────────────────────────────────────────────

    @Test
    fun `remap +4+3 then -4-3 is the identity`() {
        val s0 = populatedWorld(15, 10)
        val g0 = s0.grid
        val g1 = Grid(g0.width + 4, g0.height + 3)
        val g2 = Grid(g1.width - 4, g1.height - 3)

        val s1 = s0.remapped(g1, 4, 3)
        val s2 = s1.remapped(g2, -4, -3)

        assertEquals(s0.grid, s2.grid, "grid should be identical")
        assertEquals(s0.machines, s2.machines, "machines should be identical")
        assertEquals(s0.bridges, s2.bridges, "bridges should be identical")
        assertEquals(s0.conduits, s2.conduits, "conduits should be identical")
        assertEquals(s0.debris, s2.debris, "debris should be identical")
        assertEquals(s0.diverters.cursor, s2.diverters.cursor, "diverters should be identical")
        assertEquals(s0.air.copyGrams().contentToString(), s2.air.copyGrams().contentToString(), "air grams")
        assertEquals(s0.air.copyJoules().contentToString(), s2.air.copyJoules().contentToString(), "air joules")
        assertEquals(s0.pipeAir.copyGrams().contentToString(), s2.pipeAir.copyGrams().contentToString(), "pipeAir grams")
        assertEquals(s0.pipeAir.copyJoules().contentToString(), s2.pipeAir.copyJoules().contentToString(), "pipeAir joules")
        assertTrue(s0.momentum.copyX().contentEquals(s2.momentum.copyX()), "momentum X")
        assertTrue(s0.momentum.copyY().contentEquals(s2.momentum.copyY()), "momentum Y")
        assertTrue(s0.pipeMomentum.copyX().contentEquals(s2.pipeMomentum.copyX()), "pipeMomentum X")
        assertTrue(s0.pipeMomentum.copyY().contentEquals(s2.pipeMomentum.copyY()), "pipeMomentum Y")
        assertEquals(s0.rocks, s2.rocks, "rocks should be identical")
        assertEquals(s0.baselineAirGrams, s2.baselineAirGrams)
        assertEquals(s0.baselineAirJoules, s2.baselineAirJoules)
        assertEquals(s0.baselineJoules, s2.baselineJoules)
        assertEquals(s0.baselineRockGrams, s2.baselineRockGrams)
    }

    // ── Cells outside old grid ───────────────────────────────────────────

    @Test
    fun `new tiles are vacuum`() {
        val s0 = simpleWorld(10, 8)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width + 5, oldGrid.height + 4)
        val dx = 5
        val dy = 4

        val s1 = s0.remapped(newGrid, dx, dy)

        // Tiles in the new area that were not in the old grid should be zero
        for (x in 0 until 5) {
            for (y in 0 until 4) {
                val tile = newGrid.index(x, y)
                assertEquals(0L, s1.air.gramsOf(tile, Species.Iron),
                    "new tile ($x,$y) should be vacuum")
                assertEquals(0L, s1.air.copyJoules()[tile], "new tile ($x,$y) joules should be zero")
            }
        }
    }

    // ── Negative offset: shrink left and up ──────────────────────────────

    @Test
    fun `negative offset drops cells outside new grid`() {
        val s0 = populatedWorld(15, 10)
        val oldGrid = s0.grid
        val newGrid = Grid(oldGrid.width - 3, oldGrid.height - 2)
        val dx = -3
        val dy = -2

        val s1 = s0.remapped(newGrid, dx, dy)

        // Tiles inside the new grid should still be correct
        for (x in 3 until oldGrid.width) {
            for (y in 2 until oldGrid.height) {
                val oldTile = oldGrid.index(x, y)
                val nx = x + dx
                val ny = y + dy
                val newTile = newGrid.index(nx, ny)
                assertEquals(s0.machines[oldTile], s1.machines[newTile],
                    "machine at ($x,$y) -> ($nx,$ny)")
            }
        }
    }
}
