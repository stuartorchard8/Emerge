package org.emerge.demo.outofspace
import org.emerge.demo.outofspace.chem.Fluid
import org.emerge.demo.outofspace.world.MassArray
import org.emerge.demo.outofspace.world.EnergyArray
import org.emerge.demo.outofspace.world.Stuff
import org.emerge.demo.outofspace.world.energyAtKelvin
import org.emerge.demo.outofspace.world.thermalMassAt

import org.emerge.demo.outofspace.chem.Mixture
import org.emerge.demo.outofspace.world.Temperature
import org.emerge.demo.outofspace.world.heatCapacityOf
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
import org.emerge.demo.outofspace.world.BufferRole
import org.emerge.demo.outofspace.world.bufferTile
import org.emerge.demo.outofspace.world.inputBufferRole
import org.emerge.demo.outofspace.world.TileIndex
import org.emerge.demo.outofspace.world.materialBefore

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
    fun lay(x: Int, y: Int): RailPlan = apply {
        if (!grid.inBounds(x, y)) return@apply
        val tile = grid.tile(x, y)
        rails[tile.index] = rails[tile.index] ?: Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))
    }

    /** Joins two adjacent tiles, both halves, exactly as [Edit.Lay] does. */
    fun join(x: Int, y: Int, dir: Direction): RailPlan = apply {
        val a = grid.tile(x, y)
        val b = grid.neighbour(a, dir)
        if (b == TileIndex.NONE) return@apply
        rails[a.index] = (rails[a.index] ?: Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))).joinedTo(dir)
        rails[b.index] = (rails[b.index] ?: Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))).joinedTo(dir.opposite)
    }

    /** A horizontal run on row [y], inclusive. */
    fun row(fromX: Int, toX: Int, y: Int): RailPlan = apply {
        val lo = minOf(fromX, toX)
        val hi = maxOf(fromX, toX)
        for (x in lo..hi) lay(x, y)
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

fun joinRow(grid: Grid, rails: Array<Segment?>, fromX: Int, toX: Int, y: Int) {
    val lo = minOf(fromX, toX)
    val hi = maxOf(fromX, toX)
    for (x in lo..hi) layInto(grid, rails, x, y)
    for (x in lo until hi) linkPair(grid, rails, grid.tile(x, y), Direction.Right)
}

fun joinCol(grid: Grid, rails: Array<Segment?>, x: Int, fromY: Int, toY: Int) {
    val lo = minOf(fromY, toY)
    val hi = maxOf(fromY, toY)
    for (y in lo..hi) layInto(grid, rails, x, y)
    for (y in lo until hi) linkPair(grid, rails, grid.tile(x, y), Direction.Down)
}

/** Lays track, preserving the joins of anything already at that tile. See [RailPlan.lay]. */
private fun layInto(grid: Grid, rails: Array<Segment?>, x: Int, y: Int) {
    if (!grid.inBounds(x, y)) return
    val tile = grid.tile(x, y)
    rails[tile.index] = rails[tile.index] ?: Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))
}

private fun linkPair(grid: Grid, rails: Array<Segment?>, a: TileIndex, dir: Direction) {
    val b = grid.neighbour(a, dir)
    if (b == TileIndex.NONE) return
    rails[a.index] = (rails[a.index] ?: Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))).joinedTo(dir)
    rails[b.index] = (rails[b.index] ?: Segment(Conduit.Rail, material = materialBefore(Conduit.Rail))).joinedTo(dir.opposite)
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

/**
 * Body lying centred on the plate at [x],[y], for a plate that is already built.
 *
 * ⚠️ It used to subtract the radius to get here, because a body was placed by the corner of its cell
 * box and "centred" had to be arranged. A body is placed by its centre of mass now, so centring it
 * is saying where it goes — see `PLAN_com_anchored_frames.md`.
 */
fun rockOnPlate(x: Int, y: Int, count: Int = 1): List<RigidBody> = List(count) {
    RigidBody.rockBlob(
        radius = FEEDSTOCK_RADIUS,
        // ⚠️ The tile's *centre*, not its corner. A tile spans `[x, x+1)`, so a body centred on it
        // has its centre of mass half a tile in — which is what the old `- radius` arrived at from
        // the other direction, the cell box of an odd-diameter blob being centred on its middle row.
        positionX = x * Flight.PER_TILE + Flight.PER_TILE / 2L,
        positionY = y * Flight.PER_TILE + Flight.PER_TILE / 2L,
        composition = OutofspaceReducer.DEFAULT_ORE_BODY,
    )
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
    ).gridAtWorldOrigin()
}

