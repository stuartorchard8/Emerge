package org.emerge.demo.outofspace.world

/**
 * Which signal network each tile is on — the connected components of the [Conduit.Signal] layer.
 *
 * This is the object that replaces [Channel]. A channel was a network too, but there were exactly
 * six of them, they were named by colour, and every machine was on all six at once. A network here
 * is defined by geometry instead: two machines share a signal only if a run of wire actually joins
 * them, and there are as many networks as the player has laid separate runs.
 *
 * **Undirected and instantaneous.** One component is one value, everywhere on it, in the tick it is
 * set. There is no travel time and no direction — a signal is a *reading*, it duplicates freely by
 * definition, and nothing on a wire has to take turns. That is what makes this a plain
 * connected-components sweep and not [FlowGraph], which is a one-way permission graph precisely
 * because packets are physical objects that must not be duplicated.
 *
 * Derived every tick, for [StructureMap]'s reason: it is cheap next to the rest of a tick, and
 * caching it would mean invalidating it on every edit, which is a bug class for no gain.
 */
class SignalNetworks private constructor(private val idOf: IntArray, val count: Int) {

    /** The network on [tile], or -1 where no wire is laid. */
    operator fun get(tile: Int): Int = if (tile in idOf.indices) idOf[tile] else -1

    override fun equals(other: Any?): Boolean =
        this === other || (other is SignalNetworks && idOf.contentEquals(other.idOf))

    override fun hashCode(): Int = idOf.contentHashCode()

    override fun toString(): String = "SignalNetworks($count)"

    companion object {
        /** No wire anywhere. */
        fun none(tileCount: Int): SignalNetworks = SignalNetworks(IntArray(tileCount) { -1 }, 0)

        /**
         * Components over the signal layer's **explicit links**, not over adjacency. Two runs laid
         * side by side down one corridor are two circuits, exactly as two rails alongside each other
         * are two lines — see [Segment.links].
         *
         * Networks are numbered by their **lowest tile index**, which falls out of sweeping tiles in
         * ascending order and starting a new component at the first unassigned one. Deterministic
         * numbering is not tidiness: a network id feeds machine activation, which feeds the sim, so a
         * numbering that depended on iteration order would be a save that does not reproduce.
         */
        fun derive(grid: Grid, conduits: Conduits): SignalNetworks {
            val layer = conduits[Conduit.Signal]
            val idOf = IntArray(grid.size) { -1 }
            var next = 0
            val stack = ArrayDeque<Int>()

            for (seed in layer.indices) {
                if (layer[seed] == null || idOf[seed] >= 0) continue
                val id = next++
                idOf[seed] = id
                stack.addLast(seed)
                // Explicit stack rather than recursion: a run can be as long as the grid, and this
                // also has to run on JS.
                while (stack.isNotEmpty()) {
                    val at = stack.removeLast()
                    val segment = layer[at] ?: continue
                    for (dir in Direction.ALL) {
                        if (!segment.linkedTo(dir)) continue
                        val to = grid.neighbour(at, dir)
                        if (to < 0 || layer[to] == null || idOf[to] >= 0) continue
                        idOf[to] = id
                        stack.addLast(to)
                    }
                }
            }
            return SignalNetworks(idOf, next)
        }
    }
}
