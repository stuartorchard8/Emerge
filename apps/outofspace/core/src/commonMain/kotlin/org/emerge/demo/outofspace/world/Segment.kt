package org.emerge.demo.outofspace.world

/** Conduit layer: rail, pipe, power, signal. Four separate networks sharing one tile grid. */
enum class Conduit(val label: String) {
    Rail("RAIL"),
    Pipe("PIPE"),
    Power("POWER"),
    Signal("SIGNAL"),
}

/**
 * One tile of one conduit layer: which network it belongs to, and which neighbours it is joined to.
 *
 * **Nothing else.** No inherent direction — sources and sinks decide flow via `FlowGraph` — and no
 * wiring or on/off state. It used to carry a gauge's readings and a valve's flag too, from before
 * every fitting could be a machine standing over its run; both are [org.emerge.demo.outofspace.world.machine.DeckMachine]s now, and a conduit
 * layer holds nothing but conduit.
 */
data class Segment(
    val conduit: Conduit,
    /** Neighbour join bits (one per Direction). Explicit links ≠ adjacency — two tiles must be explicitly joined. Symmetric only. */
    val links: Int = 0,
) {
    /** Whether this tile is joined to its neighbour in [dir]. */
    fun linkedTo(dir: Direction): Boolean = links and (1 shl dir.ordinal) != 0

    /** True for track that joins nothing — a stub, laid but not yet drawn into a line. */
    val isIsolated: Boolean get() = links == 0

    fun joinedTo(dir: Direction): Segment = copy(links = links or (1 shl dir.ordinal))

    fun cutFrom(dir: Direction): Segment = copy(links = links and (1 shl dir.ordinal).inv())
}
