package org.emerge.demo.outofspace.world

/**
 * A distinct source→sink path discovered during graph construction.
 *
 * Each flow represents one connected component of rail with a clear direction: material enters at
 * [source] and is consumed at [sink]. The hop count ([distance]) is the number of tiles from
 * source to sink along this flow.
 */
data class Flow(
    val id: Int,
    val source: Int,
    val sink: Int,
    val distance: Int,
)

/**
 * A directed edge along a specific flow.
 *
 * When a tile has multiple FlowEdges as successors, the round-robin cursor on [flow.id] decides
 * which edge to use next tick.
 */
data class FlowEdge(
    val to: Int,
    val flow: Flow,
)

/**
 * The rail flow graph for a single connected component.
 *
 * Built once when conduits change, reused every tick. Contains the topological structure (sources,
 * sinks, flows, successors) and is immutable between conduit mutations.
 *
 * Build via [FlowGraph.build] — there is no public constructor to enforce this.
 */
class FlowGraph internal constructor(
    private val _tiles: Set<Int>,
    private val _sinks: Set<Int>,
    private val _order: List<Int>,
    private val _successors: Map<Int, List<FlowEdge>>,
    private val _distanceMap: Map<Int, Int>,
    private val _depthMap: Map<Int, Int>,
    private val _grid: Grid,
) {
    val tiles: Set<Int> get() = _tiles
    val sinks: Set<Int> get() = _sinks
    val order: List<Int> get() = _order

    fun isFed(tile: Int): Boolean =
        tile in tiles && (successorsOf(tile).isNotEmpty() || tile in sinks)

    fun successorsOf(tile: Int): List<FlowEdge> = _successors[tile] ?: emptyList()

    fun distanceAt(tile: Int): Int = _distanceMap[tile] ?: -1

    fun depthAt(tile: Int): Int = _depthMap[tile] ?: -1

    fun directionBetween(from: Int, to: Int): Direction =
        Direction.ALL.first { _grid.neighbour(from, it) == to }

    companion object {
        /**
         * Build the rail flow graph for a single connected component.
         *
         * Direction is pull-based: material flows toward sinks. Distance is measured from the
         * nearest sink. Sources only determine which tiles are "fed" — they don't affect the
         * direction of flow.
         */
        fun build(
            tileSet: Set<Int>,
            sources: Set<Int>,
            sinks: Set<Int>,
            linked: (Int, Direction) -> Boolean,
            grid: Grid,
        ): FlowGraph {
            if (tileSet.isEmpty()) return empty()

            val tileSources = BooleanArray(grid.size*4) { false }
            val tileDestinations = BooleanArray(grid.size*4) { false }
            val hungry = BooleanArray(grid.size) { true }
            for (source in sources) {
                hungry[source] = false
            }

            // BFS from sinks
            for (sink in sinks) {
                if (sink in tileSet) {
                    val queue = ArrayDeque<Int>()
                    queue.addLast(sink)
                    while (queue.isNotEmpty()) {
                        val at = queue.removeFirst()

                        // Have no cells leading into this one passed a source?
                        var isHungry = true
                        for (i in Direction.ALL.indices) {
                            val dir = Direction.ALL[i]
                            if (tileDestinations[at * 4 + i]) {
                                val prev = grid.neighbour(at, dir)
                                if (!hungry[prev]) {
                                    isHungry = false
                                }
                            }
                        }

                        // Have we been here already while hungry?
                        if (hungry[at]) {
                            if (tileSources[at * 4 + 0] ||
                                tileSources[at * 4 + 1] ||
                                tileSources[at * 4 + 2] ||
                                tileSources[at * 4 + 3]
                            ) continue
                        }

                        for (i in Direction.ALL.indices) {
                            val dir = Direction.ALL[i]
                            if (!linked(at, dir)) continue
                            val next = grid.neighbour(at, dir)
                            if (next < 0 || next !in tileSet) continue

                            tileSources[at * 4 + i] = true
                            tileDestinations[next * 4 + i] = true

                            queue.addLast(next)
                        }
                    }
                }
            }

            // TODO: Translate the arrays into a flowgraph

            return FlowGraph(
                tileSet, sinks, ordered, successors, distMap, depMap, grid
            )
        }

        private fun empty(): FlowGraph {
            val emptyGrid = Grid(0, 0)
            return FlowGraph(
                emptySet(), emptySet(), emptyList(),
                emptyMap(), emptyMap(), emptyMap(), emptyGrid
            )
        }
    }
}

/**
 * Round-robin cursors for the flow graph.
 *
 * [forkCursors] maps a tile (where the fork decision is made) to the index into that tile's
 * successors list. This is the only mutable state in the rail advancement system. The FlowGraph
 * itself is immutable between conduit changes.
 *
 * Only advances the cursor when a branch is actually used (not when blocked) — a jam on one side
 * must not quietly halve the throughput of the other.
 */
