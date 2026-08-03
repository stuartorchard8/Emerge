package org.emerge.demo.outofspace.world

/**
 * Which way material moves on one conduit layer: **away from where it came in, toward anything that
 * will take it.**
 *
 * Two sweeps, and it needs both. Neither alone is enough, and the history of this file is three
 * attempts to make one of them do the whole job.
 *
 *  - **Depth**, breadth-first from every *output* port — how far a tile is from where material
 *    enters the layer. This is what "forward" means. A step is only legal if it increases depth by
 *    one, which makes the flow a directed acyclic graph and is the reason nothing can ever circle or
 *    oscillate.
 *  - **Distance**, breadth-first from every *input* port — how far a tile is from somewhere material
 *    can leave. This is what makes a step *worth taking*. A branch with no consumer on the end has no
 *    distance at all, so nothing enters it: dead ends stay empty because they are dead ends, not
 *    because anything checks for one.
 *
 * ### Why pulling alone could not do it
 *
 * Pulling was introduced to fix pushing, which fanned material into every branch it could reach and
 * piled it up at the ends of the ones leading nowhere. Pulling fixed that, but "move to the neighbour
 * closest to a consumer" cannot tell a **fork** from a **shortcut**. Where a line splits toward a
 * vent two tiles away and a tank three tiles away, every packet went to the vent — not by a rule
 * anybody wrote, but because one branch was nearer. [Diverters] existed the whole time to alternate
 * at exactly such a junction and had almost nothing to alternate between, because a fork only ever
 * produced two successors when the two branches were the same length to the tile.
 *
 * Symmetry like that cannot be broken by looking at the consumers, because the consumers are
 * symmetric. It is broken by knowing which way the material came in — which is a fact about the
 * source, and why the source sweep is back. Both branches of a real fork sit one step further from
 * the source, so both are legal, and the diverter gets its choice.
 *
 * The same knowledge is what makes forking *safe*. Offering "any neighbour that leads to some
 * consumer" without it lets two tiles each be a legal step for the other, and a packet between two
 * consumers walks back and forth forever. Requiring depth to increase makes that impossible by
 * construction rather than by a guard.
 *
 * ### A consumer that cannot take anything does not pull
 *
 * Inputs are split by whether they have room. A full one still counts, but only as a last resort —
 * see [FULL_PENALTY]. So traffic runs *past* a full machine to reach a working one, and when there
 * is nothing better anywhere it still travels to the blockage and packs in behind it, which is how a
 * jam stays visible on the deck instead of hiding inside the machine feeding the belt.
 *
 * ### Where forward runs out
 *
 * Depth is measured from every source at once, and that means a **merge** — a second producer
 * joining a run that already has one — does more than add material. The newcomer resets depth to
 * zero where it lands and inverts the gradient over everything upstream of it, so the two waves meet
 * at a tile with nothing deeper beside it. That tile has no forward at all, and because a branch
 * leading nowhere is not worth entering, the emptiness spreads back up the line until the first
 * producer has nowhere to put anything either. Half a factory goes quiet because somebody bridged
 * onto its belt.
 *
 * So forward is not the only rule. Where a tile's forward leads nowhere useful — a watershed like
 * that, or a run whose miner was just dismantled and which the source sweep never reached at all —
 * material falls back to the older rule and moves **downhill toward the nearest consumer**. The two
 * coexist deliberately, and the split is not a special case bolted on: it is the difference between
 * "material is being driven along here" and "material is finding its own way out", which really are
 * two situations with two right answers.
 *
 * The fallback is safe for the reason the old model was safe — the potential strictly decreases —
 * and it cannot be reached from a tile that has a working forward, because a tile uses one rule or
 * the other and never both.
 *
 * ### Order
 *
 * The important property is not the flow itself but the **order** tiles are walked in. "The first
 * input a packet reaches" has to be a fact about the pipe's topology; if runs were walked in grid
 * order it would instead be a fact about how the array is indexed, which is exactly the arbitrary
 * junction priority this project decided not to inherit.
 */
