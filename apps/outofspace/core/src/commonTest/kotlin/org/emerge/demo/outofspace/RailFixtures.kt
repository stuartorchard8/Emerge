package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.Channel
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.Extractor
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.Machine
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.STARTER_DEMO_PLATE_Y
import org.emerge.demo.outofspace.world.STARTER_PLATE_X
import org.emerge.demo.outofspace.world.STARTER_PLATE_Y
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.starterVessel
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

// ── Ore, since there is no longer anywhere it comes from for free ─────────────

/**
 * An extractor at [x],[y] with a body lying on its plate — what "a source of ore" means since H3.
 *
 * The body is exactly the plate's size, so every one of its cells is reachable and the whole thing
 * can be eaten. It is also **finite**, at [FEEDSTOCK_GRAMS] each, which is the difference a test has
 * to live with now: a line left running long enough stops, because the body ran out.
 *
 * [bodies] above one stacks that many in the same place, which is not something the game can hand a
 * player and is the cheapest way for a test to say "more ore than this needs". Nothing objects —
 * bodies do not collide with each other, only with the ship — and the extractor works through them
 * one at a time.
 *
 * Writes the machine into [machines] and returns the bodies, since bodies are not on the deck and
 * cannot be written to the same array.
 */
fun feedExtractor(
    grid: Grid,
    machines: Array<Machine?>,
    x: Int,
    y: Int,
    facing: Direction = Direction.Right,
    wiring: Wiring = Wiring.RUNNING,
    bodies: Int = 1,
): List<RigidBody> {
    machines[grid.index(x, y)] = Extractor(facing).withWiring(wiring)
    return rockOnPlate(x, y, bodies)
}

/** Body lying centred on the plate at [x],[y], for a plate that is already built. */
fun rockOnPlate(x: Int, y: Int, count: Int = 1): List<RigidBody> {
    val half = FEEDSTOCK_RADIUS * Flight.PER_TILE
    return List(count) {
        RigidBody.rockBlob(
            radius = FEEDSTOCK_RADIUS,
            positionX = x * Flight.PER_TILE - half,
            positionY = y * Flight.PER_TILE - half,
            composition = OutofspaceReducer.DEFAULT_ORE_BODY,
        )
    }
}

/** Body radius the plate is sized for: five tiles across, 21 cells. */
const val FEEDSTOCK_RADIUS = 2

/** What one of those weighs — the ore budget of a test that plants a single body. */
val FEEDSTOCK_GRAMS: Long get() = 21L * RigidBody.MATERIAL.gramsPerTile

/**
 * The starter vessel **with feedstock on both plates** — what most of these tests mean when they
 * reach for "a working refinery".
 *
 * `starterVessel` itself ships with bare plates, because an extractor has to be given a body and a
 * starting world that quietly supplied one would be hiding the whole of H3. So a test that wants a
 * line actually running has to say so, which is the right way round: the ore is a precondition now,
 * not a fact of life.
 *
 * Six bodies a plate is about 1500 ticks of digging, comfortably past the longest run here.
 */
fun workingVessel(grid: Grid, rocksPerPlate: Int = 6): VesselState {
    // The plates are already there and already wired — the demonstration one has `ALWAYS − RED` on
    // it and rebuilding it would quietly delete the very thing WiringTest is looking at. Only the
    // bodies are new.
    //
    // No field: these tests count bodies, weigh them and watch them disappear, and a dozen more
    // floating about outside would be in every one of those sums. What is on the plates is the
    // whole ore budget of a world built here.
    val base = starterVessel(grid)
    val bodies = rockOnPlate(STARTER_PLATE_X, STARTER_PLATE_Y, rocksPerPlate) +
        rockOnPlate(STARTER_PLATE_X, STARTER_DEMO_PLATE_Y, rocksPerPlate)
    // ⚠️ Both baselines have to move with them, and `copy` will not do it: they are constructor
    // *defaults*, so a copy keeps the figure computed for the world that had no bodies in it and
    // every ledger then reads the bodies as mass and energy conjured out of nothing. The bodies were
    // always here as far as this world is concerned, which is what a baseline says.
    return base.copy(
        bodies = bodies,
        baselineBodyGrams = base.baselineBodyGrams + bodies.sumOf { it.massGrams },
        baselineJoules = base.baselineJoules + bodies.sumOf { it.joules },
    )
}
