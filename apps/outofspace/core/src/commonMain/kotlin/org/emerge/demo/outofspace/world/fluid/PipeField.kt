package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Conduits
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid

/**
 * The pipe network as something the fluid solver can run on: what is connected to what, and how much
 * room the gas in it has.
 *
 * ### Why a second field and not a sealed region of the first
 *
 * The plan said pipes would be the same solver on a sealed sub-region of the vessel's own grid, with
 * a narrow aperture standing in for the narrowness of a pipe. That is wrong, and it is wrong for the
 * reason the body model already found once: **a tile is not a thing.** Conduits are layers precisely
 * so that a corridor can have a pipe running along it — and then that tile has to hold both the
 * corridor's air and the pipe's contents, at different pressures, at different temperatures, made of
 * different things. One fluid cell per tile can represent one of them.
 *
 * So the pipe layer is its own field: its own grams, its own momentum, its own energy. What it is
 * *not* is its own solver. It runs on the same lattice, so a pipe cell's momentum is a vector in the
 * same basis as the room's, and every pass in this package applies to it unchanged — which is what
 * the aperture-as-area decision bought, just one layer over from where it expected to spend it.
 *
 * ### Connectivity comes from the drawing, not from the geometry
 *
 * This is the whole difference between the two fields. The vessel's apertures are derived from what
 * is *solid* — [ApertureField.derive] asks the [org.emerge.demo.outofspace.world.StructureMap]. A
 * pipe conducts where the player **drew a link**, which is a fact about [
 * org.emerge.demo.outofspace.world.Segment.links] and cannot be recovered from which tiles are
 * occupied: two pipe runs can lie side by side, touching, and be separate plumbing. That is why the
 * solver had to stop deriving its own connectivity.
 *
 * ### Bridges are not here
 *
 * Deliberately, and not as an oversight. A rail bridge works because a packet is a discrete thing
 * that can be handed across a span; a fluid is a continuum, and a bridge carrying one wants either a
 * third field or a small one-dimensional solver of its own inside the span. Neither is worth
 * guessing at before the two-layer case works. A pipe bridge is simply not offered, so nothing here
 * has to pretend to support one.
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

    fun joined(a: Int, b: Int, aToB: Direction): Boolean {
        if (a < 0 || b < 0) return false
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
fun valveOpenings(grid: Grid, conduits: Conduits): IntArray =
    IntArray(grid.size) { tile ->
        if (conduits.at(Conduit.Pipe, tile)?.isValve == true) ApertureField.OPEN else ApertureField.CLOSED
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
            if (conduits.at(Conduit.Pipe, tile) != null) PIPE_VOLUME else VolumeField.FULL
        }
    )