class FlowField private constructor(
    private val grid: Grid,
    private val distance: IntArray,
    private val successors: Array<IntArray>,
    /**
     * Segment tiles, **most downstream first**. Advancing in this order means the tile in front is
     * emptied before the one behind it tries to move into it, so a full run shuffles along by one in
     * a single pass instead of one tile per tick.
     *
     * "Downstream" is measured by whichever rule each tile actually moves by, because a tile ranked
     * one way and moved another gets visited after the packet it just received and moves it twice.
     */
    val order: IntArray,
) {
    /** Steps to the nearest sink, or -1 for a tile that can reach none. */
    fun distanceAt(tile: Int): Int = if (tile in distance.indices) distance[tile] else -1

    /**
     * True when this tile is part of a live flow: material on it can move on, or be taken where it
     * stands.
     *
     * Deliberately not "has a distance to some consumer". A dead-end spur can always reach one by
     * turning round and going back out the way it came in, so that question answers yes for exactly
     * the tiles the network is meant to leave alone.
     */
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

        /**
         * How much further away a consumer with no room counts as being.
         *
         * Larger than any path across any grid this game will have, so an accepting consumer
         * *anywhere* on a run beats a full one next door. It is a tie-break with a very heavy thumb
         * rather than an exclusion, which is the whole point — see [derive].
         */
        const val FULL_PENALTY = 1_000_000

        /**
         * @param isSegment whether a tile carries a segment of the layer being derived
         * @param linked whether the segment at a tile is joined to its neighbour in a direction.
         *   Asked in one direction only; the caller keeps links symmetric.
         * @param sources tiles where material **enters** the layer — an *output* port with track
         *   under it. These give the flow its direction, and are what lets a fork be a fork.
         * @param accepting tiles where material can leave the layer **right now** — an input port
         *   with track under it and room behind it.
         * @param full input ports with track under them and no room. These still pull, but only
         *   once nothing else will: material queues up behind a blockage rather than abandoning it,
         *   and that queue is how a jam stays visible.
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

            // Sorted, so a world with several sinks produces the same field however the caller
            // happened to collect them. `sorted()` rather than `toSortedSet()`: the latter is a
            // JVM-only extension, and this has to compile for JS and Android too.
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

            // Two tiers, and the order is the behaviour. Everything an accepting consumer can reach
            // is measured first, so a full machine that happens to sit on such a run comes out an
            // ordinary distance from a working one. Only then do the still-unreached full consumers
            // get seeded, far away, giving a line with nowhere else to go something to queue against.
            sweep(distance, accepting, 0, trackNearest = true)
            sweep(distance, full, FULL_PENALTY, trackNearest = true)
            sweep(depth, sources, 0, trackNearest = false)

            // ── The forward graph, and what each branch of it leads to ──
            //
            // Forward is away from the source: a step is legal only if it increases depth by one.
            // That makes this a DAG, which is what stops a packet circling, and it means both arms
            // of a genuine fork are legal where a shortest-path rule would have picked one.
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

            // Whether a branch is worth entering has to be asked **along the forward graph**, not
            // of the undirected one. A dead-end spur can always "reach" a consumer by turning round
            // and going back out the way it came in, so asking `distance >= 0` would send material
            // down it — the exact dead-end filling that pulling was introduced to stop.
            val acceptingSet = accepting.toHashSet()
            val fullSet = full.toHashSet()
            val reachesAccepting = BooleanArray(grid.size)
            val reachesAnything = BooleanArray(grid.size)
            // Deepest first, so every successor is settled before the tile that feeds it is asked.
            for (tile in (0 until grid.size).filter { depth[it] >= 0 }.sortedByDescending { depth[it] }) {
                reachesAccepting[tile] = tile in acceptingSet || forward[tile].any { reachesAccepting[it] }
                reachesAnything[tile] =
                    tile in acceptingSet || tile in fullSet || forward[tile].any { reachesAnything[it] }
            }

            /**
             * The older rule: step to a neighbour strictly closer to a consumer.
             *
             * Safe on its own terms — the potential falls every step, so nothing can circle — and
             * blind to forks, which is why it is no longer the main rule. It is still exactly right
             * wherever "forward" has nothing to say.
             */
            fun downhill(tile: Int): IntArray {
                if (distance[tile] < 0) return EMPTY
                if (distance[tile] == 0) {
                    // A tile at a sink is not the end of the road. Whatever the consumer there does
                    // not take carries on to the next one. Neighbours whose own nearest sink is
                    // *this* one are excluded, or material refused here would be sent to a tile that
                    // would only send it straight back.
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
                    // A branch leading to a working consumer beats one leading only to a blockage,
                    // and only when there is no such branch at all does the queue form.
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

            // Most-downstream first, so the tile ahead is empty by the time the one behind tries to
            // move up and a packed run advances by one along its whole length in a single pass.
            //
            // "Downstream" has to be measured the same way movement is, or a packet can be carried
            // several tiles in a single pass: walking a source-fed run in nearest-to-a-sink order
            // visits the fork *before* the tile it just moved into, and moves the same packet again.
            // So a fed tile is ranked by how far it is from the source, and one nothing feeds — which
            // moves by the fallback rule — by how close it is to a consumer.
            // A tile is ranked by the same measure it moves by, and the tiles moving downhill sort
            // ahead of the ones moving forward, because downhill is what the tail of a run does.
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
