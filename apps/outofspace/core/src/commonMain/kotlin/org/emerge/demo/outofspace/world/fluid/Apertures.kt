package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.world.StructureMap

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
        fun derive(edges: EdgeGrid, structure: StructureMap): ApertureField {
            val x = IntArray(edges.xEdgeCount)
            val y = IntArray(edges.yEdgeCount)

            for (e in 0 until edges.xEdgeCount) {
                x[e] = apertureBetween(structure, edges.xEdgeBefore(e), edges.xEdgeAfter(e))
            }
            for (e in 0 until edges.yEdgeCount) {
                y[e] = apertureBetween(structure, edges.yEdgeBefore(e), edges.yEdgeAfter(e))
            }
            return ApertureField(edges, x, y)
        }

        /** Every face open — the field with nothing built in it. For tests and for empty grids. */
        fun allOpen(edges: EdgeGrid): ApertureField = ApertureField(
            edges,
            IntArray(edges.xEdgeCount) { OPEN },
            IntArray(edges.yEdgeCount) { OPEN },
        )

        /** Solid on either side shuts the face. Off the grid is space, which obstructs nothing. */
        private fun apertureBetween(structure: StructureMap, before: Int, after: Int): Int {
            if (before >= 0 && structure.isImpermeable(before)) return CLOSED
            if (after >= 0 && structure.isImpermeable(after)) return CLOSED
            return OPEN
        }
    }
}
