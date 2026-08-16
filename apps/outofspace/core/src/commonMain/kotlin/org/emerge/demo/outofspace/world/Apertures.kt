package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Machine

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
fun airlockOpenness(machines: List<Machine?>, signals: SignalField): IntArray? {
    var openness: IntArray? = null
    for (i in machines.indices) {
        val m = machines[i]
        if (m !is Airlock) continue
        val activation = m.wiring.activation(Action.Run, signals.at(TileIndex(i)))
        if (activation <= 0) continue
        val array = openness ?: IntArray(machines.size).also { openness = it }
        array[i] = activation * ApertureField.OPEN / SignalField.FULL
    }
    return openness
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
            return if (structure.isImpermeable(tile)) CLOSED else OPEN
        }
    }
}
