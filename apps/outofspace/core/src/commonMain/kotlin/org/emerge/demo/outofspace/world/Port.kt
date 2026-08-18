package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.Directed
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Machine
import org.emerge.demo.outofspace.world.machine.Placed
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Smelter
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.machine.Thruster
import org.emerge.demo.outofspace.world.machine.Vaporizer
import org.emerge.demo.outofspace.world.machine.Vent
import org.emerge.demo.outofspace.world.machine.WireButton

/**
 * Machine connection point: specific tile of footprint, facing specific way.
 * Port is a property of a tile (footprints enable this). Two ports share tile only if different conduits.
 */
data class Port(
    val tile: TileIndex,
    /** Facing direction (not connection direction — conduits run underneath buildings). Determines draw orientation. */
    val side: Direction,
    val kind: PortKind,
    val stream: Stream = Stream.Product,
    /** Which network it belongs to. Two ports may share a tile only if these differ. */
    val conduit: Conduit = Conduit.Rail,
    /** Where the thing owning this port is stored — its centre tile. */
    val owner: TileIndex = TileIndex.NONE,
    /** Whether the owner lives on the bridge list rather than the deck. */
    val fromBridge: Boolean = false,
    /**
     * Whether the owner is a [DeckMachine] — looked up in [VesselState.deck] rather than in
     * [VesselState.machines].
     *
     * Three homes for two flags is one home too many, and it will collapse into a single "where is
     * my owner" enum once the last kind has moved off the machine list. Until then a port has to
     * say which of the two lists to ask, because a tile index alone no longer answers it.
     */
    val fromDeck: Boolean = false,
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
 * Machine connection points in local frame, rotated into world. Unrotated definition (one variant per machine, not per orientation).
 */
private fun localPorts(machine: Placed): List<LocalPort> {
    val r = machine.reach
    return when (machine) {
        // A gantry: it takes the belt on at one end and puts it down at the other. Rail only —
        // there was never a pipe bridge, since a fluid is a continuum and has nothing to hop with.
        // At ±reach like every other machine's ports, which for a 3-long span is its two ends.
        is Bridge -> listOf(
            LocalPort(-r, 0, Direction.Left, PortKind.Input),
            LocalPort(r, 0, Direction.Right, PortKind.Output),
        )


        is Extractor -> listOf(LocalPort(r, 0, Direction.Right, PortKind.Output))

        // Propellant in at the back, which for a one-tile machine is its own tile — a rail is
        // threaded underneath it exactly as it is under a smelter. The exhaust leaves out the front
        // and is not a port: nothing on a belt is going to catch it.
        is Thruster -> listOf(LocalPort(0, 0, Direction.Left, PortKind.Input))

        is Vaporizer -> listOf(LocalPort(r, 0, Direction.Right, PortKind.Input))

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
        is ThermalDecomposer -> listOf(
            LocalPort(-r, 0, Direction.Left, PortKind.Input),
            LocalPort(r, 0, Direction.Right, PortKind.Output),
        )

        // A vent is a hole. It takes whatever is put into it, from whichever face.
        is Vent -> Direction.ALL.map { LocalPort(0, 0, it, PortKind.Input) }

        // In one side, out the other, like everything else. The second input it used to have on top
        // bought nothing: two lines arriving at one tank is a merge, and a merge is something the
        // player should have to build out of track where they can see it, not something a building
        // does for them out of sight.
        is Storage -> listOf(
            LocalPort(-r, 0, Direction.Left, PortKind.Input),
            LocalPort(r, 0, Direction.Right, PortKind.Output),
        )
        is Hull, is Airlock -> emptyList()

        // A pump's traffic is gas: it draws from the room it faces and pushes into the pipe on
        // its own tile, neither of which is a port. Track arriving at one would have nothing to hand
        // over.
        is Sensor, is WireButton, is Pump -> emptyList()
    }
}

/** The ports of the machine stored at [centreTile], in world tiles. Empty if it has none or is clipped. */
fun portsOf(grid: Grid, machine: Placed, centreTile: TileIndex): List<Port> {
    val turns = machine.turns
    val cx = grid.xOf(centreTile)
    val cy = grid.yOf(centreTile)
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
            out.add(
                Port(
                    grid.tile(x, y), side, p.kind, p.stream, p.conduit, centreTile,
                    fromBridge = machine is Bridge,
                    fromDeck = machine is DeckMachine,
                )
            )
        }
    }
    return out
}
/**
 * The ports of a deck machine, which knows where it stands — see the twin above for the rest.
 */
fun portsOf(grid: Grid, machine: DeckMachine): List<Port> = portsOf(grid, machine, machine.center)

/**
 * The port of [conduit] that this machine exposes at [tile], if any.
 *
 * Binding is by **tile alone**, not by an adjacent tile: conduits run underneath buildings, so a
 * segment connects to whatever building shares its own tile. That is what "ports behind the
 * buildings" means, and it is why a run has to be threaded *under* a machine to reach it rather than
 * butted against its edge.
 */
fun portAt(grid: Grid, machine: Machine, centreTile: TileIndex, tile: TileIndex, kind: PortKind, conduit: Conduit): Port? =
    portsOf(grid, machine, centreTile).firstOrNull {
        it.kind == kind && it.tile == tile && it.conduit == conduit
    }
