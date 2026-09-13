package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.DeckArray

/**
 * How much of each face is open to flow — an **area**, not a yes-or-no.
 *
 * A solid mask would do everything the vessel needs today, and it is deliberately not what this is.
 * The plan's end state has a pipe be *the same solver on a sealed sub-region* rather than a third
 * transport network, and the whole difference between a pipe and a corridor is that a pipe is narrow
 * along its run and shut across it. That is an aperture. Make the mask a boolean now and pipes are a
 * rewrite; make it an area now and pipes are configuration.
 *
 * It also earns its keep before then. A partly-open face is what a door mid-cycle is, what a vent
 * with a grille over it is, and what a hull tile with a small hole punched in it is — a breach that
 * bleeds rather than one that either holds perfectly or blows out completely.
 *
 * [OPEN] is a power of two so that scaling a flux by an aperture is a shift rather than a division,
 * and so that a half-open face is exactly half rather than nearly half. Everything here is integer
 * for the reasons [org.emerge.demo.outofspace.chem.Mixture] gives.
 */
/**
 * How far each [Airlock] is open this tick, indexed by tile, in [ApertureField.OPEN] units.
 *
 * Null when the vessel has no airlocks, which is the overwhelmingly common case — everything
 * downstream treats null as "all shut", so a vessel without doors pays nothing for the feature.
 *
 * This is the input to *both* derivations, and it has to be, because an airlock changes two different
 * things and they must agree. [StructureMap.derive] skips an open one, so the doorway becomes a tile
 * air can actually sit in rather than a solid tile secretly holding gas; [ApertureField.derive] then
 * grades its faces. Deriving them from one array is what stops a door being open for the flood fill
 * and shut for the solver on the same tick.
 *
 * The consequence of the first part is worth stating plainly, because it is a real effect and not an
 * implementation detail: **crack a door and the room behind it reads as outside.** The flood fill
 * reaches in, the room becomes [org.emerge.demo.outofspace.world.Structure.Vacuum], and the machines
 * in it start radiating to space. That is correct — a room open to vacuum *is* outside — but it is a
 * step change at the moment the door leaves shut, because containment is a yes-or-no question and
 * there is no graded answer to give it. The gas leaving is smooth; only the label snaps.
 */
fun airlockOpenness(
    deck: DeckArray,
    signals: SignalField,
    grid: Grid,
    bodies: List<RigidBody> = emptyList(),
    pose: Pose? = null,
    structure: StructureMap,
): IntArray? {
    var openness: IntArray? = null
    for (i in 0 until deck.size) {
        val m = deck[TileIndex(i)]
        if (m !is Airlock) continue
        val array = openness ?: IntArray(deck.size).also { openness = it }
        array[i] = airlockOpenness(m, signals, grid, bodies, pose, structure)
    }
    return openness
}

/**
 * The same, but aware of rigid bodies.
 *
 * [m.sealed] gates the rigid-body check: when sealed, the airlock stays open while any body
 * overlaps its footprint (it refuses to shut on something in its way). When not sealed, an
 * unsignalled airlock closes regardless of bodies.
 *
 * [structure] is the structure map from the previous tick. It tells us whether the airlock was
 * open or closed at the start of this tick. If the airlock tile is [Structure.Hull] or
 * [Structure.Machine] in [structure], the airlock was shut at tick start — it stays shut unless
 * now signalled open. This implements the "armed but once closed, stays closed" behaviour: a body
 * drifting into a closed airlock's footprint does not re-open it. If the airlock was already open
 * (not Hull/Machine in [structure]), body overlap can keep it open — again, only if [sealed].
 */
fun airlockOpenness(
    m: Airlock,
    signals: SignalField,
    grid: Grid,
    bodies: List<RigidBody>,
    pose: Pose?,
    structure: StructureMap,
): Int {
    val signalled = m.wiring.isOn(Action.Run, signals.at(m.center))
    if (signalled) return ApertureField.OPEN
    // Not signalled: normally it shuts. If sealed and a body overlaps, hold open — but only if the
    // airlock was already open at tick start (structure != Hull/Machine). If it was closed, it
    // stays closed regardless of body position (the "armed" behaviour).
    val wasOpen = structure[m.center.index] != Structure.Hull
    if (wasOpen && pose != null && bodyOverlaps(m, grid, bodies, pose)) return ApertureField.OPEN
    return 0
}

