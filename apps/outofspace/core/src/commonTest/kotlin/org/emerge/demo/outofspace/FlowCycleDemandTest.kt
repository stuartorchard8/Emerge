package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Acceptance
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.SpeciesFilter
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.Whitelist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Demand carried all the way round a **loop**.
 *
 * ⛔ **A cycle has no topological order, and [Whitelist.of] walks one.** Appetite is carried
 * upstream in a single pass over [FlowGraph.order], which works because a tile appears after every
 * tile it can send to — and on a cycle that promise cannot be kept. `walkOrder` said as much and
 * appended the leftovers in **tile-index order**, an order with no relation to the flow, so demand
 * propagated only where the two happened to agree.
 *
 * ⚠️ **The old note called this "a source that waits a tick".** It is not: the fallback order is
 * derived from tile indices, so it is the same every tick, and a source on the wrong side of the
 * seam waits for ever.
 *
 * From Stu's `cycle` save (2026-09-11): a silo at (12,15) with a tonne of iron and a silo at (9,15)
 * with room for it, joined by a route that runs three-quarters of the way round the vessel and back
 * through two bridges. Nothing moved for 3000 ticks. Cutting *any* rail on column 8 — a stretch not
 * on the delivery route at all — broke the cycle open and the whole tonne went.
 *
 * ## The shape
 *
 * Two straight corridors, joined end to end by two spans into one ring. That is the save's shape
 * reduced to what it takes to close a directed loop, and **a span is what it takes**: a corridor
 * orients itself toward its own consumer, so a ring of plain track splits into two arcs that both
 * feed the sink and no cycle forms. A bridge sets material down at a tile the walk never reached
 * from, which is how the two arcs get joined nose to tail.
 *
 * ```
 *   A0 -> A1 -> A2 ⇢ (span)
 *   ↑                    ↓
 *  (span) ⇠ B3 <- B2 <- B1 <- B0
 * ```
 *
 * `A0` and `B0` are the spans' output ports, so they are producers; `A2` and `B3` are their input
 * ports, so they are sinks that state no appetite of their own and speak for whatever lies across
 * the span. The only real consumer is at `B2`, and the question is whether a source standing at the
 * far end of corridor A can see it.
 */
class FlowCycleDemandTest {

    private val grid = Grid(8, 6)

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

    private val a0 = grid.tile(1, 1)
    private val a1 = grid.tile(2, 1)
    private val a2 = grid.tile(3, 1)

    private val b0 = grid.tile(1, 3)
    private val b1 = grid.tile(2, 3)
    private val b2 = grid.tile(3, 3)
    private val b3 = grid.tile(4, 3)

    private val wanted = 500_000_000_000L

    /** The consumer at `B2`, and the only appetite anywhere on the ring. */
    private val appetite = Acceptance.filtered(SpeciesFilter(Species.Iron, pure = true), wanted)

    private fun iron(mass: Long) = Mixture.of(Species.Iron to mass, energy = 0)

    private fun flow(): FlowGraph {
        val n = Net(grid)
            .join(a0, Direction.Right)
            .join(a1, Direction.Right)
            .join(b0, Direction.Right)
            .join(b1, Direction.Right)
            .join(b2, Direction.Right)
        return FlowGraph.build(
            n.tiles,
            sources = setOf(a0, b0),
            sinks = setOf(a2, b2, b3),
            linked = n::linked,
            grid = grid,
            hops = mapOf(a2 to b0, b3 to a0),
        )
    }

    private fun whitelist(flow: FlowGraph): Whitelist = Whitelist.of(
        flow,
        grid.size,
        acceptanceAt = { tile -> if (tile == b2) listOf(appetite) else null },
        loadOn = { _, _ -> 0L },
    )

    /**
     * The ring really is one directed cycle — the premise everything below rests on. If a future
     * change to the orientation rules splits it into two arcs the tests that follow would pass for
     * the wrong reason, so it is asserted rather than assumed.
     */
    @Test
    fun `the two corridors and their spans make one directed loop`() {
        val f = flow()
        val walk = mutableListOf(a0)
        repeat(7) {
            val at = walk.last()
            walk.add(f.successorTiles(at).singleOrNull() ?: f.hopTo(at) ?: at)
        }
        assertEquals(listOf(a0, a1, a2, b0, b1, b2, b3, a0), walk, "the ring is not a closed loop")
    }

    /**
     * ⛔ **The case itself.** `B2` wants iron; a source at the far end of corridor A can reach it,
     * three hops and a span away, and has to be told so.
     */
    @Test
    fun `an appetite on a loop is carried the whole way round it`() {
        val w = whitelist(flow())
        val lump = iron(100_000_000_000L)
        for (tile in listOf(a0, a1, a2, b0, b1)) {
            assertTrue(w.permits(tile, lump), "$tile cannot reach the consumer at B2")
            assertTrue(w.room(tile, lump) > 0L, "$tile was offered no room for a consumer it feeds")
        }
    }

    /**
     * ⚠️ **And not more than once.** Every tile on the ring reaches the same single consumer, so
     * each of them is owed the same shortfall — the lap must not be allowed to add the sink's
     * appetite to itself on every trip round.
     */
    @Test
    fun `going round the loop does not multiply the appetite`() {
        val w = whitelist(flow())
        val lump = iron(100_000_000_000L)
        for (tile in listOf(a0, a1, a2, b0, b1)) {
            assertEquals(wanted, w.room(tile, lump), "$tile reads a different shortfall from its neighbours")
        }
    }

    /**
     * The members of a cycle are grouped, and **contiguously** — [Whitelist.of] solves one group at
     * a time and walks [FlowGraph.order] straight through, so a group split in two would be solved
     * in halves.
     */
    @Test
    fun `the loop is reported as one group, contiguous in the order`() {
        val f = flow()
        val group = f.loops.values.singleOrNull()
        assertEquals(setOf(a0, a1, a2, b0, b1, b2, b3), group?.toSet(), "the ring is not one group")
        val at = f.order.indexOf(group!!.first())
        assertEquals(group, f.order.subList(at, at + group.size), "the group is not contiguous in the order")
    }

    /**
     * ⛔ **A network with no cycle on it pays nothing and is ordered exactly as before.** The
     * grouping is reached only by the branch `walkOrder` already took when it could not place every
     * tile, so an ordinary vessel cannot tell any of this is here.
     */
    @Test
    fun `a straight run has no groups and keeps its order`() {
        val n = Net(grid)
            .join(a0, Direction.Right)
            .join(a1, Direction.Right)
        val f = FlowGraph.build(n.tiles, sources = setOf(a0), sinks = setOf(a2), linked = n::linked, grid = grid)
        assertTrue(f.loops.isEmpty(), "a straight run reported a cycle")
        assertEquals(listOf(a2, a1, a0), f.order, "the order of a straight run moved")
    }
}
