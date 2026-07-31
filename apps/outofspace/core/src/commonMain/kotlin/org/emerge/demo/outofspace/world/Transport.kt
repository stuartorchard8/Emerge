package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.logistics.Packet

/**
 * Which transport network something belongs to.
 *
 * Four separate networks sharing one tile grid: a rail, a pipe, a power line and a signal line can
 * all cross the same tile without meeting, because they are different layers. That is the thing ONI
 * gets right and the reason it can build dense factories that are still readable — routing is a
 * puzzle about *each* network rather than one fight for floor space.
 *
 * Structure, heat and atmosphere read none of these. They only ever look at the deck.
 */
enum class Conduit(val label: String) {
    Rail("RAIL"),
    Pipe("PIPE"),
    Power("POWER"),
    Signal("SIGNAL"),
}

/**
 * A length of conveyor or pipe: one tile of one conduit layer.
 *
 * **A segment has no direction of its own.** That is the whole difference between this and the belt
 * it replaces. Which way material moves along a run is decided by where the sources and sinks on it
 * are — see [FlowField] — so laying track is laying *topology*, and reversing a line is a matter of
 * moving the machine that feeds it rather than rotating fifty tiles.
 *
 * A segment is also **inert**: it has no wiring and cannot be switched off. It is plumbing. The
 * things that make decisions are the buildings at the ends of it.
 */
data class Segment(
    val conduit: Conduit,
    /** What is riding on this tile. Partial packets are normal — see [org.emerge.demo.outofspace.logistics.mergeInto]. */
    val held: Packet? = null,
)

/**
 * Which way material moves on one conduit layer, derived from where its sources are.
 *
 * A breadth-first sweep outward from every output port gives each segment tile a **distance from the
 * nearest source**, and material flows from lower distance to higher. Nothing about a tile says which
 * way it points; the shape of the network says it.
 *
 * The important property is not the flow itself but the **order**. "The first input a packet reaches"
 * has to be a fact about the pipe's topology, and if runs were walked in grid order it would instead
 * be a fact about how the array is indexed — which is exactly the arbitrary junction priority this
 * project decided not to inherit. Walking outward from the source makes *upstream buildings starve
 * downstream ones* a real, discoverable, deterministic mechanic rather than a coin flip.
 */
class FlowField private constructor(
    private val distance: IntArray,
    private val successors: Array<IntArray>,
    /**
     * Segment tiles, **furthest from a source first**. Advancing in this order means a tile is
     * emptied before the one behind it tries to move into it, so a full run shuffles along by one in
     * a single pass instead of one tile per tick.
     */
    val order: IntArray,
) {
    /** Steps from the nearest source, or -1 for a tile no source can reach. */
    fun distanceAt(tile: Int): Int = if (tile in distance.indices) distance[tile] else -1

    fun isFed(tile: Int): Boolean = distanceAt(tile) >= 0

    /** The tiles material moves to from here. More than one is a fork. */
    fun successorsOf(tile: Int): IntArray =
        if (tile in successors.indices) successors[tile] else EMPTY

    companion object {
        private val EMPTY = IntArray(0)

        /**
         * @param isSegment whether a tile carries a segment of the layer being derived
         * @param sources tiles where material enters the layer — a building or bridge output port
         */
        fun derive(grid: Grid, isSegment: (Int) -> Boolean, sources: Collection<Int>): FlowField {
            val distance = IntArray(grid.size) { -1 }

            // Sorted, so a world with several sources produces the same field however the caller
            // happened to collect them.
            val queue = ArrayDeque<Int>()
            for (tile in sources.toSortedSet()) {
                if (tile in distance.indices && isSegment(tile) && distance[tile] < 0) {
                    distance[tile] = 0
                    queue.addLast(tile)
                }
            }
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (dir in Direction.ALL) {
                    val next = grid.neighbour(at, dir)
                    if (next < 0 || !isSegment(next) || distance[next] >= 0) continue
                    distance[next] = distance[at] + 1
                    queue.addLast(next)
                }
            }

            val successors = Array(grid.size) { EMPTY }
            for (tile in 0 until grid.size) {
                if (distance[tile] < 0) continue
                var found = 0
                val buffer = IntArray(4)
                for (dir in Direction.ALL) {
                    val next = grid.neighbour(tile, dir)
                    if (next >= 0 && distance[next] == distance[tile] + 1) buffer[found++] = next
                }
                if (found > 0) successors[tile] = buffer.copyOf(found)
            }

            val fed = (0 until grid.size).filter { distance[it] >= 0 }
            // Furthest first; ties by tile index so the order is total, not merely a partial one.
            val order = fed.sortedWith(compareByDescending<Int> { distance[it] }.thenBy { it })
            return FlowField(distance, successors, order.toIntArray())
        }
    }
}

