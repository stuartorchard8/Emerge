package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid

/**
 * Pipe network for fluid solver: own mass/momentum/energy, same lattice as room (not separate solver).
 * Connectivity: from Segment.links (drawn), not derived from StructureMap (two pipes can touch and be separate).
 * Volume: 1/8 tile per cell (tuned; narrowness = behaviour).
 * No pipe bridges (fluid is continuum; rail bridge = discrete packet crossing).
 */

/**
 * How much room the gas in a pipe cell has, as a fraction of [VolumeField.FULL].
 *
 * An eighth of a tile. A tuning dial rather than a measurement — the honest number depends on a bore
 * nobody has chosen — but the *order* matters and small is the whole point: at a full tile a pipe
 * holds a room's worth of gas, so filling one takes a room's worth of pumping and the pressure behind
 * a closed valve builds as slowly as pressurising a deck. The narrowness is the behaviour.
 */
const val PIPE_VOLUME: Int = VolumeField.FULL / 8

/**
 * Which pipe faces are open: both tiles carry a pipe, and the player drew the join between them.
 *
 * Every other face is [ApertureField.CLOSED], which includes every face on the rim of the grid. A
 * pipe therefore **cannot vent to space**, whatever it is holding and wherever it is built — there is
 * no aperture for gas to cross. That is the correct default rather than a limitation: a pipe that
 * leaks does so because something cut it, which is an edit, not a boundary condition.
 *
 * The link is checked from **both** sides. It is always written symmetrically — `joinedTo` sets both
 * halves and is the only way to set one — so this is a belt-and-braces read of a fact that should
 * already agree with itself, and cheap enough to be worth not assuming.
 */
fun pipeApertures(edges: EdgeGrid, conduits: Conduits): ApertureField {
    val grid = edges.grid
    val x = IntArray(edges.xEdgeCount)
    val y = IntArray(edges.yEdgeCount)

    fun joined(a: TileIndex, b: TileIndex, aToB: Direction): Boolean {
        if (a == TileIndex.NONE || b == TileIndex.NONE) return false
        val sa = conduits.at(Conduit.Pipe, a) ?: return false
        val sb = conduits.at(Conduit.Pipe, b) ?: return false
        return sa.linkedTo(aToB) && sb.linkedTo(aToB.opposite)
    }

    for (e in 0 until edges.xEdgeCount) {
        if (joined(edges.xEdgeBefore(e), edges.xEdgeAfter(e), Direction.Right)) x[e] = ApertureField.OPEN
    }
    for (e in 0 until edges.yEdgeCount) {
        if (joined(edges.yEdgeBefore(e), edges.yEdgeAfter(e), Direction.Down)) y[e] = ApertureField.OPEN
    }
    return ApertureField(edges, x, y)
}

/**
 * Where gas may cross between a room and the pipe sharing its tile: a length of pipe that is a valve.
 *
 * Everything else is [ApertureField.CLOSED], which is every tile in a vessel with no plumbing in it
 * and every tile of an ordinary run. A pipe is sealed unless somebody opened it, which is what a pipe
 * is.
 *
 * The width is [ApertureField.OPEN] — as wide as the way gets. That is a real position rather than a
 * placeholder for a tuning constant: [exchangeLayers] is bounded by the volumes it is equalising
 * between, so a fully open valve moves the gas that fits and no more. A throttled valve is this
 * number scaled down, and comes free the moment anything wants to ask for one.
 */
fun valveOpenings(grid: Grid, conduits: Conduits, deck: DeckArray): IntArray =
    IntArray(grid.size) { tile ->
        // Both, and that is the rule a valve used to get for free by being a length of pipe: it
        // opens the pipe it stands on, so with no pipe under it there is nothing to open. A valve on
        // bare floor is a half-built vessel, not an error.
        val at = TileIndex(tile)
        val open = deck[at] is Valve && conduits.at(Conduit.Pipe, at) != null
        if (open) ApertureField.OPEN else ApertureField.CLOSED
    }

/**
 * [PIPE_VOLUME] wherever a pipe is laid, and a whole tile everywhere else.
 *
 * The cells with no pipe on them never hold gas and never open a face, so their volume is never read
 * for anything. They are [VolumeField.FULL] rather than zero because zero is a division by nothing —
 * see [VolumeField.of], which refuses it rather than letting a build mistake become a field of
 * infinities.
 */
fun pipeVolumes(grid: Grid, conduits: Conduits): VolumeField =
    VolumeField.of(
        IntArray(grid.size) { tile ->
            if (conduits.at(Conduit.Pipe, TileIndex(tile)) != null) PIPE_VOLUME else VolumeField.FULL
        }
    )
