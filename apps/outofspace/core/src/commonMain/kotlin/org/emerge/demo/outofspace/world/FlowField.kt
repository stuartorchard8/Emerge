package org.emerge.demo.outofspace.world

/**
 * Conduit layer flow direction: BFS from outputs (depth/forward) + BFS from inputs (distance).
 * Depth prevents cycles; distance prunes dead-ends.
 * Fallback: downhill toward nearest consumer (when forward leads nowhere).
 * Walk order: topological (DAG), not grid order (avoids arbitrary junction priority).
 */
class FlowField private constructor(
    private val grid: Grid,
    private val distance: IntArray,
    private val successors: Array<IntArray>,
    /** Segment tiles, most downstream first (single-pass shuffle). Downstream = whichever rule each tile actually moves by. */
    val order: IntArray,
) {
    /** Steps to the nearest sink, or -1 for a tile that can reach none. */
    fun distanceAt(tile: Int): Int = if (tile in distance.indices) distance[tile] else -1

    /** Part of live flow (has successors or distance=0). Not "has distance to any consumer" — dead-end spurs can always turn back. */
    fun isFed(tile: Int): Boolean = successorsOf(tile).isNotEmpty() || distanceAt(tile) == 0

    /** The tiles material moves to from here. More than one is a fork. */
    fun successorsOf(tile: Int): IntArray =
        if (tile in successors.indices) successors[tile] else EMPTY

    /**
     * Which way you face going from [from] to the adjacent [to]. Only ever asked of a step the
     * field itself produced, so the two really are neighbours and the answer always exists.
     */
    fun directionBetween(from: Int, to: Int): Direction =
        Direction.ALL.first { grid.neighbour(from, it) == to }

    companion object {
        private val EMPTY = IntArray(0)

        /** Full consumer penalty (larger than any grid path — accepting anywhere beats full next door). */
        const val FULL_PENALTY = 1_000_000

        /**
         * Derive flow field.
         * @param sources output ports (give direction, enable forks)
         * @param accepting input ports with room
         * @param full input ports without room (pull only as last resort)
         */
        fun derive(
            grid: Grid,
            isSegment: (Int) -> Boolean,
            linked: (tile: Int, dir: Direction) -> Boolean,
            accepting: Collection<Int>,
            full: Collection<Int> = emptyList(),
            sources: Collection<Int> = emptyList(),
        ): FlowField {
            val distance = IntArray(grid.size) { -1 }
            /** Which sink each tile's shortest path leads to. Only read for tiles *at* a sink. */
            val nearest = IntArray(grid.size) { -1 }
            /** Steps from where material enters the layer. This is what "forward" means. */
            val depth = IntArray(grid.size) { -1 }

            val queue = ArrayDeque<Int>()

            // Sorted for determinism (JS/Android compatibility).
            fun sweep(field: IntArray, tiles: Collection<Int>, from: Int, trackNearest: Boolean) {
                for (tile in tiles.distinct().sorted()) {
                    if (tile in field.indices && isSegment(tile) && field[tile] < 0) {
                        field[tile] = from
                        if (trackNearest) nearest[tile] = tile
                        queue.addLast(tile)
                    }
                }
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    for (dir in Direction.ALL) {
                        if (!linked(at, dir)) continue
                        val next = grid.neighbour(at, dir)
                        if (next < 0 || !isSegment(next) || field[next] >= 0) continue
                        field[next] = field[at] + 1
                        if (trackNearest) nearest[next] = nearest[at]
                        queue.addLast(next)
                    }
                }
            }

            // Two tiers: accepting first, then full (penalised distance).
            sweep(distance, accepting, 0, trackNearest = true)
            sweep(distance, full, FULL_PENALTY, trackNearest = true)
            sweep(depth, sources, 0, trackNearest = false)

            // ── Forward graph (DAG: prevents circling, preserves forks) ──
            val forward = Array(grid.size) { EMPTY }
            for (tile in 0 until grid.size) {
                if (depth[tile] < 0) continue
                val onward = Direction.ALL.mapNotNull { dir ->
                    if (!linked(tile, dir)) return@mapNotNull null
                    val next = grid.neighbour(tile, dir)
                    if (next < 0 || depth[next] != depth[tile] + 1) null else next
                }
                if (onward.isNotEmpty()) forward[tile] = onward.sorted().toIntArray()
            }

            // Branch worthiness asked along forward graph (not undirected — avoids dead-end filling).
            val acceptingSet = accepting.toHashSet()
            val fullSet = full.toHashSet()
            val reachesAccepting = BooleanArray(grid.size)
            val reachesAnything = BooleanArray(grid.size)
            // Deepest first.
            for (tile in (0 until grid.size).filter { depth[it] >= 0 }.sortedByDescending { depth[it] }) {
                reachesAccepting[tile] = tile in acceptingSet || forward[tile].any { reachesAccepting[it] }
                reachesAnything[tile] =
                    tile in acceptingSet || tile in fullSet || forward[tile].any { reachesAnything[it] }
            }

            /** Older rule: step to neighbour closer to consumer. */
            fun downhill(tile: Int): IntArray {
                if (distance[tile] < 0) return EMPTY
                if (distance[tile] == 0) {
                    // Sink tile: refused material carries to next. Exclude nearest = this.
                    return Direction.ALL.mapNotNull { dir ->
                        if (!linked(tile, dir)) return@mapNotNull null
                        val next = grid.neighbour(tile, dir)
                        if (next < 0 || distance[next] < 0 || nearest[next] == tile) null else next
                    }.sortedWith(compareBy<Int> { distance[it] }.thenBy { it }).toIntArray()
                }
                var found = 0
                val buffer = IntArray(4)
                for (dir in Direction.ALL) {
                    if (!linked(tile, dir)) continue
                    val next = grid.neighbour(tile, dir)
                    if (next >= 0 && distance[next] == distance[tile] - 1) buffer[found++] = next
                }
                return buffer.copyOf(found)
            }

            val successors = Array(grid.size) { EMPTY }
            /** Which of the two rules each tile ended up moving by. Read only to build [order]. */
            val movesForward = BooleanArray(grid.size)
            for (tile in 0 until grid.size) {
                if (depth[tile] >= 0) {
                    // Working consumer branch > blockage branch > queue forms.
                    val useful = forward[tile].filter { reachesAccepting[it] }
                    val chosen = useful.ifEmpty { forward[tile].filter { reachesAnything[it] } }
                    if (chosen.isNotEmpty()) {
                        successors[tile] = chosen.toIntArray()
                        movesForward[tile] = true
                        continue
                    }
                }
                val onward = downhill(tile)
                if (onward.isNotEmpty()) successors[tile] = onward
            }

            // Most-downstream first (single-pass advance). Rank: forward tiles last (downhill = tail of run).
            val fed = (0 until grid.size).filter { distance[it] >= 0 || depth[it] >= 0 }
            val order = fed.sortedWith(
                compareBy<Int> { if (movesForward[it]) 1 else 0 }
                    .thenBy { if (movesForward[it]) -depth[it] else distance[it] }
                    .thenBy { it },
            )
            return FlowField(grid, distance, successors, order.toIntArray())
        }
    }
}
