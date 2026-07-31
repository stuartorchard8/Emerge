package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.MergeResult
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.logistics.mergeInto

/**
 * Which transport network something belongs to.
 *
 * Four separate networks sharing one tile grid: a rail, a pipe, a power line and a signal line can
 * all cross the same tile without meeting, because they are different layers. That is the thing ONI
 * gets right and the reason it can build dense factories that are still readable — routing is a
 * puzzle about *each* network rather than one fight for floor space.
 *
 * Structure, heat and atmosphere read none of these. They only ever look at the deck.
 */
enum class Conduit(val label: String) {
    Rail("RAIL"),
    Pipe("PIPE"),
    Power("POWER"),
    Signal("SIGNAL"),
}

/**
 * A length of conveyor or pipe: one tile of one conduit layer.
 *
 * **A segment has no direction of its own.** That is the whole difference between this and the belt
 * it replaces. Which way material moves along a run is decided by where the sources and sinks on it
 * are — see [FlowField] — so laying track is laying *topology*, and reversing a line is a matter of
 * moving the machine that feeds it rather than rotating fifty tiles.
 *
 * A segment is also **inert**: it has no wiring and cannot be switched off. It is plumbing. The
 * things that make decisions are the buildings at the ends of it.
 */
data class Segment(
    val conduit: Conduit,
    /**
     * Which neighbours this tile is **joined** to: one bit per [Direction], by ordinal.
     *
     * Two segments sitting next to each other are not connected. They are connected when the player
     * drew a line through both, and only then. That is the difference between track being a *shape*
     * and track being a *graph*, and it decides how the whole game reads:
     *
     *  - Two lines can run side by side, touching, without merging. Without this, every parallel run
     *    needs a tile of clearance and a factory sprawls for no reason anyone can see.
     *  - A bridge can genuinely cross, because the line passing underneath is unlinked to it in the
     *    ordinary way rather than being kept at arm's length. This is what let a bridge's ports move
     *    back to its own two ends, where they belong.
     *  - Adding a tile of track cannot silently rewire a distant part of the factory, which
     *    adjacency-joining did every time a new run brushed an old one.
     *
     * Always symmetric: [linkedTo] is only ever set through [joinedTo], which sets both halves. A
     * one-sided link would be a valve nobody asked for.
     */
    val links: Int = 0,
    /** What is riding on this tile. Partial packets are normal — see [org.emerge.demo.outofspace.logistics.mergeInto]. */
    val held: Packet? = null,
    /**
     * If set, this length of track is a **gauge**: it reports what passes through it on this channel.
     *
     * A gauge is a property of a segment rather than a machine of its own, which is what it always
     * wanted to be — the old analyzer was described in its own documentation as "a belt tile that
     * measures", and making it a building meant it needed ports, which meant it broke a run in two
     * for no reason. As a segment it is simply track that reads.
     *
     * It measures without taking: material passes at full speed, and the reading **persists** after
     * it has gone, so an idle line still says what last went down it.
     */
    val channel: Channel? = null,
    val lastForm: Form? = null,
    val lastDominant: Species? = null,
    /** The dominant species' share of the last thing through, in permille. */
    val lastPurity: Int = 0,
    val lastMass: Long = 0L,
) {
    val isGauge: Boolean get() = channel != null

    /** Whether this tile is joined to its neighbour in [dir]. */
    fun linkedTo(dir: Direction): Boolean = links and (1 shl dir.ordinal) != 0

    /** True for track that joins nothing — a stub, laid but not yet drawn into a line. */
    val isIsolated: Boolean get() = links == 0

    fun joinedTo(dir: Direction): Segment = copy(links = links or (1 shl dir.ordinal))

    fun cutFrom(dir: Direction): Segment = copy(links = links and (1 shl dir.ordinal).inv())

    /** This segment having seen [packet] go past. Reads it; does not consume it. */
    fun reading(packet: Packet): Segment {
        if (channel == null) return this
        val dominant = packet.contents.dominant ?: return this
        val mass = packet.mass
        return copy(
            lastForm = (packet as? SolidPacket)?.form,
            lastDominant = dominant,
            lastPurity = if (mass == 0L) 0 else (packet.contents[dominant] * Signals.FULL / mass).toInt(),
            lastMass = mass,
        )
    }
}

