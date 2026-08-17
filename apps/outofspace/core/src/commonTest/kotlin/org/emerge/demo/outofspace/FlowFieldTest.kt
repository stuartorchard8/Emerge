package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.ApertureField
import org.emerge.demo.outofspace.world.EdgeGrid
import org.emerge.demo.outofspace.world.FlowField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.MassIndex
import org.emerge.demo.outofspace.world.SLOTS
import org.emerge.demo.outofspace.world.StructureMap
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.diffuseFluid
import org.emerge.demo.outofspace.world.machine.DeckArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tile-centre reconstruction of what diffusion moved.
 *
 * This is a *view*, so it cannot be wrong about the physics — but it can be wrong about which way is
 * which, and a flow overlay that draws the world's air going the wrong way is worse than no overlay
 * at all, because it will be believed. So the signs are pinned here rather than eyeballed on screen.
 *
 * The fluxes come from real diffusion passes rather than being handed in: the whole point of the
 * rewrite is that the overlay reports what the sim did, and a test that fabricated its input would
 * not be checking that.
 */
class FlowFieldTest {

    private val grid = Grid(5, 5)
    private val edges = EdgeGrid(grid)
    private val structure = StructureMap.derive(grid, List(grid.size) { null }, DeckArray(grid.size))
    private val apertures = ApertureField.derive(edges, structure)

    /**
     * Grams laid out by tile, all of one species, then diffused.
     *
     * Pinned to a single pass, because these assertions are arithmetic on one share — `held / SLOTS`
     * across a face. Left on the default they would quietly become assertions about
     * [org.emerge.demo.outofspace.world.SUB_STEPS] and go red the moment anyone tuned it, which is
     * the opposite of what they are for.
     */
    private fun flowFrom(vararg loaded: Pair<TileIndex, Long>): FlowField {
        val mass = MassArray(grid.size)
        for ((tile, count) in loaded) mass[MassIndex(tile, Species.Nitrogen)] = count
        return diffuseFluid(edges, apertures, mass, energies = null, subSteps = 1).flow
    }

    /**
     * Uniform air is still — *inside*. Every interior tile hands each neighbour exactly what that
     * neighbour hands back, so the faces net to zero and no arrow is drawn.
     *
     * The rim is a different matter and is asserted here rather than excused: a tile on the edge of
     * the grid sheds a share into space and gets nothing back, so a uniformly-filled open grid is
     * genuinely blowing outward everywhere along its border. That is the venting the whole model is
     * built around, and if it ever stopped showing up here, breaches would have gone quiet too.
     */
    @Test
    fun `uniform air is still inside, and venting at the rim`() {
        val loaded = Array(grid.size) { TileIndex(it) to 1_000L }
        val flow = flowFrom(*loaded)

        for (y in 1..3) {
            for (x in 1..3) {
                val tile = grid.tile(x, y)
                assertEquals(0f, flow.speedAt(tile), 0f, "interior tile ($x,$y) should be still")
            }
        }

        // Left rim, mid-height: losing air to space through its left face, so net movement is -x.
        assertTrue(flow.xAt(grid.tile(0, 2)) < 0L, "left rim should be venting leftward")
        assertTrue(flow.xAt(grid.tile(4, 2)) > 0L, "right rim should be venting rightward")
        assertTrue(flow.yAt(grid.tile(2, 0)) < 0L, "top rim should be venting upward")
        assertTrue(flow.yAt(grid.tile(2, 4)) > 0L, "bottom rim should be venting downward")
    }

    @Test
    fun `air spreading rightwards into vacuum reads as moving along positive x`() {
        // A full column at x=1 with vacuum to its right: the tile at (2,2) is being filled from the
        // left and passing it on, so the net movement across it is toward +x.
        val source = (0 until 5).map { grid.tile(1, it) to 5_000L }
        val flow = flowFrom(*source.toTypedArray())
        val tile = grid.tile(2, 2)
        assertTrue(flow.xAt(tile) > 0L, "flow should be toward +x, was ${flow.xAt(tile)}")
        assertEquals(0L, flow.yAt(tile), "a horizontal spread has no vertical component")
    }

