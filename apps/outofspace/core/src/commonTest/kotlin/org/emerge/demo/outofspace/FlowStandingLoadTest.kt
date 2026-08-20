package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Appetites
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two consumers on a stub of track no producer reaches, and the lump standing between them.
 *
 * ⛔ **On producer-less track, what is standing on it is where material enters.** Every rule
 * [FlowGraph] has about which way an edge should point is stated in terms of a producer — leading is
 * conferred by one, and a revocation must be justified by one. With none in the component all of it
 * falls silent, every revocation is permitted, and the last consumer traversed simply takes the
 * line. That is deliberate and it is the right answer for bare track. It is the wrong answer the
 * moment there is matter already standing on it, because then there *is* something to be justified
 * by, and the consumer that can use it may be the one that loses.
 *
 * From Stu's save: an extractor site 99% built and short of titanium, 100kg of titanium standing
 * three tiles away, and a run of iron rail ghosts drawn beside it. The rails' traversal ran second,
 * took the corridor back one edge at a time, and left the extractor's only feed edge pointing away
 * from it. The titanium could reach nothing but ghosts that refuse it — a rail is built from iron —
 * and stood still for ever.
 */
class FlowStandingLoadTest {

    private val grid = Grid(6, 6)

    /**
     * ```
     * E--A--B--C
     *       |
     *       G
     * ```
     * `E` is the extractor site, `G` the ghost rail, `C` is where the lump stands. No producer
     * anywhere. `E` sorts before `G`, so `E` traverses first and claims the corridor `C→B→A→E`;
     * `G` asks second.
     */
    private class Net(val grid: Grid) {
        val tiles = mutableSetOf<TileIndex>()
        private val links = mutableSetOf<Pair<TileIndex, Direction>>()

        fun join(a: TileIndex, dir: Direction): Net = apply {
            val b = grid.neighbour(a, dir)
            require(b != TileIndex.NONE)
            tiles.add(a); tiles.add(b); links.add(a to dir); links.add(b to dir.opposite)
        }

        fun linked(tile: TileIndex, dir: Direction): Boolean = (tile to dir) in links
    }

    private val e = grid.tile(1, 2)
    private val a = grid.tile(2, 2)
    private val b = grid.tile(3, 2)
    private val c = grid.tile(4, 2)
    private val g = grid.tile(3, 3)

    private fun net(): Net = Net(grid)
        .join(e, Direction.Right)
        .join(a, Direction.Right)
        .join(b, Direction.Right)
        .join(b, Direction.Down)

    private fun flow(carrying: (TileIndex) -> Boolean): FlowGraph {
        val n = net()
        return FlowGraph.build(n.tiles, emptySet(), setOf(e, g), n::linked, grid, carrying)
    }

    /**
     * ⛔ **The case itself, restated for the distance rule.** `E` is four hops from the lump at `C`
     * and `G` is two, so `C` heads for `G` — and `G`, in this fixture, admits everything.
     *
     * ⚠️ **What this used to assert was a fork at `B`, both consumers reachable.** That was the
     * greedy fallback's answer, arrived at by refusing `G`'s walk the right to take `B`'s road to
     * `E`. There is no walk on producer-less track any more and nothing to refuse: the track points
     * at the nearer consumer and the further one is simply not where this material is going. Stu's
     * call, 2026-08-20 — a producer-less rail is a degenerate case and the intuitive answer is the
     * closest sink.
     *
     * What survives, and is the reason this fixture still earns its place, is that the lump is not
     * stranded: it has a road, it is a road to a consumer, and it is the same road every tick.
     */
    @Test
    fun `the lump heads for whichever consumer is closest`() {
        val f = flow { it == c }

        assertEquals(listOf(b), f.successorTiles(c), "the lump moves off toward the junction")
        assertEquals(listOf(g), f.successorTiles(b), "and B sends it to the nearer of the two")
        assertEquals(emptyList(), f.successorTiles(g), "which is where it stops")
        // A is next door to the site, so it is one hop from `E` and two from `G`. The arm points at
        // its own consumer rather than draining to the junction — every tile picks for itself.
        assertEquals(listOf(e), f.successorTiles(a), "and the far arm feeds the site beside it")
        assertEquals(emptyList(), f.successorTiles(e))
    }

