package org.emerge.demo.outofspace

import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.OutofspaceReducer.RAIL_PERIOD
import org.emerge.demo.outofspace.world.Conduit
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.world.Direction
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.Flight
import org.emerge.demo.outofspace.world.Grid
import org.emerge.demo.outofspace.world.massPerTileOf
import org.emerge.demo.outofspace.world.RigidBody
import org.emerge.demo.outofspace.world.STARTER_DEMO_PLATE_Y
import org.emerge.demo.outofspace.world.STARTER_PLATE_X
import org.emerge.demo.outofspace.world.STARTER_PLATE_Y
import org.emerge.demo.outofspace.world.VesselState
import org.emerge.demo.outofspace.world.Wiring
import org.emerge.demo.outofspace.world.starterVessel
import org.emerge.demo.outofspace.world.Segment
import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.inputBufferRole
import org.emerge.demo.outofspace.world.TileIndex

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
    fun lay(x: Int, y: Int, gauge: Boolean = false): RailPlan = apply {
        if (!grid.inBounds(x, y)) return@apply
        val tile = grid.tile(x, y)
        val existing = rails[tile.index]
        rails[tile.index] = existing?.copy(isGauge = gauge || existing.isGauge)
            ?: Segment(Conduit.Rail, isGauge = gauge)
    }

    /** Joins two adjacent tiles, both halves, exactly as [Edit.Lay] does. */
    fun join(x: Int, y: Int, dir: Direction): RailPlan = apply {
        val a = grid.tile(x, y)
        val b = grid.neighbour(a, dir)
        if (b == TileIndex.NONE) return@apply
        rails[a.index] = (rails[a.index] ?: Segment(Conduit.Rail)).joinedTo(dir)
        rails[b.index] = (rails[b.index] ?: Segment(Conduit.Rail)).joinedTo(dir.opposite)
    }

    /** A horizontal run on row [y], inclusive, with optional gauges at given x positions. */
    fun row(fromX: Int, toX: Int, y: Int, gaugeAt: Set<Int> = emptySet()): RailPlan = apply {
        val lo = minOf(fromX, toX)
        val hi = maxOf(fromX, toX)
        for (x in lo..hi) lay(x, y, x in gaugeAt)
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

fun joinRow(grid: Grid, rails: Array<Segment?>, fromX: Int, toX: Int, y: Int, gaugeAt: Set<Int> = emptySet()) {
    val lo = minOf(fromX, toX)
    val hi = maxOf(fromX, toX)
    for (x in lo..hi) layInto(grid, rails, x, y, x in gaugeAt)
    for (x in lo until hi) linkPair(grid, rails, grid.tile(x, y), Direction.Right)
}

fun joinCol(grid: Grid, rails: Array<Segment?>, x: Int, fromY: Int, toY: Int) {
    val lo = minOf(fromY, toY)
    val hi = maxOf(fromY, toY)
    for (y in lo..hi) layInto(grid, rails, x, y, false)
    for (y in lo until hi) linkPair(grid, rails, grid.tile(x, y), Direction.Down)
}

/** Lays track, preserving the joins of anything already at that tile. See [RailPlan.lay]. */
private fun layInto(grid: Grid, rails: Array<Segment?>, x: Int, y: Int, gauge: Boolean) {
    if (!grid.inBounds(x, y)) return
    val tile = grid.tile(x, y)
    val existing = rails[tile.index]
    rails[tile.index] = existing?.copy(isGauge = gauge || existing.isGauge)
        ?: Segment(Conduit.Rail, isGauge = gauge)
}

private fun linkPair(grid: Grid, rails: Array<Segment?>, a: TileIndex, dir: Direction) {
    val b = grid.neighbour(a, dir)
    if (b == TileIndex.NONE) return
    rails[a.index] = (rails[a.index] ?: Segment(Conduit.Rail)).joinedTo(dir)
    rails[b.index] = (rails[b.index] ?: Segment(Conduit.Rail)).joinedTo(dir.opposite)
}

// ── Ore, since there is no longer anywhere it comes from for free ─────────────

/**
 * An extractor at [x],[y] with a body lying on its plate — what "a source of ore" means since H3.
 *
 * The body is exactly the plate's size, so every one of its cells is reachable and the whole thing
 * can be eaten. It is also **finite**, at [FEEDSTOCK_MASS] each, which is the difference a test has
 * to live with now: a line left running long enough stops, because the body ran out.
 *
 * [bodies] above one stacks that many in the same place, which is not something the game can hand a
 * player and is the cheapest way for a test to say "more ore than this needs". Nothing objects —
 * bodies do not collide with each other, only with the ship — and the extractor works through them
 * one at a time.
 *
 * Writes the machine into [deck] and returns the bodies, since bodies are not deck machines and
 * cannot be written to the same array.
 */
fun feedExtractor(
    grid: Grid,
    deck: DeckArray,
    x: Int,
    y: Int,
    facing: Direction = Direction.Right,
    wiring: Wiring = Wiring.RUNNING,
    bodies: Int = 1,
): List<RigidBody> {
    deck += Extractor(grid.tile(x, y), facing).withWiring(wiring)
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

/**
 * What one of those weighs — the ore budget of a test that plants a single body.
 *
 * Derived from the composition the fixture actually spawns, not from a material constant: a rock's
 * mass is its ore now, so pinning this to anything else would let the two drift apart silently.
 */
val FEEDSTOCK_MASS: Long get() = 21L * massPerTileOf(OutofspaceReducer.DEFAULT_ORE_BODY)

/**
 * How many ticks it takes to shift [mass] along a belt, plus a little slack.
 *
 * ⚠️ **Use this instead of a hard-coded tick count** in any test that waits for material to arrive.
 * A belt tile holds one packet and a machine hands over one per tick, so the whole logistics layer
 * runs at exactly `Capacity.PACKET_MASS` per tick — and that is a **tuning dial**. When it went
 * from a tonne to 100 kg, every test with a literal budget in it started failing for a reason that
 * had nothing to do with what it was testing, and a reader looking at `run(s, 240)` has no way to
 * tell whether 240 is a deadline, a measurement or a guess.
 *
 * The slack is a quarter on top, because a line has to fill before it can deliver and the first few
 * packets are in transit rather than arriving.
 */
fun ticksToMove(mass: Long): Int = ((mass / Capacity.PACKET_MASS) * 5L / 4L).toInt()*RAIL_PERIOD + 20

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
    // Bodies are not part of the energy ledger — their thermal energy enters only via extractor
    // bites (recorded in [acquiredEnergy]). So the baseline stays as-is.
    return base.copy(
        bodies = bodies,
    )
}

/**
 * A world with [resource] already sitting in the [role] store of the machine at [tile].
 *
 * No machine carries its contents any more, so a fixture that wants a loaded one states the machine
 * and the matter separately: the machine goes in the list, and this puts the matter in the layer
 * once the state exists. Chained after construction rather than passed in, because
 * [org.emerge.demo.outofspace.world.VesselState] derives its stores from the machine list, so the
 * store is already standing by the time this is called.
 *
 * [role] defaults to the store an arriving packet would land in, since that is what almost every
 * fixture means by "a machine with something in it".
 */
fun VesselState.stocked(tile: TileIndex, resource: Resource?, role: BufferRole? = null): VesselState = also {
    val m = deck[tile] ?: error("no machine at $tile to stock")
    val use = role ?: inputBufferRole(m) ?: error("$m takes no deliveries; name a role")
    it.buffers.put(bufferTile(grid, m, tile, use) ?: error("$m keeps no $use store"), resource)
}

/** What is riding on the track at [tile]. */
fun VesselState.onRail(tile: TileIndex): Resource? = rail.resourceAt(tile)

/** A world with [resource] already riding on the track at [tile]. */
fun VesselState.riding(tile: TileIndex, resource: Resource?): VesselState = also { it.rail.put(tile, resource) }

/** What the machine at [tile] is holding in its [role] store. */
fun VesselState.inStore(tile: TileIndex, role: BufferRole): Resource? {
    val m = deck[tile] ?: return null
    return buffers.resourceAt(bufferTile(grid, m, tile, role) ?: return null)
}
