package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.DeckArray

/**
 * **What is wired to what**, and nothing yet about what flows through it.
 *
 * Increment 1 of `PLAN_power_network.md`. This is the electrical contact graph, and it is the
 * thermal one — [stepSolidHeat]'s — over the same bodies, with the same [seriesConductance] edges,
 * differing in exactly the places the plan's decisions name and nowhere else.
 *
 * ### ⭐ Why there is no second network to build
 *
 * Wiedemann–Franz relates a metal's thermal and electrical conductivity through one constant, so the
 * electrical network *is* the thermal contact graph with proportional edge weights. `bodiesOf`
 * already enumerates every solid thing aboard — hull plates, machine casings, conduit fittings on
 * every layer — and [stepSolidHeat] already joins them. There is a second quantity to put on that
 * graph, not a second graph.
 *
 * ### The three rules, and the one that is different
 *
 * | | thermal | electrical |
 * |---|---|---|
 * | casings across faces | joined | joined |
 * | fittings along drawn links | joined | joined |
 * | **bodies sharing a tile** | **always joined** | **joined only where a terminal stands** |
 *
 * ⛔ **The third row is the whole design.** Taken literally, a power run would short to the deck
 * plate it crosses and every wired machine would bond to the hull through its own chassis. Gating it
 * on a [TerminalRole] makes wiring a decision: a rail crossing a power run is two circuits, so
 * crossings are free, and the player chooses their conductor.
 *
 * ### What is not here
 *
 * Cargo lumps and buffer stores carry no charge, ghosts carry none, and neither do insulators or
 * rigid bodies — all four fall out of [Body.electricalConductance] being zero rather than out of a
 * test written here, which is where they belong: they are facts about matter.
 *
 * ⚠️ **Air is not a node at all.** It is one in the thermal graph, and letting it be one here would
 * be modelling an arc across a gap. `PLAN_power_network.md` §10.
 */
class Circuit internal constructor(
    /** Body index → node id, or [NOT_CONDUCTING]. */
    private val node: IntArray,
    /** How many bodies conduct — the size of every per-node array. */
    val nodeCount: Int,
    /** Edge endpoints, as node ids. */
    val edgeA: IntArray,
    val edgeB: IntArray,
    /** What each edge conducts, harmonic-mean of its two ends. */
    val edgeG: LongArray,
    /** Node id → component id. */
    private val component: IntArray,
    /** How many separate circuits the vessel has. */
    val componentCount: Int,
) {
    val edgeCount: Int get() = edgeA.size

    /** The node [body] became, or [NOT_CONDUCTING] if a current cannot cross it. */
    fun nodeOfBody(body: Int): Int = node[body]

    /** Which circuit [body] is part of, or [NOT_CONDUCTING] if it is part of none. */
    fun circuitOfBody(body: Int): Int {
        val n = node[body]
        return if (n == NOT_CONDUCTING) NOT_CONDUCTING else component[n]
    }

    /** Which circuit a node is part of. */
    fun circuitOfNode(n: Int): Int = component[n]

    /**
     * Whether two bodies are joined by any path of conductor.
     *
     * ⚠️ **False when either does not conduct**, which is not the same as "they are apart" and is
     * the honest answer to a question about a firebrick wall.
     */
    fun joined(bodyA: Int, bodyB: Int): Boolean {
        val a = circuitOfBody(bodyA)
        val b = circuitOfBody(bodyB)
        return a != NOT_CONDUCTING && a == b
    }

    companion object {
        /** What a body that carries no current answers to every question here. */
        const val NOT_CONDUCTING = -1
    }
}

/**
 * Which tiles bond the layers under them — one pass over the deck, asked of every machine's
 * declared [TerminalRole]s.
 *
 * ⚠️ **A ghost's terminals do not count.** A machine that has not been built cannot bond anything,
 * for the same reason its casing conducts nothing: there is no metal in it yet.
 */
fun terminalTiles(grid: Grid, deck: DeckArray): BooleanArray {
    val out = BooleanArray(grid.size)
    for (tile in grid.tiles) {
        val m = deck[tile] ?: continue
        if (deck.isGhost(tile)) continue
        for (role in TerminalRole.entries) {
            val at = terminalTile(grid, m, tile, role) ?: continue
            out[at.index] = true
        }
    }
    return out
}

/**
 * Build the electrical contact graph over [bodies].
 *
 * [terminals] is by tile — see [terminalTiles]. Passed in rather than looked up so that this takes
 * no deck and a test can state a circuit without standing a machine up to do it.
 *
 * ⚠️ **Decided from the bodies it is given and nothing else.** Rebuilt each tick alongside them, for
 * the reason `bodiesOf` is rebuilt: *"a cache with an invalidation rule is a bug waiting for an edit
 * case nobody thought of."*
 */
