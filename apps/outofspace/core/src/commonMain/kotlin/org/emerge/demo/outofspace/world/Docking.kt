package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DockingPort

/**
 * Whether two mouths are close enough and square enough to join — `PLAN_economy.md` §7.
 *
 * ### Vectors, not angles
 *
 * "Are these two facing each other?" is asked with a **dot product of two world-frame directions**,
 * never by comparing two [org.emerge.sim.core.physics.primitives.Coord]s. Both mouths already know
 * which way they point in their own frame, and [Pose.turnedX]/[Pose.turnedY] put a direction into
 * the world exactly — so the question needs no branch cut, no wrap-around case, and no trig. A
 * difference of angles would need all three.
 *
 * ⚠️ **Directions are carried at [UNIT] and not at `Flight.FRAC_ONE`.** A dot product of two
 * `FRAC_ONE` vectors is 2 × (2.1e9)² = 9.2e18, which is `Long.MAX_VALUE` to two significant figures
 * — it does not overflow today and would the moment anybody added a third term. A million is plenty:
 * the threshold below is stated per mille, so a millionth of a unit is a thousand times finer than
 * anything that reads it.
 */
object Docking {

    /** The scale a direction vector is carried at here. See the class note. */
    const val UNIT: Long = 1_000_000L

    /**
     * How near the two mouths must be, in tiles.
     *
     * Two, so that "close enough to dock" is a place the player can see they are in rather than a
     * pixel they have to find. Tunable, and the tuning is a feel question for step 6.
     */
    const val RANGE_TILES: Long = 2L

    /**
     * How square the two mouths must be, as `−dot` per mille of a unit.
     *
     * 866 per mille is a cosine of 0.866, so the mouths must point within **30° of head-on**. The
     * sign is the whole condition: two mouths that dock are pointing *at* each other, so their
     * outward directions are opposed and the dot product is negative.
     */
    const val ALIGNMENT_PERMILLE: Long = 866L

    /**
     * Whether the [port] on a vessel at [shipPose] may berth at dock [nodeIndex] of [station].
     *
     * Both conditions, and both are about the **mouths** rather than about the bodies: a station is
     * twenty tiles across and a ship is bigger, so "the two are near each other" is true long before
     * anything is lined up.
     */
    fun canDock(
        grid: Grid,
        port: DockingPort,
        shipPose: Pose,
        station: RigidBody,
        nodeIndex: Int,
    ): Boolean {
        val node = station.station?.docks?.getOrNull(nodeIndex) ?: return false
        val stationPose = station.pose

        val shipX = berthWorldX(grid, port, shipPose)
        val shipY = berthWorldY(grid, port, shipPose)
        val nodeX = nodeWorldX(node, stationPose)
        val nodeY = nodeWorldY(node, stationPose)

        // ⚠️ The difference is taken before anything is squared. Two absolute world coordinates
        // multiplied together leave `Long` three tiles from the origin — `PLAN_rigid_bodies.md` §5.3,
        // and it fails silently as a wrapped value rather than loudly.
        val dx = shipX - nodeX
        val dy = shipY - nodeY
        val reach = RANGE_TILES * Flight.PER_TILE
        if (dx * dx + dy * dy > reach * reach) return false

        return squareOn(port.facing, shipPose, node.facing, stationPose)
    }

    /** Whether two outward directions are pointing at each other, within [ALIGNMENT_PERMILLE]. */
    fun squareOn(shipFacing: Direction, shipPose: Pose, nodeFacing: Direction, stationPose: Pose): Boolean {
        val sx = shipPose.turnedX(shipFacing.dx * UNIT, shipFacing.dy * UNIT)
        val sy = shipPose.turnedY(shipFacing.dx * UNIT, shipFacing.dy * UNIT)
        val nx = stationPose.turnedX(nodeFacing.dx * UNIT, nodeFacing.dy * UNIT)
        val ny = stationPose.turnedY(nodeFacing.dx * UNIT, nodeFacing.dy * UNIT)
        // Opposed, so the dot product is negative and the test is on its magnitude.
        val dot = sx * nx + sy * ny
        return -dot >= ALIGNMENT_PERMILLE * UNIT * UNIT / 1_000L
    }

    /**
     * The world point a ship's berth presents: one tile **outside** the port's footprint, on its
     * centre line, along [DockingPort.facing].
     *
     * `reach + 1` rather than `reach`, because `reach` is the last tile the machine stands on and a
     * mouth is the space in front of it. Half a tile is added so the point is the middle of that
     * tile rather than its corner — two mouths that meet corner-to-corner are half a tile apart for
     * no reason anyone could see.
     */
    fun berthWorldX(grid: Grid, port: DockingPort, shipPose: Pose): Long =
        shipPose.toWorldX(berthLocalX(grid, port), berthLocalY(grid, port))

    fun berthWorldY(grid: Grid, port: DockingPort, shipPose: Pose): Long =
        shipPose.toWorldY(berthLocalX(grid, port), berthLocalY(grid, port))

    private fun berthLocalX(grid: Grid, port: DockingPort): Long =
        (grid.xOf(port.center) + port.facing.dx * (port.reach + 1)) * Flight.PER_TILE + Flight.PER_TILE / 2

    private fun berthLocalY(grid: Grid, port: DockingPort): Long =
        (grid.yOf(port.center) + port.facing.dy * (port.reach + 1)) * Flight.PER_TILE + Flight.PER_TILE / 2

    /** The world point a station's berth presents: one cell out from the hull cell that carries it. */
    fun nodeWorldX(node: DockNode, stationPose: Pose): Long =
        stationPose.toWorldX(nodeLocalX(node), nodeLocalY(node))

    fun nodeWorldY(node: DockNode, stationPose: Pose): Long =
        stationPose.toWorldY(nodeLocalX(node), nodeLocalY(node))

    private fun nodeLocalX(node: DockNode): Long =
        (node.cellX + node.facing.dx) * Flight.PER_TILE + Flight.PER_TILE / 2

    private fun nodeLocalY(node: DockNode): Long =
        (node.cellY + node.facing.dy) * Flight.PER_TILE + Flight.PER_TILE / 2
}
