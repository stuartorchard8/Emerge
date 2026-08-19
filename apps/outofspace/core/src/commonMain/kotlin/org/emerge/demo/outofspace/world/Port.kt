package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.world.machine.Airlock
import org.emerge.demo.outofspace.world.machine.Bridge
import org.emerge.demo.outofspace.world.machine.DeckArray
import org.emerge.demo.outofspace.world.machine.DeckMachine
import org.emerge.demo.outofspace.world.machine.DirectedDeckMachine
import org.emerge.demo.outofspace.world.machine.Extractor
import org.emerge.demo.outofspace.world.machine.Gauge
import org.emerge.demo.outofspace.world.machine.Hull
import org.emerge.demo.outofspace.world.machine.Valve
import org.emerge.demo.outofspace.world.machine.Processor
import org.emerge.demo.outofspace.world.machine.Pump
import org.emerge.demo.outofspace.world.machine.Sensor
import org.emerge.demo.outofspace.world.machine.Storage
import org.emerge.demo.outofspace.world.machine.ThermalDecomposer
import org.emerge.demo.outofspace.world.machine.Thruster
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
private fun localPorts(machine: DeckMachine): List<LocalPort> {
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
        // threaded underneath it exactly as it is under an extractor. The exhaust leaves out the front
        // and is not a port: nothing on a belt is going to catch it.
        is Thruster -> listOf(LocalPort(0, 0, Direction.Left, PortKind.Input))


        // In at the back, concentrate out the front, tailings out of the floor.
        is Processor -> listOf(
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
        // No ports at all: a gauge only watches the run under it, and a valve only opens onto the
        // room it stands in. Neither is a place material can be handed to.
        is Sensor, is WireButton, is Pump, is Gauge, is Valve -> emptyList()
    }
}

/** The ports of the machine stored at [centreTile], in world tiles. Empty if it has none or is clipped. */
fun portsOf(grid: Grid, machine: DeckMachine, centreTile: TileIndex): List<Port> {
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
 * The one opening a **ghost** machine offers the network: its construction port.
 *
 * ### Why the centre tile
 *
 * A machine's real ports sit at ±[DeckMachine.reach], which is a different place for every footprint
 * and every facing. The centre is the one tile every machine has, whatever size it is, so a single
 * port there needs no knowledge of how wide the thing is and can never land on a side some kind
 * already uses. That is what makes this one entry rather than a rule per kind.
 *
 * For a **1x1** machine `reach` is 0, so the centre is exactly where its input port would be — and
 * this **overrides** it, because a ghost has no buffer to put anything in. A machine with no ports
 * at all, a hull or an airlock, gains one for the first time in its life, which is the whole reason
 * a hull can be built at all.
 *
 * Always on [Conduit.Rail], whatever the machine is for: casing is solid, so a pump and a vent are
 * both built by track and the plumbing they exist to serve has nothing to do with it.
 *
 * It is a port like any other, so the track beneath it is locked from deconstruction while the ghost
 * stands — a player cannot pull up the line they are feeding the thing with.
 */
/**
 * The tile a ghost is **fed at**, and the tile a machine being taken apart hands its casing back at.
 *
 * Its centre for everything except a **bridge**, which has no centre port at either end of its life:
 * a gantry is a thing with two ends, and a port in the middle of a span is not a place a belt can
 * reach — the tile is over the gap it is bridging. So a bridge is built through the end it takes
 * material in at, and gives its metal back through the end it puts material down at, which is what
 * every other length of track running through it already does.
 */
fun constructionTileOf(grid: Grid, machine: DeckMachine): TileIndex =
    if (machine is Bridge) {
        portsOf(grid, machine).firstOrNull { it.kind == PortKind.Input }?.tile ?: machine.center
    } else {
        machine.center
    }

fun constructionPortOf(grid: Grid, machine: DeckMachine): Port =
    if (machine is Bridge) {
        // Its own input, unchanged. A bridge needs no port invented for it.
        portsOf(grid, machine).first { it.kind == PortKind.Input }
    } else {
        constructionPortAtCentre(machine)
    }

private fun constructionPortAtCentre(machine: DeckMachine): Port =
    Port(
        machine.center,
        // Facing only orients the drawing. A ghost is fed from whichever way material arrives, the
        // same as a length of unbuilt track is.
        (machine as? DirectedDeckMachine)?.facing ?: Direction.Right,
        PortKind.Input,
        conduit = Conduit.Rail,
        owner = machine.center,
        fromDeck = true,
    )

/**
 * The ports a machine **actually has right now**, which is not always the ports its kind defines.
 *
 * The single answer to that question, because there are two callers — the reducer, which routes
 * material by it, and [org.emerge.demo.outofspace.world.VesselState.portsByTile], which draws it —
 * and a picture that disagrees with the routing is worse than no picture.
 *
 * Three states on top of the ordinary one:
 *
 *  - **Marked for deconstruction.** It keeps its outputs — its product is still its product and
 *    leaves the way it always did — and loses its inputs, because nothing new is fed to a thing
 *    being dismantled. Handing back what is *already* inside it is the deconstruction pass's job
 *    rather than a port's.
 *  - **A marked bridge that still holds something** keeps everything. A gantry is a length of track
 *    held in the air, and a run does not stop carrying because the player condemned it; closing its
 *    mouth mid-span would strand whatever was on it.
 *  - **A ghost** has one opening, its construction port — see [constructionPortOf].
 *
 * ⚠️ **Being told to go overrides being short, and that order is load-bearing.** A machine handing
 * its casing back is short of its bill from the first load it gives up, so asked the other way round
 * it grows a *construction* port, turns into a sink and draws its own metal straight back off the
 * belt. Stable, stationary, and indistinguishable from deconstruction doing nothing at all. The
 * rails learned this first; the deck learned it again.
 */
fun standingPortsOf(
    grid: Grid,
    deck: DeckArray,
    buffers: BufferLayer,
    scrapping: Set<TileIndex>,
    m: DeckMachine,
): List<Port> {
    if (m.center in scrapping) {
        if (m is Bridge && bufferRolesOf(m).any { role ->
                bufferTile(grid, m, m.center, role)?.let { buffers.massAt(it) > 0L } == true
            }
        ) return portsOf(grid, m)
        return portsOf(grid, m).filter { it.kind == PortKind.Output }
    }
    if (deck.isGhost(m.center)) return listOf(constructionPortOf(grid, m))
    return portsOf(grid, m)
}

/**
 * The ports of a deck machine, which knows where it stands — see the twin above for the rest.
 */
fun portsOf(grid: Grid, machine: DeckMachine): List<Port> = portsOf(grid, machine, machine.center)

