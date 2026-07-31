package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Segment

/**
 * Track for a test world, laid **and joined**, the way a drag lays it.
 *
 * Track stopped connecting by adjacency, so a fixture that only fills in segments builds a pile of
 * disconnected tiles rather than a run — and every test over it fails in the same uninformative way.
 * Doing the joining in one place means a test says "a line from here to here" and gets one.
 *
 * Only straight runs, deliberately. A test that wants a shape more complicated than a row and a
 * column is describing a network worth writing out link by link, where the reader can see it.
 */
class RailPlan(private val grid: Grid) {
    private val rails = arrayOfNulls<Segment>(grid.size)

    /**
     * Track with no join to anything — a stub, which is what a single click builds.
     *
     * Laying over track that is already there **keeps its joins**. Replacing the segment outright
     * looks harmless and is not: a run drawn across an existing one wipes that tile's links while
     * its neighbours keep theirs, leaving a one-sided join and a network that is connected in one
     * direction only. That is exactly how the first version of this helper silently cut every
     * crossing it drew.
     */
    fun lay(x: Int, y: Int, channel: Channel? = null): RailPlan = apply {
        if (!grid.inBounds(x, y)) return@apply
        val at = grid.index(x, y)
        val existing = rails[at]
        rails[at] = existing?.copy(channel = channel ?: existing.channel)
            ?: Segment(Conduit.Rail, channel = channel)
    }

    /** Joins two adjacent tiles, both halves, exactly as [Edit.Lay] does. */
    fun join(x: Int, y: Int, dir: Direction): RailPlan = apply {
        val a = grid.index(x, y)
        val b = grid.neighbour(a, dir)
        if (b < 0) return@apply
        rails[a] = (rails[a] ?: Segment(Conduit.Rail)).joinedTo(dir)
        rails[b] = (rails[b] ?: Segment(Conduit.Rail)).joinedTo(dir.opposite)
    }

    /** A horizontal run on row [y], inclusive, with optional gauges at given x positions. */
    fun row(fromX: Int, toX: Int, y: Int, channelAt: Map<Int, Channel> = emptyMap()): RailPlan = apply {
        val lo = minOf(fromX, toX)
        val hi = maxOf(fromX, toX)
        for (x in lo..hi) lay(x, y, channelAt[x])
        for (x in lo until hi) join(x, y, Direction.Right)
    }

    /** A vertical run on column [x], inclusive. */
    fun col(x: Int, fromY: Int, toY: Int): RailPlan = apply {
        val lo = minOf(fromY, toY)
        val hi = maxOf(fromY, toY)
        for (y in lo..hi) lay(x, y)
        for (y in lo until hi) join(x, y, Direction.Down)
    }

    fun list(): List<Segment?> = rails.toList()
}

/** `rails(grid) { row(4, 14, 5) }` — the usual way a test states its track. */
fun rails(grid: Grid, build: RailPlan.() -> Unit): List<Segment?> = RailPlan(grid).apply(build).list()

// ── In-place variants ─────────────────────────────────────────────────────────
//
// For fixtures that already own a segment array and only want a connected run laid into it. Same
// rule as everywhere else: laying is not joining, so these do both.

fun joinRow(grid: Grid, rails: Array<Segment?>, fromX: Int, toX: Int, y: Int, channelAt: Map<Int, Channel> = emptyMap()) {
    val lo = minOf(fromX, toX)
    val hi = maxOf(fromX, toX)
    for (x in lo..hi) layInto(grid, rails, x, y, channelAt[x])
    for (x in lo until hi) linkPair(grid, rails, grid.index(x, y), Direction.Right)
}

fun joinCol(grid: Grid, rails: Array<Segment?>, x: Int, fromY: Int, toY: Int) {
    val lo = minOf(fromY, toY)
    val hi = maxOf(fromY, toY)
    for (y in lo..hi) layInto(grid, rails, x, y, null)
    for (y in lo until hi) linkPair(grid, rails, grid.index(x, y), Direction.Down)
}

/** Lays track, preserving the joins of anything already at that tile. See [RailPlan.lay]. */
private fun layInto(grid: Grid, rails: Array<Segment?>, x: Int, y: Int, channel: Channel?) {
    if (!grid.inBounds(x, y)) return
    val at = grid.index(x, y)
    val existing = rails[at]
    rails[at] = existing?.copy(channel = channel ?: existing.channel)
        ?: Segment(Conduit.Rail, channel = channel)
}

private fun linkPair(grid: Grid, rails: Array<Segment?>, a: Int, dir: Direction) {
    val b = grid.neighbour(a, dir)
    if (b < 0) return
    rails[a] = (rails[a] ?: Segment(Conduit.Rail)).joinedTo(dir)
    rails[b] = (rails[b] ?: Segment(Conduit.Rail)).joinedTo(dir.opposite)
}
