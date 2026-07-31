package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Direction
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
 * Two properties matter more than the rest, and both are here because they were wrong once.
 *
 * **Material is pulled toward consumers, not pushed away from producers.** A run's direction comes
 * from where its *input* ports are. That is what stops material being shoved down a branch with
 * nothing on the end of it — a dead end has no distance to a sink, so nothing ever enters it, and
 * nobody has to write a rule saying so.
 *
 * **Track is connected by being drawn, not by touching.** Two runs side by side are two runs. This is
 * what lets a factory be dense, and it is what makes a bridge a real crossing rather than a building
 * that needs a tile of clearance either side.
 *
 * These drive the flow model directly rather than through the world, because the thing most worth
 * being sure of is one the world cannot easily show — that *"which of two buildings on this run gets
 * fed"* is a fact about the pipe's shape and not about how the tiles happen to be indexed. That is
 * the ONI junction artifact, and the only way to be sure it is absent is to build the same run twice
 * with the array running the other way and check nothing changes.
 */
class TransportTest {

    private val grid = Grid(12, 6)

    /** A lump of ore — a powder, so lumps of it bunch up against a blockage. */
    private fun lump(grams: Long = 1_000L): Packet =
        SolidPacket(Resource(Form.Ore, Mixture.of(Species.Iron to grams)))

    /**
     * A network under construction: tiles that carry track, and the joins actually drawn between
     * them. Laying and joining are separate here for the same reason they are separate in the game —
     * two tiles of track next to each other are not a run.
     */
    private inner class Net {
        val tiles = mutableSetOf<Int>()
        private val links = mutableSetOf<Pair<Int, Direction>>()

        fun linked(tile: Int, dir: Direction): Boolean = (tile to dir) in links

        /** Lays track without joining it to anything — a stub. */
        fun lay(tile: Int): Net = apply { tiles.add(tile) }

        fun join(a: Int, dir: Direction): Net = apply {
            val b = grid.neighbour(a, dir)
            require(b >= 0)
            tiles.add(a); tiles.add(b)
            links.add(a to dir)
            links.add(b to dir.opposite)
        }

        /** A horizontal run on row [y], laid and joined end to end. */
        fun row(fromX: Int, toX: Int, y: Int): Net = apply {
            for (x in minOf(fromX, toX)..maxOf(fromX, toX)) lay(grid.index(x, y))
            for (x in minOf(fromX, toX) until maxOf(fromX, toX)) join(grid.index(x, y), Direction.Right)
        }

        /** A vertical run on column [x]. */
        fun col(x: Int, fromY: Int, toY: Int): Net = apply {
            for (y in minOf(fromY, toY)..maxOf(fromY, toY)) lay(grid.index(x, y))
            for (y in minOf(fromY, toY) until maxOf(fromY, toY)) join(grid.index(x, y), Direction.Down)
        }

        /**
         * The flow field this network has, given where its consumers are — and, where it matters,
         * where material comes *in*.
         *
         * Leaving [from] empty is the "nobody is feeding this" case: material on the run drains
         * downhill to the nearest consumer. That is a real situation (a belt whose miner was just
         * torn out) and most of the tests below only care about the downstream half, so they say
         * nothing about sources. A test about a **fork** must name one, because which way is forward
         * at a junction is a fact about where material entered.
         */
        fun toward(vararg sinks: Int): FlowField =
            FlowField.derive(grid, { it in tiles }, ::linked, sinks.toList())

        fun toward(accepting: List<Int>, full: List<Int> = emptyList(), from: List<Int> = emptyList()): FlowField =
            FlowField.derive(grid, { it in tiles }, ::linked, accepting, full, from)
    }

    private fun net(): Net = Net()