/**
 * Whether any rigid body's bounding box overlaps the footprint of [airlock] in grid coordinates.
 *
 * The airlock's footprint is a single tile at [Airlock.center]. A body overlaps if its axis-aligned
 * bounding box in grid space covers that tile. The body's centre of mass is converted to grid
 * coordinates using [shipPose], then expanded by half its width/height.
 */
fun bodyOverlaps(airlock: Airlock, grid: Grid, bodies: List<RigidBody>, shipPose: Pose): Boolean {
    for (body in bodies) {
        if (body.mass <= 0L) continue
        val overlaps = tileOverlapsRock(grid, airlock.center, body, body.pose, shipPose)
        if (overlaps) return true
    }
    return false
}

class ApertureField(
    private val edges: EdgeGrid,
    private val x: IntArray,
    private val y: IntArray,
) {

    fun xAt(edge: Int): Int = x[edge]
    fun yAt(edge: Int): Int = y[edge]

    fun isXOpen(edge: Int): Boolean = x[edge] > 0
    fun isYOpen(edge: Int): Boolean = y[edge] > 0

    fun copyX(): IntArray = x.copyOf()
    fun copyY(): IntArray = y.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ApertureField && edges == other.edges &&
                x.contentEquals(other.x) && y.contentEquals(other.y))

    override fun hashCode(): Int = 31 * x.contentHashCode() + y.contentHashCode()

    companion object {
        /** A completely unobstructed face. */
        const val OPEN = 1024

        /** A face nothing crosses: hull, machine casing, the inside of a wall. */
        const val CLOSED = 0

        /**
         * Apertures implied by the current [StructureMap]: shut wherever a face touches something
         * solid, fully open otherwise.
         *
         * Derived every tick, like the structure it comes from, because it is cheap and because
         * caching it and invalidating on edits is a class of bug for no gain — the same argument
         * [StructureMap] itself makes.
         *
         * **Boundary faces are open.** A face on the rim of the grid has a tile on one side and space
         * on the other, and the whole point of the fluid field is that gas leaving there does so at
         * a velocity and takes its momentum with it. What happens to that momentum is increment D's
         * business; that it can get out is this function's.
         *
         * Pipes will contribute here too, narrowing faces rather than blocking them. They do not
         * exist yet, so this reads structure alone.
         */
        fun derive(
            edges: EdgeGrid,
            structure: StructureMap,
            openness: IntArray? = null,
        ): ApertureField {
            val x = IntArray(edges.xEdgeCount)
            val y = IntArray(edges.yEdgeCount)

            for (e in 0 until edges.xEdgeCount) {
                x[e] = apertureBetween(structure, openness, edges.xEdgeBefore(e), edges.xEdgeAfter(e))
            }
            for (e in 0 until edges.yEdgeCount) {
                y[e] = apertureBetween(structure, openness, edges.yEdgeBefore(e), edges.yEdgeAfter(e))
            }
            return ApertureField(edges, x, y)
        }

        /** Every face open — the field with nothing built in it. For tests and for empty grids. */
        fun allOpen(edges: EdgeGrid): ApertureField = ApertureField(
            edges,
            IntArray(edges.xEdgeCount) { OPEN },
            IntArray(edges.yEdgeCount) { OPEN },
        )

        /**
         * Solid on either side shuts the face; the **narrower** side governs when both are open.
         *
         * A face is a throat, and a throat is as wide as its tightest point — so this is a minimum
         * and not, say, an average. Two half-open doors back to back pass what one of them does, not
         * what one and a half of them would.
         */
        private fun apertureBetween(
            structure: StructureMap,
            openness: IntArray?,
            before: TileIndex,
            after: TileIndex,
        ): Int = minOf(
            sideAperture(structure, openness, before),
            sideAperture(structure, openness, after),
        )

        /**
         * How wide one side of a face is open. Off the grid is space, which obstructs nothing.
         *
         * [openness] is consulted **before** the structure, and that order is the whole mechanism: an
         * open airlock has already been skipped by [StructureMap.derive], so its tile reads permeable
         * and would otherwise come back fully [OPEN], losing the grading. A shut one is not in the
         * array at all and falls through to the wall it is.
         */
        private fun sideAperture(structure: StructureMap, openness: IntArray?, tile: TileIndex): Int {
            if (tile == TileIndex.NONE) return OPEN
            val open = openness?.get(tile.index) ?: 0
            if (open > 0) return open
            return if (structure.blocksAir(tile)) CLOSED else OPEN
        }
    }
}