    /**
     * ⚠️ **The orientation does not switch on matter being there**, which is here so that nobody
     * reads the case above as something the lump causes. What is standing decides only which
     * consumers are *candidates* — a sink that could never accept it is not a destination — and in
     * this fixture both of them take anything. So the empty corridor points exactly where the loaded
     * one does, edge for edge.
     *
     * ⛔ **That is the whole property the change exists for**, stated on this fixture as well as on
     * [FlowNoSourceTest]'s corridor: the greedy fallback this replaced read `carrying` directly, so
     * a lump moving one tile reversed the edge behind it and walked back down.
     */
    @Test
    fun `bare track points exactly where loaded track does`() {
        fun edges(carrying: (TileIndex) -> Boolean): List<Pair<TileIndex, List<TileIndex>>> {
            val f = flow(carrying)
            return listOf(e, a, b, c, g).map { it to f.successorTiles(it) }
        }

        assertEquals(edges { false }, edges { it == c }, "the lump moved no edge at all")
        assertEquals(
            listOf(e to emptyList(), a to listOf(e), b to listOf(g), c to listOf(b), g to emptyList()),
            edges { false },
            "and each tile heads for whichever consumer is nearer it",
        )
    }

    /**
     * ⛔ **A direction of travel is defined by the source, not by the nearest sink.** `1..8` with a
     * producer at `1`, a machine tapping the line at `4` and a tank at `8` — [VesselSimTest]'s
     * tapped belt, at the graph level.
     *
     * `5` is one hop from the tap and three from the tank, so distance alone would turn it round and
     * split the line at its midpoint. It must not: the producer at `1` grounds the whole run, every
     * tile of it is [FlowGraph] *leading*, and a leading edge is never re-litigated by a walk that
     * arrives later. So the line commits end to end and the machine is offered material in passing —
     * a tapped line is a through-route, and a machine that says no does not thereby become a wall.
     *
     * ⚠️ **This test used to assert the same shape with no producer at all**, as the guarantee that
     * retired the nearest-consumer tie-break. That is no longer the rule: on track nothing grounds,
     * a packet goes to the closest sink that can be reached from it, and the midpoint split is the
     * intended answer rather than the failure — see [FlowNoSourceTest]. Stu's call, 2026-08-20. What
     * survives, and is what this now pins, is that a source *overrides* distance wherever there is
     * one.
     */
    @Test
    fun `a source defines the direction of travel, not the nearest sink`() {
        val wide = Grid(12, 6)
        val row = (1..8).map { wide.tile(it, 2) }
        val n = Net(wide)
        for (x in 1 until 8) n.join(wide.tile(x, 2), Direction.Right)
        val head = row[0]
        val tap = row[3]
        val tank = row[7]
        val lump = row[4]

        val f = FlowGraph.build(n.tiles, setOf(head), setOf(tap, tank), n::linked, wide, carrying = { it == lump })

        assertEquals(listOf(row[5]), f.successorTiles(lump), "the lump has one road, and it is onward")
        for (i in 0..6) {
            assertEquals(listOf(row[i + 1]), f.successorTiles(row[i]), "tile ${i + 1} carries on to the tank")
        }
    }

    /**
     * ⛔ **A lump on track a producer already reaches changes nothing at all**, and that is the
     * whole of why load is fed to the justification question and not to [FlowGraph]'s notion of a
     * source. `Z-S-A-B-C-D-E` with a producer at `S` and consumers at `B` and `D` is
     * [FlowLeadingTest]'s canonical run; loading every tile of it must not move a single edge, or
     * the shape of a run becomes a function of where its packets happen to be standing this tick.
     */
    @Test
    fun `load on fed track moves nothing`() {
        val wide = Grid(12, 6)
        val row = (1..7).map { wide.tile(it, 3) }
        val n = Net(wide)
        for (x in 1 until 7) n.join(wide.tile(x, 3), Direction.Right)
        val s = row[1]
        val sinks = setOf(row[3], row[5])

        fun edges(carrying: (TileIndex) -> Boolean): List<Pair<TileIndex, List<TileIndex>>> {
            val f = FlowGraph.build(n.tiles, setOf(s), sinks, n::linked, wide, carrying)
            return row.map { it to f.successorTiles(it) }
        }

        assertEquals(edges { false }, edges { true }, "a loaded run points the same way as an empty one")
    }