    private fun held(net: Net, vararg placed: Pair<Int, Packet>): Array<Packet?> {
        val out = arrayOfNulls<Packet>(grid.size)
        for ((tile, packet) in placed) {
            require(tile in net.tiles) { "packet placed off the run" }
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
    fun `material flows toward the consumer, whichever end of the run it is on`() {
        val n = net().row(2, 8, 3)

        val toRight = n.toward(grid.index(8, 3))
        assertEquals(0, toRight.distanceAt(grid.index(8, 3)), "the sink is the origin")
        assertEquals(6, toRight.distanceAt(grid.index(2, 3)))

        // The identical run, consumed at the other end. Nothing about the tiles changed.
        val toLeft = n.toward(grid.index(2, 3))
        assertEquals(0, toLeft.distanceAt(grid.index(2, 3)))
        assertEquals(6, toLeft.distanceAt(grid.index(8, 3)))
    }

    @Test
    fun `a run with no consumer on it moves nothing at all`() {
        // The whole difference between pulling and pushing. Under the old push model this run had a
        // source, so material marched down it and piled up at the far end; a half-built line filled
        // with stock you then had to dig back out. With nothing to pull, nothing moves.
        val n = net().row(2, 8, 3)
        val f = n.toward()      // no sinks anywhere
        val h = held(n, grid.index(2, 3) to lump())

        repeat(10) { step(f, h) }
        assertEquals(1_000L, h[grid.index(2, 3)]?.mass, "it stayed exactly where it was put")
        assertFalse(f.isFed(grid.index(5, 3)))
    }

    @Test
    fun `a segment that cannot reach a consumer carries nothing`() {
        val n = net().row(2, 5, 3).row(8, 10, 3)     // same row, but with a gap at 6..7
        val f = n.toward(grid.index(2, 3))

        assertTrue(f.isFed(grid.index(5, 3)))
        assertFalse(f.isFed(grid.index(8, 3)), "no route to a consumer, so no upstream")
        assertEquals(0, f.successorsOf(grid.index(8, 3)).size)
    }

    /**
     * The rule the player asked for by name: **resources are never pushed onto dead ends.**
     *
     * A T where one arm ends at a building and the other ends at nothing. Under a push model the
     * diverter would alternate and half of everything would go and sit in the stub forever. Under a
     * pull model the stub is not part of the field at all, so the fork is not a fork.
     */
    @Test
    fun `a branch with nothing on the end of it never receives anything`() {
        val n = net().row(2, 5, 3).col(5, 3, 5).lay(grid.index(5, 2)).join(grid.index(5, 3), Direction.Up)
        val sink = grid.index(5, 5)
        val deadEnd = grid.index(5, 2)
        val f = n.toward(sink)

        // Note what is *not* claimed: the stub is perfectly reachable, at distance 3, because you can
        // walk from it back down to the consumer. Being on the field is not the same as being sent
        // anything. What keeps it empty is that successors only ever run downhill, so the junction
        // below it never offers it anything — and if a packet were somehow put there it would flow
        // *out*, which is also right.
        assertEquals(3, f.distanceAt(deadEnd), "reachable, and further from the consumer than the junction")
        assertEquals(
            listOf(grid.index(5, 4)),
            f.successorsOf(grid.index(5, 3)).toList(),
            "the junction is not a junction: only one way leads anywhere",
        )

        var delivered = 0L
        val h = held(n, grid.index(2, 3) to lump())
        repeat(10) { step(f, h) { tile, p -> if (tile == sink) { delivered += p.mass; null } else p } }
        assertEquals(1_000L, delivered, "all of it reached the building")
        assertNull(h[deadEnd], "and none of it went up the stub")
    }

    @Test
    fun `distance is to the nearest consumer, so material picks the closer one`() {
        val n = net().row(2, 10, 3)
        val f = n.toward(grid.index(2, 3), grid.index(10, 3))
        assertEquals(4, f.distanceAt(grid.index(6, 3)), "the midpoint is four from either")
        // The midpoint has two equally good routes, so the diverter decides — and it alternates,
        // which makes a line between two consumers split its throughput evenly.
        assertEquals(2, f.successorsOf(grid.index(6, 3)).size)
    }

    // ── Explicit connection ───────────────────────────────────────────────────

    @Test
    fun `two runs side by side do not merge, however close they are`() {
        // Touching is not joining. Without this, every parallel line needs a tile of clearance and a
        // factory sprawls for reasons the player cannot see.
        val n = net().row(2, 8, 3).row(2, 8, 4)
        val f = n.toward(grid.index(8, 3))

        assertEquals(6, f.distanceAt(grid.index(2, 3)), "its own row is fed")
        assertFalse(f.isFed(grid.index(2, 4)), "the row beneath is a different run")
        assertFalse(f.isFed(grid.index(8, 4)), "even the tile directly under the consumer")
    }

    @Test
    fun `a run crosses another without touching it`() {
        // The bridge case, in the flow model: a horizontal line and a vertical line sharing tile
        // (5,3) is impossible, so they pass on either side of it and neither knows about the other.
        val n = net().row(2, 8, 3).col(5, 0, 2).col(5, 4, 5)
        val f = n.toward(grid.index(8, 3))

        assertTrue(f.isFed(grid.index(4, 3)) && f.isFed(grid.index(6, 3)), "the horizontal run is whole")
        assertFalse(f.isFed(grid.index(5, 2)), "the vertical run above is not connected to it")
        assertFalse(f.isFed(grid.index(5, 4)), "nor the part below")
    }

    @Test
    fun `laid track that was never joined moves nothing`() {
        val n = net().row(2, 5, 3).lay(grid.index(6, 3))   // 6 is adjacent to 5, but unjoined
        val f = n.toward(grid.index(6, 3))

        assertTrue(f.isFed(grid.index(6, 3)), "the stub is the sink, so it is trivially at distance 0")
        assertFalse(f.isFed(grid.index(5, 3)), "but nothing reaches it")
        val h = held(n, grid.index(5, 3) to lump())
        step(f, h)
        assertEquals(1_000L, h[grid.index(5, 3)]?.mass, "the gap in the graph is a real gap")
    }

    // ── Order of absorption ───────────────────────────────────────────────────

    @Test
    fun `the first input along the run takes the packet`() {
        // Two buildings tapping the same run at 5 and 7, with the consumer end at 8. Material
        // travelling right meets 5 first, and 5 wins.
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.index(8, 3))
        val h = held(n, grid.index(2, 3) to lump())

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
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.index(8, 3))
        val h = held(n, grid.index(2, 3) to lump())

        val taken = mutableListOf<Int>()
        repeat(10) {
            step(f, h) { tile, packet ->
                // The near building is full and refuses; the far one takes it.
                if (tile == grid.index(7, 3)) { taken.add(grid.xOf(tile)); null } else packet
            }
        }
        assertEquals(listOf(7), taken)
    }

