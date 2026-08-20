package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.RailLayer
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.FlowCursors
import org.emerge.demo.outofspace.world.FlowGraph
import org.emerge.demo.outofspace.world.MotionLog
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.TileIndex
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

    /**
     * A fraction of a packet, in thousandths. These tests are about *squashing and queueing*, not
     * about what a lump weighs, so they say "six tenths of a packet" and stay right through any
     * future change of scale — which is what they failed to do when a packet was a literal 1000 g.
     */
    private fun share(perMille: Int): Long = Capacity.PACKET_MASS * perMille / 1_000L

    private fun lump(mass: Long = Capacity.PACKET_MASS): Packet =
        SolidPacket(Mixture.of(Species.Iron to mass, energy = 0))

    /**
     * A network under construction: tiles that carry track, and the joins actually drawn between
     * them. Laying and joining are separate here for the same reason they are separate in the game —
     * two tiles of track next to each other are not a run.
     */
    private inner class Net {
        val tiles = mutableSetOf<TileIndex>()
        private val links = mutableSetOf<Pair<TileIndex, Direction>>()

        fun linked(tile: TileIndex, dir: Direction): Boolean = (tile to dir) in links

        /** Lays track without joining it to anything — a stub. */
        fun lay(tile: TileIndex): Net = apply { tiles.add(tile) }

        fun join(a: TileIndex, dir: Direction): Net = apply {
            val b = grid.neighbour(a, dir)
            require(b != TileIndex.NONE)
            tiles.add(a); tiles.add(b)
            links.add(a to dir)
            links.add(b to dir.opposite)
        }

        /** A horizontal run on row [y], laid and joined end to end. */
        fun row(fromX: Int, toX: Int, y: Int): Net = apply {
            for (x in minOf(fromX, toX)..maxOf(fromX, toX)) lay(grid.tile(x, y))
            for (x in minOf(fromX, toX) until maxOf(fromX, toX)) join(grid.tile(x, y), Direction.Right)
        }

        /** A vertical run on column [x]. */
        fun col(x: Int, fromY: Int, toY: Int): Net = apply {
            for (y in minOf(fromY, toY)..maxOf(fromY, toY)) lay(grid.tile(x, y))
            for (y in minOf(fromY, toY) until maxOf(fromY, toY)) join(grid.tile(x, y), Direction.Down)
        }

        /**
         * The flow field this network has, given where its consumers are — and, where it matters,
         * where material comes *in*.
         *
         * Leaving [from] empty is the "nobody is feeding this" case: material on the run drains
         * downhill to the nearest consumer. That is a real situation (a belt whose extractor was just
         * torn out) and most of the tests below only care about the downstream half, so they say
         * nothing about sources. A test about a **fork** must name one, because which way is forward
         * at a junction is a fact about where material entered.
         */
        fun toward(vararg sinks: Int): FlowGraph =
            FlowGraph.build(tiles, emptySet(), sinks.map { TileIndex(it) }.toSet(), ::linked, grid)

        fun toward(accepting: List<TileIndex>, from: List<TileIndex> = emptyList()): FlowGraph =
            FlowGraph.build(tiles, from.toSet(), accepting.toSet(), ::linked, grid)
    }

    private fun net(): Net = Net()

    private fun held(net: Net, vararg placed: Pair<TileIndex, Packet>): RailLayer {
        val out = RailLayer.empty(grid.size)
        for ((tile, packet) in placed) {
            require(tile in net.tiles) { "packet placed off the run" }
            out.put(tile, (packet as SolidPacket).contents)
        }
        return out
    }

    /** Reading a tile by raw index, so the assertions below read as they did against an array. */
    private operator fun RailLayer.get(index: Int): Mixture? = resourceAt(TileIndex(index))

    /** True when nothing is anywhere on the layer. */
    private fun RailLayer.allEmpty(): Boolean = (0 until tileCount).all { isEmpty(TileIndex(it)) }

    /**
     * A consumer at [tile] taking whatever is there — what [advanceSegments] wants back is the lump
     * that left, and taking it is the caller's job now that the layer is the one copy of it.
     */
    private fun RailLayer.take(tile: TileIndex): Packet? {
        val packet = packetAt(tile) ?: return null
        put(tile, null)
        return packet
    }

    private fun step(
        flow: FlowGraph,
        held: RailLayer,
        cursors: FlowCursors = FlowCursors(),
        log: MotionLog? = null,
        absorb: (TileIndex) -> Packet? = { null },
    ): Int = advanceSegments(flow, held, cursors, log, absorb = absorb)

    // ── Which way is downstream ───────────────────────────────────────────────

    @Test
    fun `material flows toward the consumer, whichever end of the run it is on`() {
        val n = net().row(2, 8, 3)

        val toRight = n.toward(grid.tile(8, 3).index)
        for (x in 2..7) {
            assertEquals(listOf(grid.tile(x + 1, 3)), toRight.successorTiles(grid.tile(x, 3)), "($x, 3) points the wrong way")
        }
        assertEquals(emptyList(), toRight.successorTiles(grid.tile(8, 3)), "the sink is the end of the line")

        // The identical run, consumed at the other end. Nothing about the tiles changed.
        val toLeft = n.toward(grid.tile(2, 3).index)
        for (x in 3..8) {
            assertEquals(listOf(grid.tile(x - 1, 3)), toLeft.successorTiles(grid.tile(x, 3)), "($x, 3) points the wrong way")
        }
        assertEquals(emptyList(), toLeft.successorTiles(grid.tile(2, 3)))
    }

    @Test
    fun `a run with no consumer on it moves nothing at all`() {
        // The whole difference between pulling and pushing. Under the old push model this run had a
        // source, so material marched down it and piled up at the far end; a half-built line filled
        // with stock you then had to dig back out. With nothing to pull, nothing moves.
        val n = net().row(2, 8, 3)
        val f = n.toward()      // no sinks anywhere
        val h = held(n, grid.tile(2, 3) to lump())

        repeat(10) { step(f, h) }
        assertEquals(Capacity.PACKET_MASS, h[grid.tile(2, 3).index]?.total, "it stayed exactly where it was put")
        assertFalse(f.isFed(grid.tile(5, 3)))
    }

    @Test
    fun `a segment that cannot reach a consumer carries nothing`() {
        val n = net().row(2, 5, 3).row(8, 10, 3)     // same row, but with a gap at 6..7
        val f = n.toward(grid.tile(2, 3).index)

        assertTrue(f.isFed(grid.tile(5, 3)))
        assertFalse(f.isFed(grid.tile(8, 3)), "no route to a consumer, so no upstream")
        assertEquals(0, f.successorTiles(grid.tile(8, 3)).size)
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
        val n = net().row(2, 5, 3).col(5, 3, 5).lay(grid.tile(5, 2)).join(grid.tile(5, 3), Direction.Up)
        val sink = grid.tile(5, 5)
        val deadEnd = grid.tile(5, 2)
        val f = n.toward(sink.index)

        // Note what is *not* claimed: the stub is not cut out of the graph. It has a way *out* —
        // back down to the junction — because material stranded on it should drain rather than sit
        // there for ever. Having a way out is not the same as being sent anything: permission is
        // per direction, and the junction below simply has no permission pointing up.
        assertEquals(
            listOf(grid.tile(5, 3)),
            f.successorTiles(deadEnd),
            "the stub drains back toward the consumer",
        )
        assertEquals(
            listOf(grid.tile(5, 4)),
            f.successorTiles(grid.tile(5, 3)).toList(),
            "the junction is not a junction: only one way leads anywhere",
        )

        var delivered = 0L
        val h = held(n, grid.tile(2, 3) to lump())
        repeat(10) { step(f, h) { tile -> if (tile == sink) h.take(tile)?.also { delivered += it.mass } else null } }
        assertEquals(Capacity.PACKET_MASS, delivered, "all of it reached the building")
        assertNull(h[deadEnd.index], "and none of it went up the stub")
    }

    @Test
    fun `a line between two consumers with nothing feeding it splits at its midpoint`() {
        // This has now claimed three different things, and the history is the point.
        //
        // It began by claiming the midpoint could go either way, on the reasoning that two consumers
        // are equally good. Then it claimed the whole line drains to one end, because an edge
        // carries material one way only and a direction is only justified by leading to a producer —
        // with none, nothing justified anything and the last consumer traversed took the lot.
        //
        // ⛔ **That answer was not merely arbitrary, it was unstable.** `carrying` was an input to
        // it, so a lump moving one tile rebuilt the graph differently and reversed the edge behind
        // itself; the lump walked back down and inched along one tile per round trip. Stu's save,
        // the ore column at (24,30). So producer-less track is now oriented by distance, and a belt
        // whose extractor was just torn out empties from both ends toward whichever consumer each
        // tile is nearer. Stu's call, 2026-08-20.
        val n = net().row(2, 10, 3)
        val f = n.toward(grid.tile(2, 3).index, grid.tile(10, 3).index)

        for (x in 3..5) {
            assertEquals(listOf(grid.tile(x - 1, 3)), f.successorTiles(grid.tile(x, 3)), "($x, 3) drains left")
        }
        for (x in 7..9) {
            assertEquals(listOf(grid.tile(x + 1, 3)), f.successorTiles(grid.tile(x, 3)), "($x, 3) drains right")
        }
        // Four hops either way, so it is a genuine fork and the diverter alternates. It cannot
        // dither: every edge steps strictly nearer a consumer, so whichever way a packet goes it
        // cannot come back.
        assertEquals(
            listOf(grid.tile(5, 3), grid.tile(7, 3)),
            f.successorTiles(grid.tile(6, 3)),
            "and the midpoint forks",
        )
        assertEquals(emptyList(), f.successorTiles(grid.tile(2, 3)), "neither consumer has a road out")
        assertEquals(emptyList(), f.successorTiles(grid.tile(10, 3)))
    }

    // ── Explicit connection ───────────────────────────────────────────────────

    @Test
    fun `two runs side by side do not merge, however close they are`() {
        // Touching is not joining. Without this, every parallel line needs a tile of clearance and a
        // factory sprawls for reasons the player cannot see.
        val n = net().row(2, 8, 3).row(2, 8, 4)
        val f = n.toward(grid.tile(8, 3).index)

        assertTrue(f.isFed(grid.tile(2, 3)), "its own row is fed")
        assertFalse(f.isFed(grid.tile(2, 4)), "the row beneath is a different run")
        assertFalse(f.isFed(grid.tile(8, 4)), "even the tile directly under the consumer")
    }

    @Test
    fun `a run crosses another without touching it`() {
        // The bridge case, in the flow model: a horizontal line and a vertical line sharing tile
        // (5,3) is impossible, so they pass on either side of it and neither knows about the other.
        val n = net().row(2, 8, 3).col(5, 0, 2).col(5, 4, 5)
        val f = n.toward(grid.tile(8, 3).index)

        assertTrue(f.isFed(grid.tile(4, 3)) && f.isFed(grid.tile(6, 3)), "the horizontal run is whole")
        assertFalse(f.isFed(grid.tile(5, 2)), "the vertical run above is not connected to it")
        assertFalse(f.isFed(grid.tile(5, 4)), "nor the part below")
    }

    @Test
    fun `laid track that was never joined moves nothing`() {
        val n = net().row(2, 5, 3).lay(grid.tile(6, 3))   // 6 is adjacent to 5, but unjoined
        val f = n.toward(grid.tile(6, 3).index)

        assertTrue(f.isFed(grid.tile(6, 3)), "the stub is the sink, so it is trivially part of the flow")
        assertFalse(f.isFed(grid.tile(5, 3)), "but nothing reaches it")
        val h = held(n, grid.tile(5, 3) to lump())
        step(f, h)
        assertEquals(Capacity.PACKET_MASS, h[grid.tile(5, 3).index]?.total, "the gap in the graph is a real gap")
    }

    // ── Order of absorption ───────────────────────────────────────────────────

    @Test
    fun `the first input along the run takes the packet`() {
        // Two buildings tapping the same run at 5 and 7, with the consumer end at 8. Material
        // travelling right meets 5 first, and 5 wins.
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.tile(8, 3).index)
        val h = held(n, grid.tile(2, 3) to lump())

        val taken = mutableListOf<Int>()
        repeat(10) {
            step(f, h) { tile ->
                if (tile == grid.tile(5, 3) || tile == grid.tile(7, 3)) {
                    taken.add(grid.xOf(tile)); h.take(tile)
                } else null
            }
        }
        assertEquals(listOf(5), taken, "upstream starves downstream, and that is the mechanic")
    }

    @Test
    fun `a full building lets the packet carry on to the next one`() {
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.tile(8, 3).index)
        val h = held(n, grid.tile(2, 3) to lump())

        val taken = mutableListOf<Int>()
        repeat(10) {
            step(f, h) { tile ->
                // The near building is full and refuses; the far one takes it.
                if (tile == grid.tile(7, 3)) { taken.add(grid.xOf(tile)); h.take(tile) } else null
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
        val fork = grid.tile(5, 3)
        val n = net().row(2, 5, 3)
            .col(5, 1, 3)        // the short branch, consumer at (5, 1)
            .col(5, 3, 4).row(5, 9, 4)   // the long one, consumer at (9, 4)
        val f = n.toward(
            accepting = listOf(grid.tile(5, 1), grid.tile(9, 4)),
            from = listOf(grid.tile(2, 3)),
        )

        assertEquals(
            listOf(grid.tile(5, 2), grid.tile(5, 4)),
            f.successorTiles(fork).sortedBy { it.index },
            "both branches are live, however unequal",
        )

        val cursors = FlowCursors()
        var up = 0
        var down = 0
        repeat(8) {
            val h = held(n, fork to lump())
            step(f, h, cursors)
            if (h[grid.tile(5, 2).index] != null) up++
            if (h[grid.tile(5, 4).index] != null) down++
        }
        assertEquals(4, up, "half went the short way")
        assertEquals(4, down, "and half the long way")
    }

    @Test
    fun `a fork still refuses the branch that leads nowhere`() {
        // The other half of the same rule. Being one step further from the source is not enough —
        // a branch also has to lead to something, or a dead end would fill up like it used to.
        val fork = grid.tile(5, 3)
        val n = net().row(2, 5, 3)
            .col(5, 1, 3)                // to a consumer
            .col(5, 3, 4).row(5, 9, 4)   // to nothing at all
        val f = n.toward(accepting = listOf(grid.tile(5, 1)), from = listOf(grid.tile(2, 3)))

        assertEquals(listOf(grid.tile(5, 2)), f.successorTiles(fork))

        // The dead branch does now have a way *out* — anything stranded on it drains back toward
        // the consumer rather than sitting there for ever, which is the same courtesy a run whose
        // producer was dismantled gets. That is a change from when nothing entered *or* left a dead
        // end, and it is the safe half: what must never happen is material being sent down it.
        val stranded = grid.tile(8, 4)
        val held = held(n, stranded to lump())
        val cursors = FlowCursors()
        repeat(20) { advanceSegments(f, held, cursors) { tile -> if (tile == grid.tile(5, 1)) held.take(tile) else null } }
        assertTrue(held.allEmpty(), "material stranded on a dead branch never got off it")
        assertNull(held[grid.tile(9, 4).index], "and nothing was pushed further into the dead end")
    }

    // ── Two things feeding one line ──────────────────────────────────────────

    /**
     * A merge: a second producer joins a line that already has one further up it.
     *
     * This is the ordinary shape of a bridge dropping material onto a main run, and it broke the
     * first version of the source sweep badly enough to be worth two tests. Depth is measured from
     * *every* source at once, so a producer joining partway along does not merely add material — it
     * resets depth to zero where it lands and **inverts** the gradient over everything upstream of
     * it. The two waves meet somewhere in the middle at a tile whose neighbours are both no deeper
     * than it is, and a tile with nothing deeper next to it has no forward at all.
     *
     * The far producer's material then stops dead at that watershed, and because a branch leading
     * nowhere is not worth entering, the emptiness propagates back up the line until the producer
     * itself has nowhere to put anything. Half the run goes quiet while the near producer, whose
     * material happens to be flowing the way depth points, carries on perfectly.
     */
    @Test
    fun `a second producer joining a line does not strand the first`() {
        val sink = grid.tile(1, 1)
        val far = grid.tile(10, 1)
        val joining = grid.tile(3, 1)
        val n = net().row(1, 10, 1)
        val f = n.toward(accepting = listOf(sink), from = listOf(far, joining))

        // Every tile between the far producer and the sink still has somewhere to send material,
        // and it is always the next tile towards the sink.
        for (x in 2..10) {
            val tile = grid.tile(x, 1)
            assertEquals(
                listOf(grid.tile(x - 1, 1)),
                f.successorTiles(tile).toList(),
                "(${x}, 1) has lost its way to the consumer",
            )
        }
    }

    @Test
    fun `material from the far end of a merged line actually arrives`() {
        val sink = grid.tile(1, 1)
        val far = grid.tile(10, 1)
        val n = net().row(1, 10, 1)
        val f = n.toward(accepting = listOf(sink), from = listOf(far, grid.tile(3, 1)))

        val held = held(n, far to lump())
        val cursors = FlowCursors()
        var arrived = false
        // Nine steps to walk; give it room to spare and stop when the consumer takes it.
        repeat(20) {
            advanceSegments(f, held, cursors) { tile ->
                if (tile == sink) { arrived = true; held.take(tile) } else null
            }
        }
        assertTrue(arrived, "the far producer's material never reached the consumer")
    }

    // ── A consumer that refuses is traffic to drive round, not a wall ─────────

    /**
     * The section that made the graph state-independent.
     *
     * A consumer partway along a run refuses what is offered — it is full, or it only takes ore and
     * this is an ingot. Everything behind it should carry on to the one at the end.
     *
     * There were two earlier goes at this and both put the refusal into the *topology*. The first
     * made every input port distance zero, and distance zero had no successors, so a refused packet
     * was pinned to that tile for ever and the line jammed behind it. The second fixed that by
     * demoting a full consumer to a transit tile — which worked, but meant the shape of the network
     * now depended on how full a smelter happened to be, and every rule about direction had to be
     * restated in terms of fullness. Filtering by *form* is what made that untenable: "full" is one
     * bit, but "would take this particular packet" is a question you cannot answer before you know
     * which packet.
     *
     * So the graph no longer knows. Every input port is a destination, permanently, and a tile at a
     * destination still has permission to move onward. Refusal happens at the tile, when a specific
     * packet is offered, and the traffic simply keeps going.
     */
    @Test
    fun `material runs past a consumer that refuses it to one that will take it`() {
        val n = net().row(2, 8, 3)
        val refuses = grid.tile(5, 3)
        val end = grid.tile(8, 3)
        // Both machines are destinations as far as the graph is concerned. Nothing here says which
        // of them has room.
        val f = n.toward(accepting = listOf(refuses, end), from = listOf(grid.tile(2, 3)))

        // A destination partway along is an ordinary transit tile too: it still points onward.
        assertEquals(listOf(grid.tile(6, 3)), f.successorTiles(refuses), "a consumer is not a wall")

        // The stretch between two consumers is a two-way street — either end is a real destination —
        // so the packet is free to hesitate there. The cursor is what carries it through, and it has
        // to persist across ticks to do that.
        val h = held(n, grid.tile(2, 3) to lump())
        val cursors = FlowCursors()
        val taken = mutableListOf<Int>()
        repeat(20) {
            step(f, h, cursors) { tile ->
                // The near building refuses everything; the far one takes it.
                if (tile == end) { taken.add(grid.xOf(tile)); h.take(tile) } else null
            }
        }
        assertEquals(listOf(8), taken, "it should have got past the machine that refused it")
    }

    /**
     * The other half, and the reason a refusing consumer is not cut out of the graph.
     *
     * With nowhere better to be, material still travels toward the blockage and packs in behind it.
     * Deleting jammed consumers from the field also fixed the bug above, but it emptied every jammed
     * line: the belt went bare while the backlog hid inside the machine feeding it. A jam should be
     * the most visible thing on the deck.
     *
     * That falls out for free now — the graph never knew the consumer was jammed, so it never
     * stopped pointing at it.
     */
    @Test
    fun `when the only consumer refuses, material still queues up against it`() {
        val n = net().row(2, 8, 3)
        val end = grid.tile(8, 3)
        val f = n.toward(accepting = listOf(end), from = listOf(grid.tile(2, 3)))

        assertTrue(f.isFed(grid.tile(2, 3)), "the run still has a direction")
        assertEquals(listOf(grid.tile(7, 3)), f.successorTiles(grid.tile(6, 3)))

        val h = held(n, grid.tile(2, 3) to lump(), grid.tile(3, 3) to lump())
        // Nobody takes anything: the absorb callback hands every packet straight back.
        repeat(12) { step(f, h) }
        assertEquals(Capacity.PACKET_MASS, h[end.index]?.total, "the leader is against the blockage")
        assertEquals(Capacity.PACKET_MASS, h[grid.tile(7, 3).index]?.total, "and the next one is right behind it")
    }

    // There is deliberately no test that the graph is unchanged when a machine fills up. Under the
    // demotion model that needed watching — a run could prefer a blockage simply because it was
    // closer, so a full consumer had to be pushed further away than any real distance could reach,
    // and the size of that penalty was a thing to get wrong. There is nothing to tune now and no
    // second graph to compare against: [FlowGraph.build] takes track, ports and sources, and there
    // is no argument through which fullness could reach it. The signature is the assertion.

    /**
     * The one that would catch the artifact. Same run, same buildings, same everything — but the
     * consumer is at the low-index end, so grid order and flow order disagree. If absorption were
     * resolved by walking tiles in index order, the *downstream* building would win here.
     */
    @Test
    fun `priority follows the flow, not the array`() {
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.tile(2, 3).index)          // consumed at the left
        val h = held(n, grid.tile(8, 3) to lump())

        val taken = mutableListOf<Int>()
        repeat(10) {
            step(f, h) { tile ->
                if (tile == grid.tile(5, 3) || tile == grid.tile(7, 3)) {
                    taken.add(grid.xOf(tile)); h.take(tile)
                } else null
            }
        }
        assertEquals(listOf(7), taken, "flowing right-to-left, 7 is the upstream one")
    }

    // ── Movement ──────────────────────────────────────────────────────────────

    @Test
    fun `a packed run advances along its whole length in one pass`() {
        // Nearest-to-sink first: each tile is emptied before the one behind moves into it.
        val n = net().row(2, 6, 3)
        val f = n.toward(grid.tile(6, 3).index)
        val h = held(
            n,
            grid.tile(2, 3) to lump(),
            grid.tile(3, 3) to lump(),
            grid.tile(4, 3) to lump(),
        )
        val moved = step(f, h)
        assertEquals(3, moved, "all three moved, not just the leading one")
        assertNull(h[grid.tile(2, 3).index])
        assertEquals(3, (3..5).count { h[grid.tile(it, 3).index] != null })
    }

    @Test
    fun `a packet passing an output port partway along a run still moves only one tile`() {
        // A machine's output port sits on a tile of a run that already carries material — a second
        // extractor feeding a shared line, or a bridge putting material down on it. That tile is a
        // source, so depth there is zero and every tile *behind* it has no forward at all and falls
        // back to moving downhill. The two rules interleave in the traversal order, and the source
        // tile ends up walked after the tile that feeds it: the packet is moved into it and then
        // straight out of it again, jumping two tiles in one step and appearing to skip the port.
        //
        // One tile per advance is a fact about the packet, not about the walk.
        val n = net().row(2, 10, 3)
        val sink = grid.tile(10, 3)
        val midOutput = grid.tile(6, 3)
        val f = n.toward(accepting = listOf(sink), from = listOf(grid.tile(2, 3), midOutput))

        val h = held(n, grid.tile(5, 3) to lump())
        step(f, h)
        assertNull(h[grid.tile(5, 3).index], "it left the tile behind the port")
        assertEquals(Capacity.PACKET_MASS, h[midOutput.index]?.total, "and stopped on the port's own tile")
        assertNull(h[grid.tile(7, 3).index], "rather than carrying straight over it")
    }

    @Test
    fun `a packet sitting on the consumer stays put rather than falling off the end`() {
        val n = net().row(2, 4, 3)
        val f = n.toward(grid.tile(4, 3).index)
        val h = held(n, grid.tile(4, 3) to lump())
        step(f, h)
        assertEquals(Capacity.PACKET_MASS, h[grid.tile(4, 3).index]?.total, "still there, waiting to be taken")
    }

    // ── Bunching up against a blockage ────────────────────────────────────────

    /**
     * A jammed run queues, tile by tile, and loses nothing doing it.
     *
     * ⚠️ This used to assert the opposite shape — that the four lumps *bunched into one* at the end.
     * They no longer merge at all (Stu, 2026-08-19), so what a jam looks like now is a queue rather
     * than a pile. The property worth keeping is the one underneath either behaviour: material
     * pressed against a blockage is still all there, on as many tiles as it takes.
     */
    @Test
    fun `a jammed run queues without losing anything`() {
        val n = net().row(2, 8, 3)
        val f = n.toward(grid.tile(8, 3).index)
        val h = held(
            n,
            grid.tile(5, 3) to lump(share(250)),
            grid.tile(6, 3) to lump(share(250)),
            grid.tile(7, 3) to lump(share(250)),
            grid.tile(8, 3) to lump(share(250)),
        )
        repeat(8) { step(f, h) }

        assertEquals(4, (2..8).count { h[grid.tile(it, 3).index] != null }, "four lumps went in")
        assertEquals(
            listOf(share(250), share(250), share(250), share(250)),
            (5..8).map { h[grid.tile(it, 3).index]?.total },
            "and four identical lumps should be queued against the blocked end",
        )
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
    private fun why(): Pair<Net, FlowGraph> {
        val n = net().row(2, 5, 3)
            .lay(grid.tile(5, 2)).join(grid.tile(5, 3), Direction.Up)
            .lay(grid.tile(5, 4)).join(grid.tile(5, 3), Direction.Down)
        return n to n.toward(
            accepting = listOf(grid.tile(5, 2), grid.tile(5, 4)),
            from = listOf(grid.tile(2, 3)),
        )
    }

    @Test
    fun `a fork alternates instead of favouring a branch`() {
        val (n, f) = why()
        val cursors = FlowCursors()
        val up = grid.tile(5, 2)
        val down = grid.tile(5, 4)

        val sent = mutableListOf<Int>()
        repeat(6) {
            val h = held(n, grid.tile(5, 3) to lump())
            step(f, h, cursors)
            if (h[up.index] != null) sent.add(2)
            if (h[down.index] != null) sent.add(4)
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
        val cursors = FlowCursors()
        val up = grid.tile(5, 2)
        val down = grid.tile(5, 4)

        var reachedDown = 0
        repeat(6) {
            // The upward branch is permanently occupied, so it can never accept.
            val h = held(n, grid.tile(5, 3) to lump(), up to lump())
            step(f, h, cursors)
            if (h[down.index] != null) reachedDown++
        }
        assertEquals(6, reachedDown, "every packet should have taken the branch that was open")
    }

    @Test
    fun `cursor state survives a round trip`() {
        val (n, f) = why()
        val cursors1 = FlowCursors()
        step(f, held(n, grid.tile(5, 3) to lump()), cursors1)
        val saved: Map<TileIndex, Int> = cursors1.snapshot()
        assertTrue(saved.isNotEmpty(), "a fork that has sent something remembers which way")

        // Resuming from the snapshot continues the alternation rather than starting over.
        val cursors2 = FlowCursors()
        cursors2.restore(saved)
        val h = held(n, grid.tile(5, 3) to lump())
        step(f, h, cursors2)
        assertEquals(1, listOf(grid.tile(5, 2), grid.tile(5, 4)).count { h[it.index] != null })
        assertEquals(saved, mapOf(grid.tile(5, 3) to 1), "and it is the state it looks like")
    }

    // ── Merges ────────────────────────────────────────────────────────────────

    /**
     * Two runs joining one line, both loaded, every tick.
     *
     * From a save Stu sent: a junction fed from two directions took from the same one every single
     * tick and the other never moved at all. Not a preference — a starvation. There was no merge
     * arbitration in the transport layer at all: a fork had a cursor and took turns, and a merge was
     * settled by whichever feeder happened to sort earlier in the traversal order, which never
     * changes. A merge takes turns now, exactly as a fork does.
     */
    private fun merging(): Triple<Net, FlowGraph, Pair<TileIndex, TileIndex>> {
        val n = net().row(2, 6, 3).lay(grid.tile(5, 4)).join(grid.tile(5, 3), Direction.Down)
        val f = n.toward(
            accepting = listOf(grid.tile(2, 3)),
            from = listOf(grid.tile(6, 3), grid.tile(5, 4)),
        )
        return Triple(n, f, grid.tile(6, 3) to grid.tile(5, 4))
    }

    @Test
    fun `a merge alternates instead of starving one of its feeders`() {
        val (n, f, feeders) = merging()
        val (fromRight, fromBelow) = feeders
        val junction = grid.tile(5, 3)
        assertEquals(
            listOf(fromBelow, fromRight).sortedBy { it.index },
            f.feeders(junction).sortedBy { it.index },
            "the junction is fed from both",
        )

        val cursors = FlowCursors()
        val sent = mutableListOf<String>()
        repeat(6) {
            val h = held(n, fromRight to lump(), fromBelow to lump())
            step(f, h, cursors)
            if (h[fromRight.index] == null) sent.add("right")
            if (h[fromBelow.index] == null) sent.add("below")
        }
        assertEquals(
            listOf("right", "below", "right", "below", "right", "below"),
            sent,
            "each feeder should get every other turn",
        )
    }

    /**
     * ⛔ **A packet is never merged into.** Two half-loads pressed together on a jammed run stay two
     * half-loads: the one behind queues, and neither changes composition.
     *
     * This is the rule that replaced belt blending (Stu, 2026-08-19). A lump whose contents can
     * change under whatever happens to be routed at it has no stable answer to "what is on its way
     * to this tile", and that question is the whole of demand-based flow. Blending is a storage
     * operation now.
     */
    @Test
    fun `two lumps pressed together stay two lumps`() {
        val half = SolidPacket(Mixture.of(Species.Iron to share(400), energy = 0))
        val n = net().row(2, 5, 3)
        val f = n.toward(grid.tile(5, 3).index)
        val h = held(n, grid.tile(4, 3) to half, grid.tile(5, 3) to half)

        step(f, h)
        assertEquals(share(400), h[grid.tile(5, 3).index]?.total, "the lump ahead grew")
        assertEquals(share(400), h[grid.tile(4, 3).index]?.total, "and the one behind should have queued")
    }

    /** A tile carrying anything has no room at all, however light what it carries. */
    @Test
    fun `a loaded tile has no headroom`() {
        val h = RailLayer.empty(grid.size)
        val tile = grid.tile(4, 3)
        assertEquals(Capacity.PACKET_MASS, h.headroom(tile), "an empty tile takes a whole belt-load")
        h.put(tile, Mixture.of(Species.Iron to share(50), energy = 0))
        assertEquals(0L, h.headroom(tile), "a tile with a crumb on it takes nothing more")
        assertFalse(h.loadOnto(tile, Mixture.of(Species.Iron to share(50), energy = 0)), "and refuses a load")
    }

    @Test
    fun `an empty feeder does not consume its turn at a merge`() {
        // The same courtesy a fork extends to a blocked branch. A junction whose turn falls on a run
        // with nothing on it must take from the run that does, or a quiet feeder would cost the busy
        // one half its throughput for nothing.
        val (n, f, feeders) = merging()
        val (fromRight, fromBelow) = feeders

        val cursors = FlowCursors()
        var delivered = 0
        repeat(6) {
            // Only ever the one feeder loaded; the other is bare track.
            val h = held(n, fromBelow to lump())
            step(f, h, cursors)
            if (h[fromBelow.index] == null) delivered++
        }
        assertEquals(6, delivered, "every packet should have got away")
        assertNull(f.feeders(grid.tile(5, 3)).firstOrNull { it !in setOf(fromRight, fromBelow) })
    }

    @Test
    fun `merge cursor state survives a round trip`() {
        val (n, f, feeders) = merging()
        val (fromRight, fromBelow) = feeders
        val cursors = FlowCursors()
        step(f, held(n, fromRight to lump(), fromBelow to lump()), cursors)
        val saved = cursors.mergeSnapshot()
        assertTrue(saved.isNotEmpty(), "a junction that has taken from someone remembers who")

        val resumed = FlowCursors(cursors.snapshot(), saved)
        val h = held(n, fromRight to lump(), fromBelow to lump())
        step(f, h, resumed)
        assertNull(h[fromBelow.index], "resuming continues the alternation rather than starting over")
    }

    // ── Determinism ───────────────────────────────────────────────────────────

    @Test
    fun `the same network resolves the same way twice`() {
        fun digest(): String {
            val (n, f) = why()
            val cursors = FlowCursors()
            val h = held(n, grid.tile(2, 3) to lump())
            repeat(20) { step(f, h, cursors) }
            return (0 until grid.size).joinToString(",") { h[it]?.total?.toString() ?: "-" } +
                "|" + cursors.snapshot()
        }
        assertEquals(digest(), digest())
    }

    /**
     * A consumer partway along a line that carries on to a second consumer.
     *
     * From a save Stu sent: material crawled past the first tank at half speed. The order the
     * advance walks tiles in was layered by hops from the *nearest* sink, and a consumer is zero
     * hops from itself — so it sorted to the very front, ahead of the tiles it feeds. Material it
     * refused could then only move on the ticks when the tile ahead happened to already be empty.
     *
     * Half throughput is the mild version. The rule it broke is the one the single-pass advance
     * rests on, so it is asserted directly here rather than through anything that stands in for it.
     */
    @Test
    fun `a consumer partway along a run does not halve what gets past it`() {
        val n = net().row(2, 10, 3)
        val partway = grid.tile(5, 3)
        val f = n.toward(accepting = listOf(partway, grid.tile(10, 3)), from = listOf(grid.tile(2, 3)))

        val position = f.order.withIndex().associate { (i, tile) -> tile to i }
        for (tile in f.order) {
            for (target in f.successorTiles(tile)) {
                assertTrue(
                    position.getValue(target) < position.getValue(tile),
                    "$tile is walked before $target, which it feeds",
                )
            }
        }

        // And the throughput that followed from it: a packet a tick, with the run kept full behind.
        val h = held(n, grid.tile(2, 3) to lump())
        val cursors = FlowCursors()
        var arrived = 0
        repeat(8) {
            step(f, h, cursors) { tile ->
                // The near consumer refuses everything; only the far one takes delivery.
                if (tile == grid.tile(10, 3)) { arrived++; h.take(tile) } else null
            }
            if (h.isEmpty(grid.tile(2, 3))) h.put(grid.tile(2, 3), (lump() as SolidPacket).contents)
        }
        assertEquals(Capacity.PACKET_MASS, h[partway.index]?.total, "the run is packed right through the near consumer")
        assertEquals(Capacity.PACKET_MASS, h[grid.tile(6, 3).index]?.total, "including the tile just past it")
    }

    @Test
    fun `the traversal order is total, so nothing depends on sort stability`() {
        val n = net().row(2, 6, 3).row(2, 6, 4)
        val f = n.toward(grid.tile(6, 3).index, grid.tile(6, 4).index)
        val order = f.order.toList()
        assertEquals(order.size, order.toSet().size, "every fed tile appears exactly once")
        // The property advancing actually relies on, asserted directly rather than through a proxy:
        // a tile is never walked before somewhere it can send material to.
        val position = order.withIndex().associate { (i, tile) -> tile to i }
        for (tile in order) {
            for (target in f.successorTiles(tile)) {
                val ahead = position[target] ?: continue
                assertTrue(ahead < position.getValue(tile), "$tile was walked before $target, which it feeds")
            }
        }
    }
}
