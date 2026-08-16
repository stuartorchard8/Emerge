package org.emerge.demo.outofspace.world

import kotlin.math.sqrt

/**
 * Where the air is going, tile by tile — the net mass crossing each tile per tick, reconstructed
 * from the face fluxes of one diffusion pass (opposing faces averaged).
 *
 * This is measured, not modelled: it reports what [diffuseFluid] actually moved, so it cannot drift
 * away from the sim the way a separately-integrated velocity field can. It replaced exactly such a
 * field — tile velocities read off the momentum solver — which kept being drawn after the solver
 * itself was extracted, by which point the arrows were an accumulating impulse total that nothing
 * ever spent.
 *
 * Presentation and inspection only. Nothing in the sim reads it.
 */
class FlowField(
    private val x: LongArray,
    private val y: LongArray,
    private val speed: FloatArray,
) {

    /** Net mass per tick crossing the tile along +x. */
    fun xAt(tile: TileIndex): Long = x[tile.index]

    /** Net mass per tick along +y — **downward**, since the world is side-on and screen-down is gravity-down. */
    fun yAt(tile: TileIndex): Long = y[tile.index]

    /**
     * How fast the tile's contents are moving, in tiles per tick: the net mass crossing it over the
     * mass it held. One means a tile-load of gas moved a tile in a tick, which is the fastest
     * anything can honestly be said to travel on this lattice — though diffusion cannot reach it,
     * since a cell keeps a share (see [SLOTS]).
     */
    fun speedAt(tile: TileIndex): Float = speed[tile.index]

    /** The fastest tile in the field, in tiles per tick. Zero for still air. */
    fun peakSpeed(): Float {
        var best = 0f
        for (s in speed) if (s > best) best = s
        return best
    }

    companion object {

        /**
         * Reconstruct tile-centre flow from the face fluxes of a diffusion pass.
         *
         * The denominator for [speedAt] is the **larger** of the tile's mass before and after the
         * pass, because both ends of a tile's life have a way of being zero and neither zero means
         * nothing happened. A tile that empties completely still moved a tile-load of gas; a tile
         * filling from vacuum starts at nothing and is the single most visible flow in the game —
         * air rushing into a breached room — so dividing by where it started would silently rate the
         * clearest flow on screen as no flow at all. The larger of the two is the tile-load the
         * movement is worth measuring against, and it is zero only when the tile was empty
         * throughout, in which case the flux is zero too.
         */
        fun derive(
            edges: EdgeGrid,
            fluxX: LongArray,
            fluxY: LongArray,
            startingMass: LongArray,
            endingMass: LongArray,
        ): FlowField {
            val size = edges.grid.size
            val x = LongArray(size)
            val y = LongArray(size)
            val speed = FloatArray(size)
            for (tile in edges.grid.tiles) {
                // Averaged, so gas passing straight through reads as its throughput and gas arriving
                // from both sides at once reads as still — which on the whole it is. Divergence is a
                // different question, and pressure is the overlay that answers it.
                x[tile.index] = (fluxX[edges.leftEdgeOf(tile)] + fluxX[edges.rightEdgeOf(tile)]) / 2L
                y[tile.index] = (fluxY[edges.upEdgeOf(tile)] + fluxY[edges.downEdgeOf(tile)]) / 2L
                val mass = maxOf(startingMass[tile.index], endingMass[tile.index])
                if (mass <= 0L) continue
                val fx = x[tile.index].toDouble()
                val fy = y[tile.index].toDouble()
                speed[tile.index] = (sqrt(fx * fx + fy * fy) / mass).toFloat()
            }
            return FlowField(x, y, speed)
        }

        /** A world in which nothing is moving — the default for a state nobody has stepped yet. */
        fun still(size: Int): FlowField = FlowField(LongArray(size), LongArray(size), FloatArray(size))
    }
}