/**
 * Which way material moves on one conduit layer — **toward whatever will consume it**.
 *
 * A breadth-first sweep outward from every *input* port gives each linked segment tile a **distance
 * to the nearest sink**, and material moves downhill from there. Nothing about a tile says which way
 * it points; where the consumers are says it.
 *
 * Pulling rather than pushing is the correction that makes a junction behave the way a player
 * expects, and it is not a tuning detail — it changes what a network *is*. Pushed away from its
 * source, material fans out into every branch it can reach and piles up at the end of the ones that
 * lead nowhere, so a half-built line silently fills with stock you then have to dig out. Pulled
 * toward its sinks, a branch with nothing on the end of it has **no distance at all**, so nothing
 * ever enters it. Dead ends stay empty because they are dead ends, not because anything checks for
 * one.
 *
 * It also makes the useful failure legible: a line that has stopped moving is a line with no
 * consumer on it, and that is a thing the player can go and look at.
 *
 * The important property is not the flow itself but the **order**. "The first input a packet reaches"
 * has to be a fact about the pipe's topology, and if runs were walked in grid order it would instead
 * be a fact about how the array is indexed — which is exactly the arbitrary junction priority this
 * project decided not to inherit. Walking outward from the sink makes *downstream buildings starve
 * upstream ones* a real, discoverable, deterministic mechanic rather than a coin flip.
 */
class FlowField private constructor(
    private val distance: IntArray,
    private val successors: Array<IntArray>,
    /**
     * Segment tiles, **nearest to a sink first**. Advancing in this order means the tile in front is
     * emptied before the one behind it tries to move into it, so a full run shuffles along by one in
     * a single pass instead of one tile per tick.
     */
    val order: IntArray,
) {
    /** Steps to the nearest sink, or -1 for a tile that can reach none. */
    fun distanceAt(tile: Int): Int = if (tile in distance.indices) distance[tile] else -1

    /** True when something on this tile has somewhere to go. */
    fun isFed(tile: Int): Boolean = distanceAt(tile) >= 0

    /** The tiles material moves to from here. More than one is a fork. */
    fun successorsOf(tile: Int): IntArray =
        if (tile in successors.indices) successors[tile] else EMPTY

    companion object {
        private val EMPTY = IntArray(0)

        /**
         * @param isSegment whether a tile carries a segment of the layer being derived
         * @param linked whether the segment at a tile is joined to its neighbour in a direction.
         *   Asked in one direction only; the caller keeps links symmetric.
         * @param sinks tiles where material leaves the layer — a building or bridge **input** port
         *   with track under it.
         */
        fun derive(
            grid: Grid,
            isSegment: (Int) -> Boolean,
            linked: (tile: Int, dir: Direction) -> Boolean,
            sinks: Collection<Int>,
        ): FlowField {
            val distance = IntArray(grid.size) { -1 }

            // Sorted, so a world with several sinks produces the same field however the caller
            // happened to collect them. `sorted()` rather than `toSortedSet()`: the latter is a
            // JVM-only extension, and this has to compile for JS and Android too.
            val queue = ArrayDeque<Int>()
            for (tile in sinks.distinct().sorted()) {
                if (tile in distance.indices && isSegment(tile) && distance[tile] < 0) {
                    distance[tile] = 0
                    queue.addLast(tile)
                }
            }
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (dir in Direction.ALL) {
                    if (!linked(at, dir)) continue
                    val next = grid.neighbour(at, dir)
                    if (next < 0 || !isSegment(next) || distance[next] >= 0) continue
                    distance[next] = distance[at] + 1
                    queue.addLast(next)
                }
            }

            // Downhill: a successor is a linked neighbour one step closer to a sink. A tile at
            // distance 0 is already at one and has none, which is what stops material walking
            // through a consumer and out the far side.
            val successors = Array(grid.size) { EMPTY }
            for (tile in 0 until grid.size) {
                if (distance[tile] <= 0) continue
                var found = 0
                val buffer = IntArray(4)
                for (dir in Direction.ALL) {
                    if (!linked(tile, dir)) continue
                    val next = grid.neighbour(tile, dir)
                    if (next >= 0 && distance[next] == distance[tile] - 1) buffer[found++] = next
                }
                if (found > 0) successors[tile] = buffer.copyOf(found)
            }

            val fed = (0 until grid.size).filter { distance[it] >= 0 }
            // Nearest first; ties by tile index so the order is total, not merely a partial one.
            val order = fed.sortedWith(compareBy<Int> { distance[it] }.thenBy { it })
            return FlowField(distance, successors, order.toIntArray())
        }
    }
}

/**
 * Where a fork last sent material, so the next packet goes the other way.
 *
 * ONI resolves a junction by whichever branch its iteration happens to favour, which is why priority
 * there is a folklore skill rather than a mechanic. A fork here is a **diverter**: it remembers, and
 * it alternates. That makes a three-way split genuinely even instead of evenly-ish, and it makes the
 * answer a property of the junction rather than of the array.
 *
 * Stored per fork tile, and only for tiles that actually fork, so this stays empty in almost every
 * world.
 */
class Diverters private constructor(internal val cursor: Map<Int, Int>) {

    /** Which branch this fork will try first. Zero for a tile that has never forked anything. */
    operator fun get(tile: Int): Int = cursor[tile] ?: 0