    /**
     * ⛔ **A lump justifies nobody who cannot eat it.** Stu's corridor, traced out of his save:
     *
     * ```
     * K--A--B--C          K is the tank, and takes anything
     *          |
     *          D          a lump of titanium stands here
     *          |
     *    G--F--E          G is an iron rail ghost, and refuses titanium
     * ```
     *
     * `K` sorts first and claims the whole corridor, `D → C → B → A → K`, which is the only road
     * the titanium has. `G` walks second, and at every edge it asks the same question: *is there a
     * producer beyond this, so that taking it gains me something?* Blind, the answer is yes — the
     * lump at `A` is up there — so `G` reverses the corridor one edge at a time, and the titanium
     * points at a ghost that will never accept a gram of it.
     *
     * ⚠️ **What made this so hard to see in play is that it clears itself.** Once the lump at `A`
     * is absorbed there is nothing beyond, `G` can justify nothing, the corridor points at the tank
     * again and the next lump goes. So a queue delivers **one lump at a time**, each waiting for the
     * one ahead to be eaten — the behaviour Stu reported as "pauses and waits until the line is
     * clear". Traced on the save at tiles 915/953/989, 2026-08-20: the single value that changed
     * between stuck and moving was `beyond(989, Up)`.
     */
    @Test
    fun `a consumer cannot be justified by material it will not accept`() {
        val wide = Grid(10, 8)
        val k = wide.tile(1, 2); val a = wide.tile(2, 2); val b = wide.tile(3, 2); val c = wide.tile(4, 2)
        val d = wide.tile(4, 3); val e = wide.tile(4, 4); val f = wide.tile(3, 4); val g = wide.tile(2, 4)
        val n = Net(wide)
            .join(k, Direction.Right).join(a, Direction.Right).join(b, Direction.Right)
            .join(c, Direction.Down).join(d, Direction.Down)
            .join(e, Direction.Left).join(f, Direction.Left)

        // Two lumps of the same stuff: one up the corridor, one on the branch tile. Only the tank
        // will take it; the ghost is class 1 and admits nothing standing anywhere.
        val carrying = { t: TileIndex -> t == a || t == d }
        val appetites = object : Appetites {
            override val classes: Int get() = 2
            override fun classOf(sink: TileIndex): Int = if (sink == g) 1 else 0
            override fun admits(cls: Int, lump: TileIndex): Boolean = cls == 0
        }

        val f2 = FlowGraph.build(n.tiles, emptySet(), setOf(k, g), n::linked, wide, carrying, appetites)

        assertEquals(listOf(c), f2.successorTiles(d), "the lump heads up the corridor, not at the ghost")
        assertEquals(listOf(b), f2.successorTiles(c), "and the corridor is not reversed behind it")
        assertEquals(listOf(a), f2.successorTiles(b))
        assertEquals(listOf(k), f2.successorTiles(a), "right through to the tank")
    }

    /**
     * ⚠️ **The same corridor, blind, is the bug** — kept so that the case above is measuring the
     * appetite and not the shape. [Appetites.BLIND] is what every caller that does not care gets,
     * and it must still reproduce exactly what the graph did before appetites existed.
     */
    @Test
    fun `blind, the ghost takes the corridor it cannot use`() {
        val wide = Grid(10, 8)
        val k = wide.tile(1, 2); val a = wide.tile(2, 2); val b = wide.tile(3, 2); val c = wide.tile(4, 2)
        val d = wide.tile(4, 3); val e = wide.tile(4, 4); val f = wide.tile(3, 4); val g = wide.tile(2, 4)
        val n = Net(wide)
            .join(k, Direction.Right).join(a, Direction.Right).join(b, Direction.Right)
            .join(c, Direction.Down).join(d, Direction.Down)
            .join(e, Direction.Left).join(f, Direction.Left)

        val blind = FlowGraph.build(
            n.tiles, emptySet(), setOf(k, g), n::linked, wide, carrying = { it == a || it == d },
        )

        assertEquals(listOf(e), blind.successorTiles(d), "blind, the lump is pointed at the ghost")
    }
}
