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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ⛔ **A span may not offer an appetite that can only be reached back through its own mouth.**
 *
 * Appetite travels backwards across a span: the near end is told what lies beyond, which is how a
 * corridor leading to a bridge learns there is any point feeding it. On a **loop** that sentence
 * turns on itself. Everything downstream of the mouth is also, eventually, downstream of the far
 * end — by going all the way round and back through the mouth — so the span ends up advertising the
 * mouth's own appetite to the mouth, as though crossing were a way of getting there. It is the exact
 * opposite: crossing is what takes the packet *off* the tile it needed to stay on.
 *
 * ⚠️ **And the door believes it**, which is where the material actually goes. `sinkAdmits` asks
 * `permits(hopTo(tile), cargo)` — the road past the span, which is the right question — so a wrong
 * answer here is a span that swallows a packet one tile from where it was going. Every lap. For
 * ever.
 *
 * Stu's `cycle` save, 2026-09-11, diagnosed by him: a tonne of iron bound for the silo at (9,15),
 * whose input is at (10,15) on a spur hanging off (10,13). (10,13) is also the mouth of
 * `Bridge@(9,13)`, which sets down at (8,13) — and from (8,13) the route back to (10,15) runs three
 * quarters of the way round the vessel and in through (10,13) again. So the span read the silo as
 * something it could deliver to, took the iron, and the iron went round for ever.
 *
 * ⚠️ **Not a rule about edges.** The same reflection happens at an ordinary fork and costs nothing:
 * a packet there has a *choice*, and `FlowCursors` gives the spur its turn, so the loop is latency.
 * A span is asked first and pre-empts; there is no turn to wait for. That asymmetry is why this is
 * stated of a hop and of nothing else.
 *
 * ## The shape
 *
 * ```
 *   A0 -> A1 -> A2 ⇢ (span 1)          A2 is the mouth: the spur hangs off it
 *   ↑            ↓
 *   |           P1 -> P2               <- wants IRON, and is reachable from B0
 *   |                                     only by coming back through A2
 *  (span 2) ⇠ B3 <- B2 <- B1 <- B0     <- B2 wants HYDROGEN, which does not
 * ```
 */
class FlowSpanReflectionTest {

    private val grid = Grid(9, 7)

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
    private val p1 = grid.tile(3, 2)
    private val p2 = grid.tile(3, 3)

    private val b0 = grid.tile(1, 5)
    private val b1 = grid.tile(2, 5)
    private val b2 = grid.tile(3, 5)
    private val b3 = grid.tile(4, 5)

    private val wanted = 500_000_000_000L

    /** The consumer on the spur, behind the mouth. */
    private val onTheSpur = Acceptance.filtered(SpeciesFilter(Species.Iron, pure = true), wanted)

    /** The consumer genuinely past the span, which nothing about this may disturb. */
    private val pastTheSpan = Acceptance.filtered(SpeciesFilter(Species.Hydrogen, pure = true), wanted)

    private fun iron(mass: Long) = Mixture.of(Species.Iron to mass, energy = 0)
    private fun hydrogen(mass: Long) = Mixture.of(Species.Hydrogen to mass, energy = 0)

    private fun flow(): FlowGraph {
        val n = Net(grid)
            .join(a0, Direction.Right)
            .join(a1, Direction.Right)
            .join(a2, Direction.Down)
            .join(p1, Direction.Down)
            .join(b0, Direction.Right)
            .join(b1, Direction.Right)
            .join(b2, Direction.Right)
        return FlowGraph.build(
            n.tiles,
            sources = setOf(a0, b0),
            sinks = setOf(a2, b2, b3, p2),
            linked = n::linked,
            grid = grid,
            hops = mapOf(a2 to b0, b3 to a0),
        )
    }

    /** [endless] makes the spur's consumer an ordinary machine door — the `unlimited` path. */
    private fun whitelist(flow: FlowGraph, endless: Boolean = false): Whitelist = Whitelist.of(
        flow,
        grid.size,
        acceptanceAt = { tile ->
            when (tile) {
                p2 -> if (endless) null else listOf(onTheSpur)
                b2 -> listOf(pastTheSpan)
                else -> null
            }
        },
        loadOn = { _, _ -> 0L },
    )

    /**
     * The premise: one ring joined by the two spans, with the spur hanging off the first span's
     * mouth. Asserted rather than assumed — if the orientation rules ever point the spur the other
     * way the tests below would pass for the wrong reason.
     */
    @Test
    fun `the mouth feeds a spur and also carries the ring`() {
        val f = flow()
        assertEquals(listOf(a1), f.successorTiles(a0), "A0 does not run along the ring")
        assertEquals(listOf(p1), f.successorTiles(a2), "the spur does not hang off the mouth")
        assertEquals(b0, f.hopTo(a2), "the mouth does not span to B0")
        assertEquals(listOf(p2), f.successorTiles(p1), "the spur does not reach its consumer")
        assertEquals(a0, f.hopTo(b3), "the ring does not close")
    }

    /**
     * ⛔ **The case itself.** `P2` wants iron and is reachable from `B0` — but only by coming back
     * through `A2`, the very mouth that would be doing the delivering. So the span must not offer
     * it, and the door that reads this must refuse the iron.
     */
    @Test
    fun `an appetite reached only back through the mouth does not cross the span`() {
        val w = whitelist(flow())
        assertFalse(
            w.permitsPast(a2, iron(100_000_000_000L)),
            "the span offered an appetite that lies behind its own mouth",
        )
    }

    /**
     * ⚠️ **The other half, and the one that stops this being a rule against spans.** `B2` wants
     * hydrogen and is reached from `B0` without going near `A2`, so the span speaks for it exactly
     * as before.
     */
    @Test
    fun `an appetite genuinely past the span still crosses it`() {
        val w = whitelist(flow())
        assertTrue(
            w.permitsPast(a2, hydrogen(100_000_000_000L)),
            "a span stopped speaking for a consumer that is really past it",
        )
    }

    /**
     * And the spur is untouched at the mouth itself: `A2` reaches `P2` down its own edge, which is
     * the road the iron is supposed to take.
     */
    @Test
    fun `the mouth still sees its own spur`() {
        val w = whitelist(flow())
        assertTrue(w.permits(a2, iron(100_000_000_000L), rationed = false), "the mouth lost its own spur")
    }

    /**
     * ⛔ **The `unlimited` path, which carries no provenance at all.** "Something reachable from
     * here takes anything for ever" is one bit per tile with nothing on it to say *which* something
     * — so a machine door on the spur would set it at the mouth, and it would travel round the ring
     * and back across the span exactly as a stated appetite did. Read the bare flag across a hop and
     * this whole rule is a fast path away from being undone.
     */
    @Test
    fun `an endless door on the spur does not cross the span either`() {
        val w = whitelist(flow(), endless = true)
        assertTrue(w.permitsAnything(a2), "the mouth cannot see the machine on its own spur")
        assertFalse(
            w.permitsPast(a2, iron(100_000_000_000L)),
            "the endless door behind the mouth let iron across the span",
        )
    }
}
