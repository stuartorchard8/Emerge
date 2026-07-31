package org.emerge.demo.outofspace.world

/**
 * How many tiles across a machine is. Always **odd**, always square.
 *
 * Odd because a machine is stored at its **centre** tile, and a centre only exists for odd sizes.
 * That one choice removes most of the awkwardness from rotation: turning a building leaves its
 * anchor exactly where it was, so a rotate is a change of facing and nothing else. With a top-left
 * anchor, every rotation would also have to move the machine, and "rotate" would silently become
 * "rotate and translate" — which is fiddly to place and worse to undo.
 *
 * The sizes say what a thing *is*. A conveyor is a fitting, so it is one tile. A processor or a tank
 * is a room-sized installation at three. A smelter is a furnace at five: it should dominate the deck
 * it sits on, and its heat should have somewhere to be.
 */
val MachineKind.size: Int
    get() = when (this) {
        // Fittings and the small deck pieces. A bridge is three tiles long but occupies none of
        // them, so its size says nothing about space -- only its two ports place it.
        MachineKind.Rail, MachineKind.Gauge, MachineKind.Bridge -> 1
        MachineKind.Sensor, MachineKind.Vent, MachineKind.Hull -> 1
        MachineKind.Miner -> 3
        MachineKind.Processor, MachineKind.Fabricator, MachineKind.Storage -> 3
        MachineKind.Smelter -> 5
    }

/** Half-width: how far the footprint reaches from its centre in each direction. */
val MachineKind.reach: Int get() = size / 2

/**
 * Which tiles belong to which machine.
 *
 * `machines` holds each machine once, at its centre — storing a copy on every covered tile would
 * mean nine or twenty-five things that have to agree about one furnace's contents, and they would
 * eventually not. This is the index that makes the single copy usable: tile → the index its machine
 * is stored at, or `-1` for open deck.
 *
 * Derived every tick alongside [StructureMap], and for the same reason: a cache with an invalidation
 * rule is a bug waiting for an edit case nobody thought of.
 */
class Occupancy(private val originOf: IntArray) {

    /** The index the machine covering this tile is stored at, or -1 if the tile is free. */
    operator fun get(tile: Int): Int = if (tile in originOf.indices) originOf[tile] else -1

    fun isFree(tile: Int): Boolean = get(tile) < 0

    /** True when this tile is where its machine actually lives, rather than a tile it merely covers. */
    fun isOrigin(tile: Int): Boolean = get(tile) == tile

    override fun equals(other: Any?): Boolean =
        this === other || (other is Occupancy && originOf.contentEquals(other.originOf))

    override fun hashCode(): Int = originOf.contentHashCode()

    companion object {
        fun derive(grid: Grid, machines: List<Machine?>): Occupancy {
            val originOf = IntArray(grid.size) { -1 }
            for (i in machines.indices) {
                val m = machines[i] ?: continue
                for (tile in coveredTiles(grid, i, m.kind.size)) originOf[tile] = i
            }
            return Occupancy(originOf)
        }
    }
}

/**
 * Every tile a machine of [size] centred on [centre] covers, clipped to the grid.
 *
 * Row-major order, which is arbitrary but fixed — the only property anything downstream relies on.
 */
fun coveredTiles(grid: Grid, centre: Int, size: Int): List<Int> {
    if (size <= 1) return listOf(centre)
    val reach = size / 2
    val cx = grid.xOf(centre)
    val cy = grid.yOf(centre)
    val out = ArrayList<Int>(size * size)
    for (dy in -reach..reach) {
        for (dx in -reach..reach) {
            val x = cx + dx
            val y = cy + dy
            if (grid.inBounds(x, y)) out.add(grid.index(x, y))
        }
    }
    return out
}

/** True when a machine of [size] centred here would fit entirely on the grid. */
fun footprintFits(grid: Grid, centre: Int, size: Int): Boolean {
    val reach = size / 2
    val cx = grid.xOf(centre)
    val cy = grid.yOf(centre)
    return grid.inBounds(cx - reach, cy - reach) && grid.inBounds(cx + reach, cy + reach)
}

// ── Ports ─────────────────────────────────────────────────────────────────────

/** Whether material enters the machine here or leaves it here. */
enum class PortKind { Input, Output }

/**
 * Which of a machine's output buffers a port drains.
 *
 * Machines that separate a product from a waste stream need to say which port is which, and the old
 * "product leaves by `facing`, waste by `facing.clockwise`" rule could only express that because
 * every machine was one tile. On a five-tile furnace the two streams leave from genuinely different
 * places, so the stream has to be named rather than inferred from an angle.
 */
enum class Stream { Product, Waste }

/**
 * One connection point on a machine: a specific tile of its footprint, facing a specific way.
 *
 * **This is the whole reason footprints came first.** A port is a property of a *tile*, so a
 * one-tile machine can only ever have ports that overlap each other, and "where does this connect"
 * collapses back into "which way is it pointing". Give a building nine tiles and its input and its
 * two output streams are three different places you have to route to — which is the mechanic, not a
 * detail of it.
 *
 * Connectivity being a property of ports rather than of tiles is also what makes a crossing free
 * later: two runs that share a tile but not a port are simply not connected, and nothing has to know
 * about the special case.
 */
data class Port(
    val tile: Int,
    /**
     * Which way the port faces.
     *
     * **Not** how it connects — a port binds to whatever segment shares its own tile, because
     * conduits run *underneath* buildings rather than butting up against them. This is which face of
     * the building the fitting is on, which is what gets drawn and what orients a bridge.
     */
    val side: Direction,
    val kind: PortKind,
    val stream: Stream = Stream.Product,
    /** Which network it belongs to. Two ports may share a tile only if these differ. */
    val conduit: Conduit = Conduit.Rail,
    /** Where the thing owning this port is stored — its centre tile. */
    val owner: Int = -1,
    /** Whether the owner lives on the bridge list rather than the deck. */
    val fromBridge: Boolean = false,
)

