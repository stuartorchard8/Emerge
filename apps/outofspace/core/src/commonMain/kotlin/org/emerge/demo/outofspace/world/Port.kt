package org.emerge.demo.outofspace.world

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
 * sends concentrate out the front and drops tailings out of the floor, and that sentence is true
 * whichever way the machine is turned. The alternative — four handwritten variants per machine — is
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