/**
 * Where a fork last sent material, so the next packet goes the other way.
 *
 * ONI resolves a junction by whichever branch its iteration happens to favour, which is why priority
 * there is a folklore skill rather than a mechanic. A fork here is a **diverter**: it remembers, and
 * it alternates. That makes a three-way split genuinely even instead of evenly-ish, and it makes the
 * answer a property of the junction rather than of the array.
 *
 * Stored per fork tile, and only for tiles that actually fork, so this stays empty in almost every
 * world.
 */
class Diverters private constructor(internal val cursor: Map<Int, Int>) {

    /** Which branch this fork will try first. Zero for a tile that has never forked anything. */
    operator fun get(tile: Int): Int = cursor[tile] ?: 0

    val isEmpty: Boolean get() = cursor.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Diverters && cursor == other.cursor)

    override fun hashCode(): Int = cursor.hashCode()

    override fun toString(): String = "Diverters($cursor)"

    companion object {
        val EMPTY: Diverters = Diverters(emptyMap())
        fun of(cursor: Map<Int, Int>): Diverters = if (cursor.isEmpty()) EMPTY else Diverters(cursor.toMap())
    }
}

/** Mutable diverter cursors for one tick. */
class DiverterWork(diverters: Diverters) {
    private val cursor: MutableMap<Int, Int> = HashMap(diverters.cursor)

    /**
     * Picks a successor for a packet leaving [tile], preferring one that is free, and alternating
     * between them so a fork splits its throughput rather than favouring a branch.
     */
    fun choose(tile: Int, options: IntArray, isFree: (Int) -> Boolean): Int {
        if (options.isEmpty()) return -1
        if (options.size == 1) return if (isFree(options[0])) options[0] else -1
        val start = cursor[tile] ?: 0
        for (step in options.indices) {
            val pick = options[(start + step) % options.size]
            if (isFree(pick)) {
                // Advance past the branch actually *used*, not past the one we hoped to use. A
                // blocked branch must not consume its turn, or a jam on one side would quietly
                // halve the throughput of the other.
                cursor[tile] = (start + step + 1) % options.size
                return pick
            }
        }
        return -1
    }

    fun snapshot(): Diverters = Diverters.of(cursor)
}

/**
 * Moves everything on one conduit layer one step, offering each packet to whatever sits under it
 * first.
 *
 * The order is the whole design, and it is two rules:
 *
 *  1. **A tile's own port gets first refusal.** A packet passing under a building's input is taken
 *     if the building has room — that is what makes "the first input along the run wins" true, and
 *     with [FlowField.order] walking outward from the source it is a statement about the pipe rather
 *     than about the array.
 *  2. **Furthest from the source moves first**, so a packed run advances by one along its whole
 *     length in a single pass rather than crawling a tile per tick.
 *
 * Movement is into *free* tiles only. Packets do not coalesce as they travel — a lump catching up
 * with a slower one queues behind it, which is what makes a jam visible. Topping a partial packet up
 * is something a **source** does, not something travel does.
 *
 * @param absorb offered every packet on the tile it currently occupies; returns what is left, or
 *   null when the whole packet was taken.
 * @return the number of packets that moved, which is only useful for tests and diagnostics.
 */
fun advanceSegments(
    flow: FlowField,
    held: Array<Packet?>,
    diverters: DiverterWork,
    absorb: (tile: Int, packet: Packet) -> Packet?,
): Int {
    var moved = 0
    for (tile in flow.order) {
        val packet = held[tile] ?: continue

        val leftover = absorb(tile, packet)
        held[tile] = leftover
        if (leftover == null) continue

        val target = diverters.choose(tile, flow.successorsOf(tile)) { held[it] == null }
        if (target < 0) continue
        held[target] = leftover
        held[tile] = null
        moved++
    }
    return moved
}
