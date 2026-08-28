package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Species

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
    /**
     * Marked to be taken apart: it gives itself an output port, hands its metal back to the network
     * and ceases to be once it is holding nothing — see `apps/outofspace/PLAN_self_building_rails.md`.
     *
     * The one thing here that is *not* derivable. Being a **ghost** is a fact about the matter layer
     * — track short of its bill — and needs no flag; wanting to be taken apart is an instruction and
     * has nowhere else to live. It is on the segment despite this class having just been cut back to
     * conduit-and-links, because construction and deconstruction are going to be part of every
     * building's lifecycle and this is where a tile's own intent belongs.
     *
     * A marked segment is still track: it carries traffic until the moment it is gone.
     */
    val deconstructing: Boolean = false,
    /**
     * **What this length is made of** — required, because a length of track that nobody has decided
     * the substance of is not a thing this game can represent.
     *
     * ⛔ **There is no answer to "what is a rail normally made of".** This was `Species?`, where null
     * meant the conduit's default, and the default was a `when` in one place answering for every
     * length of track ever laid. That answer is deleted: a conduit is a *shape* — how it joins, what
     * it carries, how much of a tile it fills — and a shape is not a substance. Stu's call, and it
     * closes off a whole family of bug in which one half of the tick asked a segment what it was and
     * the other half asked its kind.
     *
     * ⚠️ **A ghost is why it has to be stored rather than derived.** A *finished* tile's material is
     * recoverable from the matter in it — see `StuffLayer.dominantAt` — but a ghost holds nothing,
     * and a site that does not yet know what it is asking for cannot ask for it. The bill decides
     * which deliveries a tile admits, so the choice exists before the first gram arrives.
     *
     * It lives here for the reason [deconstructing] does, and the argument is that field's: being a
     * ghost is a fact about the *matter*; wanting to be taken apart is an *instruction* and has
     * nowhere else to live. Choosing a substance is an instruction of exactly that shape.
     */
    val material: Species,
) {
    /** Whether this tile is joined to its neighbour in [dir]. */
    fun linkedTo(dir: Direction): Boolean = links and (1 shl dir.ordinal) != 0

    /** True for track that joins nothing — a stub, laid but not yet drawn into a line. */
    val isIsolated: Boolean get() = links == 0

    fun joinedTo(dir: Direction): Segment = copy(links = links or (1 shl dir.ordinal))

    fun cutFrom(dir: Direction): Segment = copy(links = links and (1 shl dir.ordinal).inv())
}
