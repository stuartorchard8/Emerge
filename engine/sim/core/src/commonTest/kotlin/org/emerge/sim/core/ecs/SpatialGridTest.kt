package org.emerge.sim.core.ecs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpatialGridTest {

    /**
     * Grid must see bodies whose centres are close via the torus seam, not
     * only bodies close in raw-Int space. Relies on cell-index bitmask-wrap
     * mirroring `Coord.minus`'s Int-overflow wrap.
     */
    @Test
    fun neighbourSweepCrossesTorusSeam() {
        // cellSize = 2^29 -> 8 cells per axis; plenty of slack around the seam.
        val grid = SpatialGrid.forMinCellSize(minCellSize = 1L shl 29)
        assertNotNull(grid)

        // Two bodies on opposite sides of the seam, only a handful of raw
        // units apart along the torus-shortest path.
        val justAboveMin = Int.MIN_VALUE + 1
        val justBelowMax = Int.MAX_VALUE
        grid.insert(index = 0, xRaw = justBelowMax, yRaw = 0)
        grid.insert(index = 1, xRaw = justAboveMin, yRaw = 0)

        val seen = mutableListOf<Int>()
        grid.forEachNeighbour(xRaw = justBelowMax, yRaw = 0) { seen.add(it) }
        // Body 0 should see both itself and body 1 (across the seam).
        assertContains(seen, 0)
        assertContains(seen, 1)

        seen.clear()
        grid.forEachNeighbour(xRaw = justAboveMin, yRaw = 0) { seen.add(it) }
        assertContains(seen, 0)
        assertContains(seen, 1)
    }

    @Test
    fun neighbourSweepDoesNotSeeFarawayBodiesInOtherCells() {
        val grid = SpatialGrid.forMinCellSize(minCellSize = 1L shl 29)
        assertNotNull(grid)

        // Same axis, two cells apart — should NOT appear in each other's 3x3
        // window. With 8 cells per axis cellSize 2^29, shortest torus distance
        // between cells 0 and 2 is 2*cellSize = 2^30 raw units.
        grid.insert(index = 0, xRaw = 0, yRaw = 0)
        grid.insert(index = 1, xRaw = (1 shl 30), yRaw = 0) // cell 2

        val seen = mutableListOf<Int>()
        grid.forEachNeighbour(xRaw = 0, yRaw = 0) { seen.add(it) }
        assertContains(seen, 0)
        assertTrue(1 !in seen, "body 1 at cell 2 should not be a neighbour of cell 0, got $seen")
    }

    @Test
    fun neighbourSweepVisitsDiagonalSeamCrossings() {
        val grid = SpatialGrid.forMinCellSize(minCellSize = 1L shl 29)
        assertNotNull(grid)

        // Diagonally opposite corners of the torus — should still be neighbours
        // via (dx, dy) = (±1, ±1) with both axes wrapped.
        val justBelowMax = Int.MAX_VALUE
        val justAboveMin = Int.MIN_VALUE + 1
        grid.insert(index = 0, xRaw = justBelowMax, yRaw = justBelowMax)
        grid.insert(index = 1, xRaw = justAboveMin, yRaw = justAboveMin)

        val seen = mutableListOf<Int>()
        grid.forEachNeighbour(xRaw = justBelowMax, yRaw = justBelowMax) { seen.add(it) }
        assertContains(seen, 1)
    }

    @Test
    fun neighbourSweepDoesNotDoubleVisitOnMinimumGridSize() {
        // 4 cells per axis is the documented minimum. A body in any cell
        // should visit each of the 9 cells in its 3x3 window exactly once;
        // crucially, each distinct neighbouring body should appear exactly
        // once in forEachNeighbour (no self-duplication from wrap).
        val grid = SpatialGrid.forMinCellSize(minCellSize = 1L shl 30) // 4 cells/axis
        assertNotNull(grid)

        // Put one body in each cell along x-axis; they're all within each
        // other's 3x3 window. But each body should appear in the visit
        // stream exactly once, not twice because of cell-index wrap.
        grid.insert(index = 0, xRaw = 0, yRaw = 0)
        grid.insert(index = 1, xRaw = (1 shl 30), yRaw = 0)
        grid.insert(index = 2, xRaw = (2 shl 30), yRaw = 0)
        grid.insert(index = 3, xRaw = (3 shl 30), yRaw = 0)

        val seen = mutableListOf<Int>()
        grid.forEachNeighbour(xRaw = 0, yRaw = 0) { seen.add(it) }
        // With 4 cells per axis and 3x3 neighbourhood, we see 3 of 4 x-cells
        // (the far cell is not adjacent). Each visited body should appear
        // exactly once.
        val counts = seen.groupingBy { it }.eachCount()
        assertTrue(
            counts.values.all { it == 1 },
            "expected each index visited at most once, got $counts",
        )
    }

    @Test
    fun forMinCellSizeRoundsUpToPowerOfTwo() {
        // Not a power of two; cell size must round up to 2^30, giving a
        // 4-cells-per-axis grid. The 4 cells along x cover:
        //   cell 0: [0, 2^30),    cell 1: [2^30, 2^31),
        //   cell 2: [-2^31, -2^30), cell 3: [-2^30, 0)
        // Cell 0's 3x3 x-neighbours are cells {3, 0, 1} — cell 2 is 2 cells
        // away in either torus direction, so bodies there must NOT appear.
        val grid = SpatialGrid.forMinCellSize(minCellSize = (1L shl 29) + 1)
        assertNotNull(grid)
        grid.insert(0, xRaw = 0, yRaw = 0)               // cell 0
        grid.insert(1, xRaw = Int.MIN_VALUE, yRaw = 0)   // cell 2
        val seen = mutableListOf<Int>()
        grid.forEachNeighbour(xRaw = 0, yRaw = 0) { seen.add(it) }
        assertEquals(listOf(0), seen, "Body 1 in cell 2 is 2 cells from cell 0")
    }

    @Test
    fun forMinCellSizeReturnsNullWhenCellsDoNotFit() {
        // minCellSize > 2^30 leaves fewer than 4 cells per axis.
        assertNull(SpatialGrid.forMinCellSize(minCellSize = (1L shl 30) + 1))
        assertNull(SpatialGrid.forMinCellSize(minCellSize = 1L shl 31))
    }

    @Test
    fun insertionOrderPreservedWithinCell() {
        val grid = SpatialGrid.forMinCellSize(minCellSize = 1L shl 29)
        assertNotNull(grid)
        grid.insert(7, xRaw = 0, yRaw = 0)
        grid.insert(3, xRaw = 0, yRaw = 0)
        grid.insert(5, xRaw = 0, yRaw = 0)
        val seen = mutableListOf<Int>()
        grid.forEachNeighbour(xRaw = 0, yRaw = 0) { seen.add(it) }
        assertEquals(listOf(7, 3, 5), seen)
    }
}