/**
 * A port before it is attached to a place: an offset from the machine's centre, in the machine's own
 * frame, where facing-Right is canonical.
 */
private data class LocalPort(
    val dx: Int,
    val dy: Int,
    val side: Direction,
    val kind: PortKind,
    val stream: Stream = Stream.Product,
    val conduit: Conduit = Conduit.Rail,
)

/**
 * Where each kind of machine connects, described once in its own frame and rotated into the world.
 *
 * Describing them unrotated is what keeps this readable: a processor takes material in at the back,
 * sends concentrate out the front and drops tailings out of the bottom, and that sentence is true
 * whichever way the machine is turned. The alternative — four hand-written variants per machine — is
 * four chances to get one of them wrong, and a bug that only shows up in one orientation.
 */
private fun localPorts(machine: Machine): List<LocalPort> {
    val r = machine.kind.reach
    return when (machine) {
        // A bridge is three tiles long and connects at its own two ends, one either side of the tile
        // it hops.
        //
        // These sat two tiles further out for a while, flanking the span, because segments used to
        // join to each other by mere adjacency: track at a bridge's end sat *next to* the track it
        // was meant to be hopping over, and the two runs merged regardless of ports. Explicit links
        // removed the reason — a line crossing underneath is now unconnected because nobody drew a
        // join, which is the ordinary rule and not a clearance the bridge has to buy. So the ports
        // came home, and a bridge is the three tiles it looks like.
        is Bridge -> listOf(
            LocalPort(-1, 0, Direction.Left, PortKind.Input, conduit = machine.conduit),
            LocalPort(1, 0, Direction.Right, PortKind.Output, conduit = machine.conduit),
        )

        // A vent is a hole. It takes whatever is put into it, from whichever face.
        is Vent -> Direction.ALL.map { LocalPort(0, 0, it, PortKind.Input) }

        is Miner -> listOf(LocalPort(r, 0, Direction.Right, PortKind.Output))

        // In at the back, concentrate out the front, tailings out of the floor.
        is Processor -> listOf(
            LocalPort(-r, 0, Direction.Left, PortKind.Input),
            LocalPort(r, 0, Direction.Right, PortKind.Output, Stream.Product),
            LocalPort(0, r, Direction.Down, PortKind.Output, Stream.Waste),
        )
        is Smelter -> listOf(
            LocalPort(-r, 0, Direction.Left, PortKind.Input),
            LocalPort(r, 0, Direction.Right, PortKind.Output, Stream.Product),
            LocalPort(0, r, Direction.Down, PortKind.Output, Stream.Waste),
        )

        // In one side, out the other, like everything else. The second input it used to have on top
        // bought nothing: two lines arriving at one tank is a merge, and a merge is something the
        // player should have to build out of track where they can see it, not something a building
        // does for them out of sight.
        is Storage -> listOf(
            LocalPort(-r, 0, Direction.Left, PortKind.Input),
            LocalPort(r, 0, Direction.Right, PortKind.Output),
        )

        is Fabricator -> listOf(
            LocalPort(-r, 0, Direction.Left, PortKind.Input),
            LocalPort(0, -r, Direction.Up, PortKind.Input),
            LocalPort(r, 0, Direction.Right, PortKind.Output),
        )

        is Sensor, is Hull -> emptyList()
    }
}

/** The ports of the machine stored at [centre], in world tiles. Empty if it has none or is clipped. */
fun portsOf(grid: Grid, machine: Machine, centre: Int): List<Port> {
    val turns = (machine as? Directed)?.facing?.ordinal ?: 0
    val cx = grid.xOf(centre)
    val cy = grid.yOf(centre)
    val out = ArrayList<Port>(4)
    for (p in localPorts(machine)) {
        var dx = p.dx
        var dy = p.dy
        var side = p.side
        // Direction's declaration order is clockwise, so facing.ordinal is exactly how many quarter
        // turns to apply. (dx, dy) -> (-dy, dx) is that turn with +y pointing down the screen.
        repeat(turns) {
            val nx = -dy
            dy = dx
            dx = nx
            side = side.clockwise
        }
        val x = cx + dx
        val y = cy + dy
        if (grid.inBounds(x, y)) {
            out.add(Port(grid.index(x, y), side, p.kind, p.stream, p.conduit, centre, machine is Bridge))
        }
    }
    return out
}

/**
 * The port that would receive material arriving at [tile] travelling in [heading], or null.
 *
 * A port on the far side of a wall it does not face is not a connection: material entering a tile
 * heading right has to meet a port on that tile facing left. Checking the facing rather than just
 * the tile is what stops a three-by-three building behaving like a nine-tile sponge.
 */
/**
 * The port of [conduit] that this machine exposes at [tile], if any.
 *
 * Binding is by **tile alone**, not by an adjacent tile: conduits run underneath buildings, so a
 * segment connects to whatever building shares its own tile. That is what "ports behind the
 * buildings" means, and it is why a run has to be threaded *under* a machine to reach it rather than
 * butted against its edge.
 */
fun portAt(grid: Grid, machine: Machine, centre: Int, tile: Int, kind: PortKind, conduit: Conduit): Port? =
    portsOf(grid, machine, centre).firstOrNull {
        it.kind == kind && it.tile == tile && it.conduit == conduit
    }