/**
 * The vessel placed so that its grid and the world coincide — tile `(3, 4)` is at world `(3, 4)`.
 *
 * ⚠️ **Fixtures that put a body at a tile need this, and used to get it for nothing.** A vessel is
 * anchored on its centre of mass now, so the default `positionX = 0` means *the centre* is at the
 * world origin and the grid hangs some seventeen tiles up and to the left of it. Every fixture that
 * says "a rock on the plate at (x, y)" and then hands the body raw tile coordinates is quietly
 * mixing two frames; before the anchor flipped the two happened to be the same one.
 *
 * Solving `world = C + R·(local − comLocal)` for `world == local` at zero rotation gives
 * `C == comLocal`, which is the whole of this.
 */
fun VesselState.gridAtWorldOrigin(): VesselState =
    copy(positionX = distribution.comX, positionY = distribution.comY)

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
fun VesselState.stocked(tile: TileIndex, resource: Mixture?, role: BufferRole? = null): VesselState = also {
    val m = deck[tile] ?: error("no machine at $tile to stock")
    val use = role ?: inputBufferRole(m) ?: error("$m takes no deliveries; name a role")
    it.buffers.put(bufferTile(grid, m, tile, use) ?: error("$m keeps no $use store"), resource)
}

/**
 * [this] at room temperature.
 *
 * ⛔ **A fixture that states matter has to state its heat as well.** `Mixture.of(…, energy = 0)` is
 * not "no opinion about temperature", it is *absolute zero* — so a machine stocked with two tonnes
 * of ore that way holds two tonnes of 0 K iron, and its tile reads colder than the room it stands
 * in whatever its element is doing.
 *
 * It hid a real stall for a while. A concentrator takes a fixed charge off its input now rather than
 * swallowing the whole buffer, so the ballast is no longer immediately consumed by the one bite that
 * used to carry a huge slug of working heat with it — and `HeatTest`'s mill read 291 K and falling.
 * That looked like the machine having stopped, and the machine *had* stopped, but the cold was the
 * fixture's and not the sim's, and the two had to be told apart before either could be fixed.
 */
fun Mixture.atAmbient(): Mixture =
    Mixture.of(masses, heatCapacityOf(this) * Temperature.AMBIENT_KELVIN)

/** What is riding on the track at [tile]. */
fun VesselState.onRail(tile: TileIndex): Mixture? = rail.resourceAt(tile)

/** A world with [resource] already riding on the track at [tile]. */
fun VesselState.riding(tile: TileIndex, resource: Mixture?): VesselState = also { it.rail.put(tile, resource) }

/** What the machine at [tile] is holding in its [role] store. */
fun VesselState.inStore(tile: TileIndex, role: BufferRole): Mixture? {
    val m = deck[tile] ?: return null
    return buffers.resourceAt(bufferTile(grid, m, tile, role) ?: return null)
}

/**
 * A pipe layer holding [propellant] at [tiles] and nothing anywhere else — **a motor's chamber,
 * fuelled**.
 *
 * ⚠️ **Passed to the constructor, never assigned afterwards.** `baselineAirMass` defaults to
 * `air.totalMass + pipeAir.totalMass` and `copy()` does not recompute a defaulted field, so a world
 * charged after it was built starts life with its air ledger already out by whatever was poured in.
 *
 * ⛔ **No pipe segments are laid, and none are needed.** A motor reads the gas in the cell under it;
 * gas cannot get into the pipe layer anywhere a player has not plumbed, so the segment adds nothing
 * a fixture can observe. What it *would* add is the two reduces per tile it takes to lay a run.
 */
fun fuelledPipes(
    grid: Grid,
    propellant: Mixture,
    tiles: List<TileIndex>,
    kelvin: Int = Temperature.AMBIENT_KELVIN,
): Stuff {
    val mass = MassArray(grid.size)
    for (tile in tiles) for (f in Fluid.ALL) {
        val held = propellant[f.species]
        if (held > 0L) mass[tile, f] = held
    }
    val energy = EnergyArray(grid.size)
    for (tile in tiles) energy[tile] = energyAtKelvin(thermalMassAt(mass, tile), kelvin)
    return Stuff.from(mass, energy)
}

/** What is in the pipe cell at [tile] — a motor's chamber, which is the only tank it has. */
fun VesselState.chamberMass(tile: TileIndex): Long {
    var sum = 0L
    for (f in Fluid.ALL) sum += pipeAir.massOf(tile, f)
    return sum
}