fun circuitOf(grid: Grid, bodies: List<Body>, terminals: BooleanArray): Circuit {
    val node = IntArray(bodies.size) { Circuit.NOT_CONDUCTING }
    var nodeCount = 0
    for (i in bodies.indices) {
        if (bodies[i].electricalConductance > 0L) node[i] = nodeCount++
    }
    if (nodeCount == 0) {
        return Circuit(node, 0, IntArray(0), IntArray(0), LongArray(0), IntArray(0), 0)
    }

    val tiles = TileBodies(grid.size, bodies)
    val edgeA = ArrayList<Int>()
    val edgeB = ArrayList<Int>()
    val edgeG = ArrayList<Long>()
    val union = UnionFind(nodeCount)

    fun join(a: Int, b: Int) {
        val ka = bodies[a].electricalConductance
        val kb = bodies[b].electricalConductance
        val g = seriesConductance(ka, kb)
        if (g <= 0L) return
        edgeA.add(node[a]); edgeB.add(node[b]); edgeG.add(g)
        union.union(node[a], node[b])
    }

    for (b in bodies.indices) {
        if (node[b] == Circuit.NOT_CONDUCTING) continue
        val body = bodies[b]

        // ── Casings across faces. One continuous conductor per run of touching plate, which is what
        // makes a metal hull a single node and what joins two machines that merely stand next to
        // each other. Circuits join through *placement*, not only through wiring.
        if (body.slot == BodySlot.DeckStore) {
            for (dir in Direction.ALL) {
                val next = grid.neighbour(body.tile, dir)
                if (next == TileIndex.NONE) continue
                for (i in tiles.startOf(next) until tiles.endOf(next)) {
                    val other = tiles.id(i)
                    // Each unordered pair once.
                    if (other <= b) continue
                    if (node[other] == Circuit.NOT_CONDUCTING) continue
                    if (bodies[other].slot != BodySlot.DeckStore) continue
                    join(b, other)
                }
            }
        }

        // ── Fittings along drawn links, on their own layer. ⚠️ Per layer, exactly as heat runs: a
        // rail crossing a power run conducts *through the tile* if a terminal stands there and never
        // *along* the other run.
        if (body.slot == BodySlot.Fitting && body.conduit != null) {
            for (dir in Direction.ALL) {
                if (!body.linkedTo(dir)) continue
                val next = grid.neighbour(body.tile, dir)
                if (next == TileIndex.NONE) continue
                val other = tiles.fittingAt(body.conduit, next)
                if (other < 0 || other <= b) continue
                if (node[other] == Circuit.NOT_CONDUCTING) continue
                join(b, other)
            }
        }
    }

    // ── The rod. Everything standing on a terminal tile is bonded to everything else standing on
    // it, which is the one rule the thermal graph applies everywhere and this one applies here.
    // ⚠️ Weighted like any other join: the rod is assumed not to be the bottleneck, so the worse of
    // the two things it bonds still governs.
    for (tile in grid.tiles) {
        if (!terminals[tile.index]) continue
        val from = tiles.startOf(tile)
        val to = tiles.endOf(tile)
        for (i in from until to) {
            val a = tiles.id(i)
            if (node[a] == Circuit.NOT_CONDUCTING) continue
            for (j in i + 1 until to) {
                val b = tiles.id(j)
                if (node[b] == Circuit.NOT_CONDUCTING) continue
                join(a, b)
            }
        }
    }

    // ── Components, numbered in node order so the answer does not depend on which root won.
    val label = IntArray(nodeCount) { Circuit.NOT_CONDUCTING }
    var components = 0
    val component = IntArray(nodeCount)
    for (n in 0 until nodeCount) {
        val root = union.find(n)
        if (label[root] == Circuit.NOT_CONDUCTING) label[root] = components++
        component[n] = label[root]
    }

    return Circuit(
        node, nodeCount,
        IntArray(edgeA.size) { edgeA[it] },
        IntArray(edgeB.size) { edgeB[it] },
        LongArray(edgeG.size) { edgeG[it] },
        component, components,
    )
}

/** Disjoint set with path halving and union by size — enough for one pass over a vessel's bodies. */
private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }
    private val count = IntArray(size) { 1 }

    fun find(x: Int): Int {
        var i = x
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]
            i = parent[i]
        }
        return i
    }

    fun union(a: Int, b: Int) {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        val (big, small) = if (count[ra] >= count[rb]) ra to rb else rb to ra
        parent[small] = big
        count[big] += count[small]
    }
}