    // ── A fork is a fork even when one branch is shorter ─────────────────────

    /**
     * From a save Stu sent: a line splitting to a vent two tiles away and a tank three tiles away
     * put **everything** down the vent and nothing down the other branch.
     *
     * Nothing chose the vent. Material moved to whichever neighbour was closest to a consumer, and
     * one branch simply happened to be shorter — so the junction produced a single successor and
     * [Diverters], which exists to alternate at exactly this junction, never saw a choice at all.
     *
     * A shortest-path rule cannot tell a fork from a shortcut. Knowing where material came *in* can:
     * both branches lie one step further from the source, so both are legal moves.
     */
    @Test
    fun `a fork alternates even when one branch reaches its consumer sooner`() {
        // In from the left; up is two tiles to a consumer, down-then-along is five.
        val fork = grid.index(5, 3)
        val n = net().row(2, 5, 3)
            .col(5, 1, 3)        // the short branch, consumer at (5, 1)
            .col(5, 3, 4).row(5, 9, 4)   // the long one, consumer at (9, 4)
        val f = n.toward(
            accepting = listOf(grid.index(5, 1), grid.index(9, 4)),
            from = listOf(grid.index(2, 3)),
        )

        assertEquals(
            listOf(grid.index(5, 2), grid.index(5, 4)),
            f.successorsOf(fork).toList().sorted(),
            "both branches are live, however unequal",
        )

        val diverters = DiverterWork(Diverters.EMPTY)
        var up = 0
        var down = 0
        repeat(8) {
            val h = held(n, fork to lump())
            step(f, h, diverters)
            if (h[grid.index(5, 2)] != null) up++
            if (h[grid.index(5, 4)] != null) down++
        }
        assertEquals(4, up, "half went the short way")
        assertEquals(4, down, "and half the long way")
    }

