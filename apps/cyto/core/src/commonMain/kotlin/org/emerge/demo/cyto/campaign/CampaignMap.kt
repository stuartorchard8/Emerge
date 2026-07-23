package org.emerge.demo.cyto.campaign

/**
 * The campaign as a **map**: which chapters the player can see, where they sit relative to each other, and
 * what leads to what. Pure layout — no rendering, no file IO — so the shape of the thing can be tested
 * without a screen (`CytoMenu` draws it, `CampaignMapTest` checks it).
 *
 * ## What is visible
 *
 * The map is a reveal, not a table of contents. Three states, and nothing else exists as far as the player
 * is concerned:
 *
 *  - [State.Completed] / [State.Available] — **unlocked**: named, clickable, connected up to whatever led
 *    there. Unlocked means some chapter leading here is completed, or nothing leads here at all (a root).
 *  - [State.Ghost] — one layer further out: a chapter that something visible leads to, drawn as an unnamed
 *    marker. The player learns *that there is something there* and, where a chapter branches, **how many**
 *    somethings — which is the whole point at a fork, since a fork you cannot see is just a corridor. They
 *    do not learn what it is called or what it is about.
 *
 * Anything beyond that layer is absent: no node, no edge, no gap where one would be.
 *
 * ## Layout
 *
 * Depth is the **longest** path from a root, so a chapter that several routes reach sits below all of them
 * rather than jumping up next to the shortest one. Horizontally each node owns a **band** of the width
 * ([x] is its centre, [span] its share), split evenly among its children — so a fork divides the space and
 * everything downstream of it stays on its own side, instead of drifting back to the middle whenever a row
 * happens to hold one node. That is what makes the picture read as "these are two different roads".
 */