    val isEmpty: Boolean get() = cursor.isEmpty()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Diverters && cursor == other.cursor)

    override fun hashCode(): Int = cursor.hashCode()

    override fun toString(): String = "Diverters($cursor)"

    companion object {
        val EMPTY: Diverters = Diverters(emptyMap())
        fun of(cursor: Map<Int, Int>): Diverters = if (cursor.isEmpty()) EMPTY else Diverters(cursor.toMap())
    }
}

/** Mutable diverter cursors for one tick. */
class DiverterWork(diverters: Diverters) {
    private val cursor: MutableMap<Int, Int> = HashMap(diverters.cursor)

    /**
     * Picks a successor for a packet leaving [tile], preferring one that is free, and alternating
     * between them so a fork splits its throughput rather than favouring a branch.
     */
    fun choose(tile: Int, options: IntArray, isFree: (Int) -> Boolean): Int {
        if (options.isEmpty()) return -1
        if (options.size == 1) return if (isFree(options[0])) options[0] else -1
        val start = cursor[tile] ?: 0
        for (step in options.indices) {
            val pick = options[(start + step) % options.size]
            if (isFree(pick)) {
                // Advance past the branch actually *used*, not past the one we hoped to use. A
                // blocked branch must not consume its turn, or a jam on one side would quietly
                // halve the throughput of the other.
                cursor[tile] = (start + step + 1) % options.size
                return pick
            }
        }
        return -1
    }

    fun snapshot(): Diverters = Diverters.of(cursor)
}

/**
 * Moves everything on one conduit layer one step, offering each packet to whatever sits under it
 * first.
 *
 * The order is the whole design, and it is two rules:
 *
 *  1. **A tile's own port gets first refusal.** A packet passing under a building's input is taken
 *     if the building has room — that is what makes "the first input along the run wins" true, and
 *     with [FlowField.order] walking outward from the sink it is a statement about the pipe rather
 *     than about the array.
 *  2. **Nearest to a sink moves first**, so the tile ahead is always empty by the time the one
 *     behind it tries to move up. A packed run advances by one along its whole length in a single
 *     pass rather than crawling a tile per tick.
 *
 * Material on a tile the field never reached does not move at all: it is on a run with no consumer,
 * and there is nowhere for it to go that would be an improvement.
 *
 * A packet that cannot move because the tile ahead is occupied will **squash into it** where the two
 * can combine at all, so a blocked run bunches up toward its destination rather than standing in a
 * queue of gaps. ONI does this too, and gets it free because materials there cannot mix.
 *
 * Here they can, and what decides it is [Form.isPowder]. Two lots of ore tip together into one lot
 * at a purity in between, because that is what powder does and there is no way back from it. Two
 * ingots stay two ingots however hard they are pressed together, and two *different* forms never
 * combine at all.
 *
 * That is not a limitation to work around — it is the mechanic. Merging a line of 41% ore into one
 * carrying 75% concentrate destroys the refining that separated them, so keeping streams apart is
 * something the player has to actually do. Sending four kinds of ingot down one belt, by contrast,
 * is merely untidy.
 *
 * @param absorb offered every packet on the tile it currently occupies; returns what is left, or
 *   null when the whole packet was taken.
 * @return the number of packets that moved or merged, which is only useful for tests.
 */
fun advanceSegments(
    flow: FlowField,
    held: Array<Packet?>,
    diverters: DiverterWork,
    absorb: (tile: Int, packet: Packet) -> Packet?,
): Int {
    var moved = 0
    for (tile in flow.order) {
        val packet = held[tile] ?: continue

        val leftover = absorb(tile, packet)
        held[tile] = leftover
        if (leftover == null) continue

        val options = flow.successorsOf(tile)
        val target = diverters.choose(tile, options) { held[it] == null }
        if (target >= 0) {
            held[target] = leftover
            held[tile] = null
            moved++
            continue
        }

        // Nowhere free. Squash forward into an identical packet if there is one with room. Checked
        // in the successors' own order so a fork behaves the same way it would when moving.
        for (option in options) {
            val ahead = held[option] ?: continue
            val squashed = squashOnto(ahead, leftover) ?: continue
            held[option] = squashed.merged
            held[tile] = squashed.rejected
            moved++
            break
        }
    }
    return moved
}

/**
 * Merges [incoming] into [ahead] where the two can genuinely combine, else null.
 *
 * [mergeInto] already refuses to mix two different forms, or a solid with a fluid. The extra
 * condition here is [Form.isPowder]: within one form, only a powder actually flows together. Two
 * ingots of the same metal are still two ingots, and pressing them against each other on a jammed
 * belt does not make one bigger ingot.
 */
fun squashOnto(ahead: Packet, incoming: Packet): MergeResult? {
    if (Capacity.headroom(ahead) <= 0L) return null
    val form = (ahead as? SolidPacket)?.form
    // Fluids always flow together; a solid only does if it is a powder.
    if (form != null && !form.isPowder) return null
    return mergeInto(ahead, incoming)
}
