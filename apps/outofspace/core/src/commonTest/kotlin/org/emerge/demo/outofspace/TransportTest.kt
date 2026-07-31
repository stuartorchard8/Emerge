package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Diverters
import org.emerge.demo.outofspace.world.DiverterWork
import org.emerge.demo.outofspace.world.FlowField
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.advanceSegments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transport layer, on its own: which way material moves along a run, and who gets it first.
 *
 * These tests drive the flow model directly rather than through the world, because the property that
 * matters most is one the world cannot easily show — that the answer to *"which of two buildings on
 * this run gets fed"* is a fact about the pipe's shape and not about how the tiles happen to be
 * indexed. That is the ONI junction artifact, and the only way to be sure it is absent is to build
 * the same run twice with the array running the other way and check nothing changes.
 */
class TransportTest {

    private val grid = Grid(12, 6)

    /** A lump of ore — a powder, so lumps of it bunch up against a blockage. */
    private fun lump(grams: Long = 1_000L): Packet =
        SolidPacket(Resource(Form.Ore, Mixture.of(Species.Iron to grams)))

    /** A horizontal run of segments on row [y], from [fromX] to [toX] inclusive. */
    private fun run(fromX: Int, toX: Int, y: Int): Set<Int> =
        (minOf(fromX, toX)..maxOf(fromX, toX)).map { grid.index(it, y) }.toSet()

    private fun flow(segments: Set<Int>, vararg sources: Int): FlowField =
        FlowField.derive(grid, { it in segments }, sources.toList())

    private fun held(segments: Set<Int>, vararg placed: Pair<Int, Packet>): Array<Packet?> {
        val out = arrayOfNulls<Packet>(grid.size)
        for ((tile, packet) in placed) {
            require(tile in segments) { "packet placed off the run" }
            out[tile] = packet
        }
        return out
    }

    private fun step(
        flow: FlowField,
        held: Array<Packet?>,
        diverters: DiverterWork = DiverterWork(Diverters.EMPTY),
        absorb: (Int, Packet) -> Packet? = { _, p -> p },
    ): Int = advanceSegments(flow, held, diverters, absorb)

    // ── Which way is downstream ───────────────────────────────────────────────

    @Test
    fun `material flows away from the source, whichever end of the run it is on`() {
        val segments = run(2, 8, 3)

        val fromLeft = flow(segments, grid.index(2, 3))
        assertEquals(0, fromLeft.distanceAt(grid.index(2, 3)))
        assertEquals(6, fromLeft.distanceAt(grid.index(8, 3)))

        // The identical run, fed from the other end. Nothing about the tiles changed.
        val fromRight = flow(segments, grid.index(8, 3))
        assertEquals(6, fromRight.distanceAt(grid.index(2, 3)))
        assertEquals(0, fromRight.distanceAt(grid.index(8, 3)))
    }

    @Test
    fun `a segment no source can reach carries nothing`() {
        val connected = run(2, 5, 3)
        val orphan = run(8, 10, 3)      // same row, but with a gap at 6..7
        val f = flow(connected + orphan, grid.index(2, 3))

        assertTrue(f.isFed(grid.index(5, 3)))
        assertFalse(f.isFed(grid.index(8, 3)), "nothing feeds it, so it has no downstream")
        assertEquals(0, f.successorsOf(grid.index(8, 3)).size)
    }

    @Test
    fun `distance is to the nearest source, so two feeds meet in the middle`() {
        val segments = run(2, 10, 3)
        val f = flow(segments, grid.index(2, 3), grid.index(10, 3))
        assertEquals(4, f.distanceAt(grid.index(6, 3)), "the midpoint is four from either end")
        // And the tile in the middle has nowhere further to go: both neighbours are closer to a
        // source than it is.
        assertEquals(0, f.successorsOf(grid.index(6, 3)).size, "two flows meeting is a dead end")
    }

    // ── Order of absorption ───────────────────────────────────────────────────

