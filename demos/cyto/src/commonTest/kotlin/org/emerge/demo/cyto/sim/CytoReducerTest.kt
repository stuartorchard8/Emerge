package org.emerge.demo.cyto.sim

import org.emerge.demo.cyto.cells.CellType
import org.emerge.sim.core.PlayerId
import org.emerge.sim.core.physics.components.SpringConstraintComponent
import org.emerge.sim.core.physics.primitives.Coord2
import org.emerge.sim.core.sim.SimBuilder
import org.emerge.sim.core.sim.SimState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless checks for the native (Box2D-free) Cyto reducer — the parity-critical paths
 * that can't be eyeballed without a display: cells weld on contact via the spring system,
 * and a stem cell divides into a connected colony.
 */
class CytoReducerTest {
    private val cfg = CytoConfig()
    private val reducer = CytoReducer()
    private val noInput = mapOf(PlayerId(0) to CytoInput.EMPTY)

    private fun springCount(state: SimState): Int =
        state.components.getTable<SpringConstraintComponent>().asMap().values.sumOf { it.springs.size }

    @Test
    fun overlappingCellsWeld() {
        // Two cells 0.2 apart (sum radii 0.5; weld threshold 0.375) — they should spring-join.
        var state = run {
            val b = SimBuilder(SimState())
            b.spawnCell(CytoUnits.coord2(-0.1f, 0f), Coord2.zero, CellType.Blank, mapOf("energy" to 1f), MIN_RADIUS)
            b.spawnCell(CytoUnits.coord2(0.1f, 0f), Coord2.zero, CellType.Blank, mapOf("energy" to 1f), MIN_RADIUS)
            b.build()
        }
        repeat(5) { state = reducer.reduce(cfg, state, noInput) }
        assertTrue(springCount(state) > 0, "overlapping cells should have welded")
    }

    @Test
    fun stemCellDividesIntoConnectedColony() {
        var state = createCytoInitialState()
        repeat(700) { state = reducer.reduce(cfg, state, noInput) }
        val cellCount = state.components.getTable<CytoCellComponent>().keys().size
        assertTrue(cellCount > 1, "stem cell should divide into a colony; got $cellCount")
        assertTrue(springCount(state) > 0, "divided cells should be spring-connected")
    }
}