class FlowCursors(
    initial: Map<Int, Int> = emptyMap(),
) {
    internal val cursors = HashMap<Int, Int>(initial)

    /** Read-only view of fork cursor state. */
    val forkCursors: Map<Int, Int> get() = cursors

    /**
     * Pick a successor for a packet leaving [tile], preferring one that is free, and alternating
     * between them so a fork splits its throughput rather than favouring a branch.
     */
    fun choose(tile: Int, successors: List<FlowEdge>, isFree: (Int) -> Boolean): Int {
        if (successors.isEmpty()) return -1
        if (successors.size == 1) {
            val target = successors[0].to
            return if (isFree(target)) target else -1
        }
        val start = cursors[tile] ?: 0
        for (step in successors.indices) {
            val pick = successors[(start + step) % successors.size]
            if (isFree(pick.to)) {
                // Advance past the branch actually *used*, not past the one we hoped to use.
                cursors[tile] = (start + step + 1) % successors.size
                return pick.to
            }
        }
        return -1
    }

    /** Snapshot the current cursor state for persistence across ticks. */
    fun snapshot(): Map<Int, Int> = cursors.toMap()

    /** Restore from a previously snapshot state. */
    fun restore(map: Map<Int, Int>) {
        cursors.clear()
        cursors.putAll(map)
    }
}

/** Backward-compatible choose for IntArray-based successor lists. */
fun FlowCursors.chooseInt(tile: Int, options: IntArray, isFree: (Int) -> Boolean): Int {
    if (options.isEmpty()) return -1
    if (options.size == 1) return if (isFree(options[0])) options[0] else -1
    val start = cursors[tile] ?: 0
    for (step in options.indices) {
        val pick = options[(start + step) % options.size]
        if (isFree(pick)) {
            cursors[tile] = (start + step + 1) % options.size
            return pick
        }
    }
    return -1
}

/**
 * The rail flow field — a facade over one or more [FlowGraph] components.
 *
 * Each connected component of rail with at least one source and one sink becomes its own graph.
 * Tiles in components with no valid path are simply absent from all graphs.
 *
 * This is the entry point for the transport layer: [derive] builds the field from a set of rail
 * tiles, their connectivity, and the locations of sources (producers) and sinks (consumers).
 */
class FlowField private constructor(
    private val graphs: List<FlowGraph>,
    private val _grid: Grid,
) {
    /** All tiles that appear in any flow graph. */
    val allTiles: Set<Int> = graphs.flatMap { it.tiles }.toSet()

    /** Order of every fed tile across all components (upstream first). */
    val order: List<Int> = graphs.flatMap { it.order }

    fun isFed(tile: Int): Boolean = graphs.any { it.isFed(tile) }

    fun distanceAt(tile: Int): Int = graphs.find { tile in it.tiles }?.distanceAt(tile) ?: -1

    /**
     * Successors of [tile] as edges (with flow metadata).
     */
    fun successorsOf(tile: Int): List<FlowEdge> {
        for (g in graphs) {
            if (tile in g.tiles) {
                return g.successorsOf(tile)
            }
        }
        return emptyList()
    }

    /**
     * Successor tile IDs only (for tests that assert on tile indices).
     */
    fun successorTiles(tile: Int): List<Int> = successorsOf(tile).map { it.to }

    /**
     * Direction from [from] to [to] tile.
     */
    fun directionBetween(from: Int, to: Int): Direction =
        Direction.ALL.first { _grid.neighbour(from, it) == to }

    companion object {
        fun derive(
            grid: Grid,
            isSegment: (Int) -> Boolean,
            linked: (Int, Direction) -> Boolean,
            accepting: List<Int>,
            full: List<Int> = emptyList(),
            from: List<Int> = emptyList(),
        ): FlowField {
            val tileSet = mutableSetOf<Int>()
            for (i in 0 until grid.size) {
                if (isSegment(i)) tileSet.add(i)
            }
            if (tileSet.isEmpty()) return empty()

            var parent = IntArray(grid.size) { it }
            fun find(x: Int): Int {
                var root = x
                while (parent[root] != root) root = parent[root]
                var curr = x
                while (parent[curr] != root) { val p = parent[curr]; parent[curr] = root; curr = p }
                return root
            }
            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }

            for (tile in tileSet) {
                for (dir in Direction.ALL) {
                    if (!linked(tile, dir)) continue
                    val next = grid.neighbour(tile, dir)
                    if (next >= 0 && isSegment(next)) {
                        union(tile, next)
                    }
                }
            }

            val components = mutableMapOf<Int, MutableSet<Int>>()
            for (tile in tileSet) {
                val root = find(tile)
                components.getOrPut(root) { mutableSetOf() }.add(tile)
            }

            val sinks = (accepting + full).filter { it in tileSet }.toSet()
            val sources = from.filter { it in tileSet }.toSet()

            val graphs = mutableListOf<FlowGraph>()
            for ((root, compTiles) in components) {
                val compSources = compTiles.intersect(sources)
                val compSinks = compTiles.intersect(sinks)
                // Skip components that can't reach any sink (no route to a consumer)
                if (compSinks.isEmpty()) continue

                graphs.add(
                    FlowGraph.build(
                        tileSet = compTiles,
                        sources = compSources,
                        sinks = compSinks,
                        linked = linked,
                        grid = grid,
                    )
                )
            }

            return FlowField(graphs, grid)
        }

        private fun empty(): FlowField = FlowField(emptyList(), Grid(0, 0))
    }
}