    @Test
    fun `the first input along the run takes the packet`() {
        val segments = run(2, 8, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(segments, grid.index(2, 3) to lump())

        // Two buildings tapping the same run, at 5 and 7. The nearer one should win.
        val taken = mutableListOf<Int>()
        repeat(10) {
            step(f, h) { tile, packet ->
                if (tile == grid.index(5, 3) || tile == grid.index(7, 3)) {
                    taken.add(grid.xOf(tile)); null
                } else packet
            }
        }
        assertEquals(listOf(5), taken, "upstream starves downstream, and that is the mechanic")
    }

    @Test
    fun `a full building lets the packet carry on to the next one`() {
        val segments = run(2, 8, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(segments, grid.index(2, 3) to lump())

        val taken = mutableListOf<Int>()
        repeat(10) {
            step(f, h) { tile, packet ->
                // The near building is full and refuses; the far one takes it.
                if (tile == grid.index(7, 3)) { taken.add(grid.xOf(tile)); null } else packet
            }
        }
        assertEquals(listOf(7), taken)
    }

    /**
     * The one that would catch the artifact. Same run, same buildings, same everything — but the
     * source is at the high-index end, so grid order and flow order disagree. If absorption were
     * resolved by walking tiles in index order, the *downstream* building would win here.
     */
    @Test
    fun `priority follows the flow, not the array`() {
        val segments = run(2, 8, 3)
        val f = flow(segments, grid.index(8, 3))          // fed from the right
        val h = held(segments, grid.index(8, 3) to lump())

        val taken = mutableListOf<Int>()
        repeat(10) {
            step(f, h) { tile, packet ->
                if (tile == grid.index(5, 3) || tile == grid.index(7, 3)) {
                    taken.add(grid.xOf(tile)); null
                } else packet
            }
        }
        assertEquals(listOf(7), taken, "flowing right-to-left, 7 is the upstream one")
    }

    // ── Movement ──────────────────────────────────────────────────────────────

    @Test
    fun `a packed run advances along its whole length in one pass`() {
        // Furthest-from-source first: each tile is emptied before the one behind moves into it.
        val segments = run(2, 6, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(
            segments,
            grid.index(2, 3) to lump(),
            grid.index(3, 3) to lump(),
            grid.index(4, 3) to lump(),
        )
        val moved = step(f, h)
        assertEquals(3, moved, "all three moved, not just the leading one")
        assertNull(h[grid.index(2, 3)])
        assertEquals(3, (3..5).count { h[grid.index(it, 3)] != null })
    }

    @Test
    fun `a packet at the end of a run stays put rather than falling off it`() {
        val segments = run(2, 4, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(segments, grid.index(4, 3) to lump())
        step(f, h)
        assertEquals(1_000L, h[grid.index(4, 3)]?.mass, "still there")
    }

    // ── Bunching up against a blockage ────────────────────────────────────────

    @Test
    fun `identical lumps squash together against a blockage`() {
        val segments = run(2, 5, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(
            segments,
            grid.index(4, 3) to lump(400L),
            grid.index(5, 3) to lump(400L),   // at the end of the run, nowhere to go
        )
        step(f, h)
        assertEquals(800L, h[grid.index(5, 3)]?.mass, "the one behind squashed into the one ahead")
        assertNull(h[grid.index(4, 3)], "leaving its tile free")
    }

    @Test
    fun `squashing stops at a full packet and the rest queues behind it`() {
        val segments = run(2, 5, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(
            segments,
            grid.index(4, 3) to lump(600L),
            grid.index(5, 3) to lump(700L),
        )
        step(f, h)
        assertEquals(1_000L, h[grid.index(5, 3)]?.mass, "filled to capacity")
        assertEquals(300L, h[grid.index(4, 3)]?.mass, "and the overflow stayed put")
    }

    /**
     * The consequence of powder being powder, and the reason routing matters.
     *
     * Tip 41% ore into a line carrying 75% concentrate and you get one pile at a purity in between,
     * with no way back. That is not a limitation to design around — it is the cost of merging two
     * streams that should have been kept apart, and it is what makes the separation a processor
     * performs worth protecting.
     */
    @Test
    fun `ore of different purities blends, because that is what powder does`() {
        val dirty = SolidPacket(Resource(Form.Ore, Mixture.of(Species.Iron to 200L, Species.Silica to 300L)))
        val clean = SolidPacket(Resource(Form.Ore, Mixture.of(Species.Iron to 375L, Species.Silica to 125L)))
        val segments = run(2, 5, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(segments, grid.index(4, 3) to dirty, grid.index(5, 3) to clean)

        step(f, h)
        val merged = h[grid.index(5, 3)]!!
        assertNull(h[grid.index(4, 3)], "the two piles became one")
        assertEquals(1_000L, merged.mass, "and nothing was lost doing it")
        // 375g + 200g of iron in a kilogram: the concentrate has been spoiled, and deservedly.
        assertEquals(575L, merged.contents[Species.Iron], "purity is now somewhere in between")
    }

    @Test
    fun `ingots stay separate lumps however hard they are pressed together`() {
        // A made thing is a made thing. Two bars on a jammed belt are still two bars, so the run
        // queues rather than bunching, and they can be told apart at the far end.
        val bar = SolidPacket(Resource(Form.IronIngot, Mixture.of(Species.Iron to 400L)))
        val segments = run(2, 5, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(segments, grid.index(4, 3) to bar, grid.index(5, 3) to bar)

        step(f, h)
        assertEquals(400L, h[grid.index(5, 3)]?.mass, "still one bar")
        assertEquals(400L, h[grid.index(4, 3)]?.mass, "and the other queued behind it")
    }

    @Test
    fun `different forms never bunch, however alike their contents`() {
        val pure = Mixture.of(Species.Iron to 500L)
        val ingot = SolidPacket(Resource(Form.IronIngot, pure))
        val ore = SolidPacket(Resource(Form.Ore, pure))
        val segments = run(2, 5, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(segments, grid.index(4, 3) to ore, grid.index(5, 3) to ingot)

        step(f, h)
        assertEquals(500L, h[grid.index(5, 3)]?.mass, "an ingot is not a lump of ore")
        assertEquals(500L, h[grid.index(4, 3)]?.mass)
    }

    @Test
    fun `a jammed run bunches toward its destination over several ticks`() {
        val segments = run(2, 8, 3)
        val f = flow(segments, grid.index(2, 3))
        val h = held(
            segments,
            grid.index(5, 3) to lump(250L),
            grid.index(6, 3) to lump(250L),
            grid.index(7, 3) to lump(250L),
            grid.index(8, 3) to lump(250L),
        )
        repeat(8) { step(f, h) }
        assertEquals(1_000L, h[grid.index(8, 3)]?.mass, "all of it ended up in one lump at the end")
        assertEquals(1, (2..8).count { h[grid.index(it, 3)] != null }, "and the rest of the run is clear")
    }

    // ── Forks ─────────────────────────────────────────────────────────────────

    /** A T: a run along row 3 that splits at x=5 into row 2 and row 4. */
    private fun tee(): Pair<Set<Int>, FlowField> {
        val segments = run(2, 5, 3) + setOf(grid.index(5, 2), grid.index(5, 4))
        return segments to flow(segments, grid.index(2, 3))
    }

    @Test
    fun `a fork alternates instead of favouring a branch`() {
        val (segments, f) = tee()
        val diverters = DiverterWork(Diverters.EMPTY)
        val up = grid.index(5, 2)
        val down = grid.index(5, 4)

        val sent = mutableListOf<Int>()
        repeat(6) {
            val h = held(segments, grid.index(5, 3) to lump())
            step(f, h, diverters)
            if (h[up] != null) sent.add(2)
            if (h[down] != null) sent.add(4)
        }
        // Which branch goes first follows Direction's declaration order (Down before Up) — arbitrary,
        // but fixed, and the alternation after it is the part that matters.
        assertEquals(listOf(4, 2, 4, 2, 4, 2), sent, "even by construction, not by iteration luck")
    }

    @Test
    fun `a blocked branch does not consume its turn`() {
        // The point: a jam on one side must not quietly halve the other side's throughput. If the
        // cursor advanced past a branch it could not use, every other packet would be lost to it.
        val (segments, f) = tee()
        val diverters = DiverterWork(Diverters.EMPTY)
        val up = grid.index(5, 2)
        val down = grid.index(5, 4)

        var reachedDown = 0
        repeat(6) {
            // The upward branch is permanently occupied, so it can never accept.
            val h = held(segments, grid.index(5, 3) to lump(), up to lump())
            step(f, h, diverters)
            if (h[down] != null) reachedDown++
        }
        assertEquals(6, reachedDown, "every packet should have taken the branch that was open")
    }

    @Test
    fun `diverter state survives a round trip`() {
        val (segments, f) = tee()
        val first = DiverterWork(Diverters.EMPTY)
        step(f, held(segments, grid.index(5, 3) to lump()), first)
        val saved = first.snapshot()
        assertFalse(saved.isEmpty, "a fork that has sent something remembers which way")

        // Resuming from the snapshot continues the alternation rather than starting over.
        val resumed = DiverterWork(saved)
        val h = held(segments, grid.index(5, 3) to lump())
        step(f, h, resumed)
        assertEquals(1, listOf(grid.index(5, 2), grid.index(5, 4)).count { h[it] != null })
        assertEquals(saved, Diverters.of(mapOf(grid.index(5, 3) to 1)), "and it is the state it looks like")
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    fun `the same network resolves the same way twice`() {
        fun digest(): String {
            val (segments, f) = tee()
            val diverters = DiverterWork(Diverters.EMPTY)
            val h = held(segments, grid.index(2, 3) to lump())
            repeat(20) { step(f, h, diverters) }
            return (0 until grid.size).joinToString(",") { h[it]?.mass?.toString() ?: "-" } +
                "|" + diverters.snapshot()
        }
        assertEquals(digest(), digest())
    }

    @Test
    fun `the traversal order is total, so nothing depends on sort stability`() {
        val segments = run(2, 6, 3) + run(2, 6, 4)
        val f = flow(segments, grid.index(2, 3), grid.index(2, 4))
        val order = f.order.toList()
        assertEquals(order.size, order.toSet().size, "every fed tile appears exactly once")
        // Distances are non-increasing along the order: that is the property advancing relies on.
        val distances = order.map { f.distanceAt(it) }
        assertEquals(distances.sortedDescending(), distances)
    }
}