class CampaignMap private constructor(
    val nodes: List<Node>,
    /** Parent → child, as indices into [nodes]. Both ends are always present in [nodes]. */
    val edges: List<Edge>,
) {
    enum class State { Completed, Available, Ghost }

    class Node(
        val id: String,
        /** The authored chapter — **null for a [State.Ghost]**, whose whole point is that the player has no
         *  access to it. Nothing about a ghost but its existence may be drawn, so nothing else is offered. */
        val chapter: Chapter?,
        val state: State,
        /** Row: the longest path from a root. */
        val depth: Int,
        /** Horizontal centre as a fraction of the map's width (0..1), and the width of the band this node
         *  owns. Fractions rather than pixels: placement is the map's business, sizing is the caller's. */
        val x: Float,
        val span: Float,
    ) {
        val revealed: Boolean get() = chapter != null
        /** What to draw on it: the title, or the fact that it is unknown. */
        val label: String get() = chapter?.title ?: "???"
    }

    class Edge(val from: Int, val to: Int)

    /** The node the player should be looking at: the first available (not yet completed) chapter, else the
     *  last completed one. Null on an empty map. Lets the menu open with something obvious to click. */
    val current: Node?
        get() = nodes.firstOrNull { it.state == State.Available }
            ?: nodes.lastOrNull { it.state == State.Completed }

    val depthCount: Int get() = (nodes.maxOfOrNull { it.depth } ?: -1) + 1

    companion object {
        /**
         * Build the visible map for [chapters] given which of them are [completed].
         *
         * [successors] is how the graph is read (defaults to the authored `branchesTo`-or-next-in-list rule
         * that `CampaignContent.successorsOf` implements), injected so a test can state a graph directly
         * instead of authoring chapters to imply one.
         */
        fun build(
            chapters: List<Chapter>,
            completed: (String) -> Boolean,
            successors: (String) -> List<String> = { id -> defaultSuccessors(chapters, id) },
        ): CampaignMap {
            val byId = chapters.associateBy { it.id }
            val parents = HashMap<String, MutableList<String>>()
            for (ch in chapters) for (s in successors(ch.id)) {
                if (s in byId) parents.getOrPut(s) { mutableListOf() }.add(ch.id)
            }
            // Unlocked = something leading here is done, or nothing leads here (a root — the first chapter,
            // and any chapter authored outside the flow).
            fun unlocked(id: String): Boolean {
                val ps = parents[id] ?: return true
                return ps.isEmpty() || ps.any(completed)
            }
            // Visible = unlocked, plus ONE layer of ghosts hanging off the unlocked frontier.
            val visible = LinkedHashSet<String>()
            for (ch in chapters) if (unlocked(ch.id)) visible.add(ch.id)
            for (id in visible.toList()) for (s in successors(id)) if (s in byId) visible.add(s)

            val order = chapters.filter { it.id in visible }.map { it.id }
            val depth = longestPathDepths(order, parents)
            val bands = bands(order, depth, parents, successors)
            val index = HashMap<String, Int>()
            val nodes = ArrayList<Node>(order.size)
            for (id in order.sortedBy { depth.getValue(it) }) {
                val open = unlocked(id)
                val (x, span) = bands.getValue(id)
                index[id] = nodes.size
                nodes.add(Node(
                    id = id,
                    chapter = if (open) byId[id] else null,
                    state = when {
                        !open -> State.Ghost
                        completed(id) -> State.Completed
                        else -> State.Available
                    },
                    depth = depth.getValue(id), x = x, span = span,
                ))
            }
            val edges = ArrayList<Edge>()
            for (id in order) {
                val from = index.getValue(id)
                for (s in successors(id)) index[s]?.let { edges.add(Edge(from, it)) }
            }
            return CampaignMap(nodes, edges)
        }

        /**
         * Horizontal bands, top down: a root owns the whole width (roots share it evenly), and each node
         * splits its own band evenly among the children it is the *first* parent of — so every node is placed
         * once, under the route that reaches it soonest, and no two bands overlap.
         *
         * Returns centre-and-width fractions per id. A node nothing places (unreachable from a root — only
         * possible if the authored graph has a cycle) falls back to the full width, so it is drawn somewhere
         * rather than not at all.
         */
        private fun bands(
            order: List<String>,
            depth: Map<String, Int>,
            parents: Map<String, List<String>>,
            successors: (String) -> List<String>,
        ): Map<String, Pair<Float, Float>> {
            val out = HashMap<String, Pair<Float, Float>>()
            val roots = order.filter { parents[it].orEmpty().none { p -> p in depth } }
            for ((i, id) in roots.withIndex()) out[id] = (i + 0.5f) / roots.size to 1f / roots.size
            // Depth order guarantees a node's own band is settled before it places its children.
            for (id in order.sortedBy { depth.getValue(it) }) {
                val (cx, span) = out[id] ?: continue
                val kids = successors(id).filter { it in depth && it !in out }
                if (kids.isEmpty()) continue
                val child = span / kids.size
                for ((i, k) in kids.withIndex()) out[k] = cx - span / 2f + child * (i + 0.5f) to child
            }
            for (id in order) out.getOrPut(id) { 0.5f to 1f }
            return out
        }

        /** The authored graph rule, mirrored from `CampaignContent.predecessorsOf`: a chapter's successors
         *  are the ones it names in [Chapter.branchesTo], or the next chapter in the list when it names none.
         *  An EMPTY `branchesTo` really is "leads nowhere". */
        private fun defaultSuccessors(chapters: List<Chapter>, id: String): List<String> {
            val i = chapters.indexOfFirst { it.id == id }
            if (i < 0) return emptyList()
            return chapters[i].branchesTo ?: listOfNotNull(chapters.getOrNull(i + 1)?.id)
        }

        /**
         * Longest path from a root, over the visible subgraph only. Relaxed repeatedly rather than
         * topologically sorted: the pass is over a handful of chapters, and a cycle (which an authored
         * `branchesTo` could express by accident) settles at the iteration cap instead of hanging.
         */
        private fun longestPathDepths(order: List<String>, parents: Map<String, List<String>>): Map<String, Int> {
            val depth = order.associateWith { 0 }.toMutableMap()
            repeat(order.size) {
                var changed = false
                for (id in order) {
                    val ps = parents[id].orEmpty().filter { it in depth }
                    val d = (ps.maxOfOrNull { depth.getValue(it) + 1 }) ?: 0
                    if (d > depth.getValue(id)) { depth[id] = d; changed = true }
                }
                if (!changed) return depth
            }
            return depth
        }
    }
}