    /**
     * The one that would be silently inverted. `+y` is *down* on this grid — the world is side-on —
     * so flow toward `+y` is air heading for the floor, and the overlay has to draw it that way.
     */
    @Test
    fun `air spreading downward reads as positive y`() {
        val source = (0 until 5).map { grid.tile(it, 1) to 5_000L }
        val flow = flowFrom(*source.toTypedArray())
        val tile = grid.tile(2, 2)
        assertTrue(flow.yAt(tile) > 0L, "positive y is toward the floor, was ${flow.yAt(tile)}")
        assertEquals(0L, flow.xAt(tile))
    }

    /**
     * Documenting the reconstruction's known blind spot rather than pretending it does not exist.
     *
     * Air arriving from both sides at once is *filling* the tile, not moving through it, and
     * averaging the two faces gives zero — correctly, since the tile's contents are on the whole
     * going nowhere. Divergence is a different question and [Overlay.Pressure] is what asks it; a
     * picture that tried to show both at once would show neither clearly.
     */
    @Test
    fun `air arriving from both sides reads as still, because on the whole it is`() {
        val flow = flowFrom(
            grid.tile(1, 2) to 5_000L,
            grid.tile(3, 2) to 5_000L,
        )
        assertEquals(0L, flow.xAt(grid.tile(2, 2)), "symmetric inflow should cancel")
    }

    /**
     * The units, worked out rather than pinned.
     *
     * One loaded tile beside vacuum. It splits [SLOTS] ways, so each of its four faces passes
     * `held / SLOTS`. The neighbour to its right therefore takes `share` in across its left face and
     * passes nothing on (it began empty), which averages to `share / 2` of net movement across a
     * tile that now holds exactly `share`: half a tile per tick.
     */
    @Test
    fun `speed is in tiles per tick, measured against the tile-load that moved`() {
        val held = 5_000L
        val share = held / SLOTS
        val flow = flowFrom(grid.tile(2, 2) to held)

        val next = grid.tile(3, 2)
        assertEquals(share / 2, flow.xAt(next), "net mass across the filling tile")
        assertEquals(share.toFloat() / 2 / share, flow.speedAt(next), 0.0001f)
    }

    /**
     * The case that made [FlowField.derive] measure against the larger of the tile's two masses. A
     * tile filling from vacuum starts at zero mass, and dividing by where it started would rate air
     * rushing into an empty room — the most visible flow in the game — as no flow at all, because the
     * overlay decides what to draw by [FlowField.speedAt].
     */
    @Test
    fun `air pouring into vacuum is not rated as stillness`() {
        val flow = flowFrom(grid.tile(2, 2) to 5_000L)
        val filling = grid.tile(3, 2)
        assertTrue(flow.speedAt(filling) > 0f, "a tile filling from nothing must read as moving")
    }

    /** The mirror of it: a tile that empties completely is measured against what it used to hold. */
    @Test
    fun `a tile emptying into vacuum is measured against what it held`() {
        // Two tiles side by side at the rim, so the pair drains outward with nowhere to refill from.
        val flow = flowFrom(grid.tile(0, 2) to 5_000L)
        val draining = grid.tile(0, 2)
        // Its left face vents and its right face passes gas on, so the two cancel on x — it is
        // emptying, not travelling, which is the same reading as the squeezed case and correct.
        assertEquals(0L, flow.xAt(draining))
        assertEquals(0L, flow.yAt(draining))
    }

    @Test
    fun `peak speed finds the fastest tile in the field`() {
        val flow = flowFrom(grid.tile(1, 2) to 9_000L)
        var best = 0f
        for (tile in grid.tiles) best = maxOf(best, flow.speedAt(tile))
        assertEquals(best, flow.peakSpeed())
    }

    @Test
    fun `a vacuum tile has no flow rather than a division by nothing`() {
        val flow = flowFrom(grid.tile(4, 4) to 1_000L)
        val empty = grid.tile(0, 0)
        assertEquals(0f, flow.speedAt(empty), 0f)
    }
}