    @Test
    fun `a fork still refuses the branch that leads nowhere`() {
        // The other half of the same rule. Being one step further from the source is not enough —
        // a branch also has to lead to something, or a dead end would fill up like it used to.
        val fork = grid.index(5, 3)
        val n = net().row(2, 5, 3)
            .col(5, 1, 3)                // to a consumer
            .col(5, 3, 4).row(5, 9, 4)   // to nothing at all
        val f = n.toward(accepting = listOf(grid.index(5, 1)), from = listOf(grid.index(2, 3)))

        assertEquals(listOf(grid.index(5, 2)), f.successorsOf(fork).toList())
        assertFalse(f.isFed(grid.index(8, 4)), "the dead branch is not part of the network")
    }

    // ── A consumer with no room is traffic to drive round, not a wall ─────────

    /**
     * The bug that made this whole section necessary, in its simplest form.
     *
     * A consumer partway along a run has filled up. Everything behind it should carry on to the one
     * at the end. The first version of pulling could not do this at all: a tile with an input port
     * was distance zero and distance zero had no successors, so a packet the full machine refused
     * was pinned to that tile for ever and the rest of the line jammed behind it.
     */
    @Test
    fun `material runs past a consumer that has no room to one that has`() {
        val n = net().row(2, 8, 3)
        val fullOne = grid.index(5, 3)
        val end = grid.index(8, 3)
        val f = n.toward(accepting = listOf(end), full = listOf(fullOne))

        // The full one is an ordinary transit tile: it has a downhill successor, pointing onward.
        assertEquals(3, f.distanceAt(fullOne), "measured from the consumer that can actually take it")
        assertEquals(listOf(grid.index(6, 3)), f.successorsOf(fullOne).toList())

        val h = held(n, grid.index(2, 3) to lump())
        val taken = mutableListOf<Int>()
        repeat(12) {
            step(f, h) { tile, packet ->
                // The near building refuses everything; the far one takes it.
                if (tile == end) { taken.add(grid.xOf(tile)); null } else packet
            }
        }
        assertEquals(listOf(8), taken, "it should have got past the full machine")
    }

    /**
     * The other half, and the reason a full consumer is *demoted* rather than deleted.
     *
     * With nowhere better to be, material still travels toward the blockage and packs in behind it.
     * Dropping full consumers from the field entirely also fixed the bug above, but it emptied every
     * jammed line: the belt went bare while the backlog hid inside the machine feeding it. A jam
     * should be the most visible thing on the deck.
     */
    @Test
    fun `when the only consumer is full, material still queues up against it`() {
        val n = net().row(2, 8, 3)
        val end = grid.index(8, 3)
        val f = n.toward(accepting = emptyList(), full = listOf(end))

        assertTrue(f.isFed(grid.index(2, 3)), "the run still has a direction")
        assertEquals(listOf(grid.index(7, 3)), f.successorsOf(grid.index(6, 3)).toList())

        val h = held(n, grid.index(2, 3) to lump(), grid.index(3, 3) to lump())
        repeat(12) { step(f, h) }
        assertEquals(1_000L, h[end]?.mass, "the leader is against the blockage")
        assertEquals(1_000L, h[grid.index(7, 3)]?.mass, "and the next one is right behind it")
    }

    @Test
    fun `an accepting consumer anywhere beats a full one next door`() {
        // The penalty has to be heavier than any distance, or a run would prefer the blockage simply
        // because it was closer — which is the jam this fix exists to clear.
        val n = net().row(2, 8, 3)
        val f = n.toward(accepting = listOf(grid.index(8, 3)), full = listOf(grid.index(3, 3)))
        assertEquals(listOf(grid.index(5, 3)), f.successorsOf(grid.index(4, 3)).toList(), "onward, not back")
    }

    /**
     * The one that would catch the artifact. Same run, same buildings, same everything — but the
     * consumer is at the low-index end, so grid order and flow order disagree. If absorption were
     * resolved by walking tiles in index order, the *downstream* building would win here.
     */
    @Test
    fun `priority follows the flow, not the array`() {
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.index(2, 3))          // consumed at the left
        val h = held(n, grid.index(8, 3) to lump())

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
        // Nearest-to-sink first: each tile is emptied before the one behind moves into it.
        val n = net().row(2, 6, 3)
        val f = n.toward(grid.index(6, 3))
        val h = held(
            n,
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
    fun `a packet sitting on the consumer stays put rather than falling off the end`() {
        val n = net().row(2, 4, 3)
        val f = n.toward(grid.index(4, 3))
        val h = held(n, grid.index(4, 3) to lump())
        step(f, h)
        assertEquals(1_000L, h[grid.index(4, 3)]?.mass, "still there, waiting to be taken")
    }

    // ── Bunching up against a blockage ────────────────────────────────────────

    @Test
    fun `identical lumps squash together against a blockage`() {
        val n = net().row(2, 5, 3)
        val f = n.toward(grid.index(5, 3))
        val h = held(
            n,
            grid.index(4, 3) to lump(400L),
            grid.index(5, 3) to lump(400L),   // on the consumer, which is refusing
        )
        step(f, h)
        assertEquals(800L, h[grid.index(5, 3)]?.mass, "the one behind squashed into the one ahead")
        assertNull(h[grid.index(4, 3)], "leaving its tile free")
    }

    @Test
    fun `squashing stops at a full packet and the rest queues behind it`() {
        val n = net().row(2, 5, 3)
        val f = n.toward(grid.index(5, 3))
        val h = held(n, grid.index(4, 3) to lump(600L), grid.index(5, 3) to lump(700L))
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
        val n = net().row(2, 5, 3)
        val f = n.toward(grid.index(5, 3))
        val h = held(n, grid.index(4, 3) to dirty, grid.index(5, 3) to clean)

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
        val n = net().row(2, 5, 3)
        val f = n.toward(grid.index(5, 3))
        val h = held(n, grid.index(4, 3) to bar, grid.index(5, 3) to bar)

        step(f, h)
        assertEquals(400L, h[grid.index(5, 3)]?.mass, "still one bar")
        assertEquals(400L, h[grid.index(4, 3)]?.mass, "and the other queued behind it")
    }

    @Test
    fun `different forms never bunch, however alike their contents`() {
        val pure = Mixture.of(Species.Iron to 500L)
        val ingot = SolidPacket(Resource(Form.IronIngot, pure))
        val ore = SolidPacket(Resource(Form.Ore, pure))
        val n = net().row(2, 5, 3)
        val f = n.toward(grid.index(5, 3))
        val h = held(n, grid.index(4, 3) to ore, grid.index(5, 3) to ingot)

        step(f, h)
        assertEquals(500L, h[grid.index(5, 3)]?.mass, "an ingot is not a lump of ore")
        assertEquals(500L, h[grid.index(4, 3)]?.mass)
    }

    @Test
    fun `a jammed run bunches toward its destination over several ticks`() {
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.index(8, 3))
        val h = held(
            n,
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

    /**
     * A Y: material arriving along row 3 from the left, with a consumer at the end of each of two
     * branches leaving x=5. A fork only exists where *both* ways lead somewhere — which is the point
     * of pulling.
     */
    /**
     * A run that splits: in from the left along row 3, out to a consumer above and one below.
     *
     * The source at the left end is load-bearing, not scenery. Which way is forward at a junction is
     * decided by where material came in, so a fork with nothing feeding it is not a fork — it is two
     * consumers with some track between them.
     */
    private fun why(): Pair<Net, FlowField> {
        val n = net().row(2, 5, 3)
            .lay(grid.index(5, 2)).join(grid.index(5, 3), Direction.Up)
            .lay(grid.index(5, 4)).join(grid.index(5, 3), Direction.Down)
        return n to n.toward(
            accepting = listOf(grid.index(5, 2), grid.index(5, 4)),
            from = listOf(grid.index(2, 3)),
        )
    }

    @Test
    fun `a fork alternates instead of favouring a branch`() {
        val (n, f) = why()
        val diverters = DiverterWork(Diverters.EMPTY)
        val up = grid.index(5, 2)
        val down = grid.index(5, 4)

        val sent = mutableListOf<Int>()
        repeat(6) {
            val h = held(n, grid.index(5, 3) to lump())
            step(f, h, diverters)
            if (h[up] != null) sent.add(2)
            if (h[down] != null) sent.add(4)
        }
        // Which branch goes first follows ascending tile index (up, then down) — arbitrary, but
        // fixed, and the alternation after it is the part that matters. It used to follow
        // Direction's declaration order instead; branches are now sorted by index like every other
        // tie-break in the file, which is worth one less arbitrary ordering to remember.
        assertEquals(listOf(2, 4, 2, 4, 2, 4), sent, "even by construction, not by iteration luck")
    }

    @Test
    fun `a blocked branch does not consume its turn`() {
        // The point: a jam on one side must not quietly halve the other side's throughput. If the
        // cursor advanced past a branch it could not use, every other packet would be lost to it.
        val (n, f) = why()
        val diverters = DiverterWork(Diverters.EMPTY)
        val up = grid.index(5, 2)
        val down = grid.index(5, 4)

        var reachedDown = 0
        repeat(6) {
            // The upward branch is permanently occupied, so it can never accept.
            val h = held(n, grid.index(5, 3) to lump(), up to lump())
            step(f, h, diverters)
            if (h[down] != null) reachedDown++
        }
        assertEquals(6, reachedDown, "every packet should have taken the branch that was open")
    }

    @Test
    fun `diverter state survives a round trip`() {
        val (n, f) = why()
        val first = DiverterWork(Diverters.EMPTY)
        step(f, held(n, grid.index(5, 3) to lump()), first)
        val saved = first.snapshot()
        assertFalse(saved.isEmpty, "a fork that has sent something remembers which way")

        // Resuming from the snapshot continues the alternation rather than starting over.
        val resumed = DiverterWork(saved)
        val h = held(n, grid.index(5, 3) to lump())
        step(f, h, resumed)
        assertEquals(1, listOf(grid.index(5, 2), grid.index(5, 4)).count { h[it] != null })
        assertEquals(saved, Diverters.of(mapOf(grid.index(5, 3) to 1)), "and it is the state it looks like")
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    fun `the same network resolves the same way twice`() {
        fun digest(): String {
            val (n, f) = why()
            val diverters = DiverterWork(Diverters.EMPTY)
            val h = held(n, grid.index(2, 3) to lump())
            repeat(20) { step(f, h, diverters) }
            return (0 until grid.size).joinToString(",") { h[it]?.mass?.toString() ?: "-" } +
                "|" + diverters.snapshot()
        }
        assertEquals(digest(), digest())
    }

    @Test
    fun `the traversal order is total, so nothing depends on sort stability`() {
        val n = net().row(2, 6, 3).row(2, 6, 4)
        val f = n.toward(grid.index(6, 3), grid.index(6, 4))
        val order = f.order.toList()
        assertEquals(order.size, order.toSet().size, "every fed tile appears exactly once")
        // Distances are non-decreasing along the order: that is the property advancing relies on.
        val distances = order.map { f.distanceAt(it) }
        assertEquals(distances.sorted(), distances)
    }
}
