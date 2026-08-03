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
 * Which way material moves on one conduit layer: **away from where it came in, toward anything that
 * will take it.**
 *
 * Two sweeps, and it needs both. Neither alone is enough, and the history of this file is three
 * attempts to make one of them do the whole job.
 *
 *  - **Depth**, breadth-first from every *output* port — how far a tile is from where material
 *    enters the layer. This is what "forward" means. A step is only legal if it increases depth by
 *    one, which makes the flow a directed acyclic graph and is the reason nothing can ever circle or
 *    oscillate.
 *  - **Distance**, breadth-first from every *input* port — how far a tile is from somewhere material
 *    can leave. This is what makes a step *worth taking*. A branch with no consumer on the end has no
 *    distance at all, so nothing enters it: dead ends stay empty because they are dead ends, not
 *    because anything checks for one.
 *
 * ### Why pulling alone could not do it
 *
 * Pulling was introduced to fix pushing, which fanned material into every branch it could reach and
 * piled it up at the ends of the ones leading nowhere. Pulling fixed that, but "move to the neighbour
 * closest to a consumer" cannot tell a **fork** from a **shortcut**. Where a line splits toward a
 * vent two tiles away and a tank three tiles away, every packet went to the vent — not by a rule
 * anybody wrote, but because one branch was nearer. [Diverters] existed the whole time to alternate
 * at exactly such a junction and had almost nothing to alternate between, because a fork only ever
 * produced two successors when the two branches were the same length to the tile.
 *
 * Symmetry like that cannot be broken by looking at the consumers, because the consumers are
 * symmetric. It is broken by knowing which way the material came in — which is a fact about the
 * source, and why the source sweep is back. Both branches of a real fork sit one step further from
 * the source, so both are legal, and the diverter gets its choice.
 *
 * The same knowledge is what makes forking *safe*. Offering "any neighbour that leads to some
 * consumer" without it lets two tiles each be a legal step for the other, and a packet between two
 * consumers walks back and forth forever. Requiring depth to increase makes that impossible by
 * construction rather than by a guard.
 *
 * ### A consumer that cannot take anything does not pull
 *
 * Inputs are split by whether they have room. A full one still counts, but only as a last resort —
 * see [FULL_PENALTY]. So traffic runs *past* a full machine to reach a working one, and when there
 * is nothing better anywhere it still travels to the blockage and packs in behind it, which is how a
 * jam stays visible on the deck instead of hiding inside the machine feeding the belt.
 *
 * ### Where forward runs out
 *
 * Depth is measured from every source at once, and that means a **merge** — a second producer
 * joining a run that already has one — does more than add material. The newcomer resets depth to
 * zero where it lands and inverts the gradient over everything upstream of it, so the two waves meet
 * at a tile with nothing deeper beside it. That tile has no forward at all, and because a branch
 * leading nowhere is not worth entering, the emptiness spreads back up the line until the first
 * producer has nowhere to put anything either. Half a factory goes quiet because somebody bridged
 * onto its belt.
 *
 * So forward is not the only rule. Where a tile's forward leads nowhere useful — a watershed like
 * that, or a run whose miner was just dismantled and which the source sweep never reached at all —
 * material falls back to the older rule and moves **downhill toward the nearest consumer**. The two
 * coexist deliberately, and the split is not a special case bolted on: it is the difference between
 * "material is being driven along here" and "material is finding its own way out", which really are
 * two situations with two right answers.
 *
 * The fallback is safe for the reason the old model was safe — the potential strictly decreases —
 * and it cannot be reached from a tile that has a working forward, because a tile uses one rule or
 * the other and never both.
 *
 * ### Order
 *
 * The important property is not the flow itself but the **order** tiles are walked in. "The first
 * input a packet reaches" has to be a fact about the pipe's topology; if runs were walked in grid
 * order it would instead be a fact about how the array is indexed, which is exactly the arbitrary
 * junction priority this project decided not to inherit.
 */
class FlowField private constructor(
    private val grid: Grid,
    private val distance: IntArray,
    private val successors: Array<IntArray>,
    /**
     * Segment tiles, **most downstream first**. Advancing in this order means the tile in front is
     * emptied before the one behind it tries to move into it, so a full run shuffles along by one in
     * a single pass instead of one tile per tick.
     *
     * "Downstream" is measured by whichever rule each tile actually moves by, because a tile ranked
     * one way and moved another gets visited after the packet it just received and moves it twice.
     */
    val order: IntArray,
) {
    /** Steps to the nearest sink, or -1 for a tile that can reach none. */
    fun distanceAt(tile: Int): Int = if (tile in distance.indices) distance[tile] else -1

    /**
     * True when this tile is part of a live flow: material on it can move on, or be taken where it
     * stands.
     *
     * Deliberately not "has a distance to some consumer". A dead-end spur can always reach one by
     * turning round and going back out the way it came in, so that question answers yes for exactly
     * the tiles the network is meant to leave alone.
     */
    fun isFed(tile: Int): Boolean = successorsOf(tile).isNotEmpty() || distanceAt(tile) == 0

    /** The tiles material moves to from here. More than one is a fork. */
    fun successorsOf(tile: Int): IntArray =
        if (tile in successors.indices) successors[tile] else EMPTY

    /**
     * Which way you face going from [from] to the adjacent [to]. Only ever asked of a step the
     * field itself produced, so the two really are neighbours and the answer always exists.
     */
    fun directionBetween(from: Int, to: Int): Direction =
        Direction.ALL.first { grid.neighbour(from, it) == to }

    companion object {
        private val EMPTY = IntArray(0)

        /**
         * How much further away a consumer with no room counts as being.
         *
         * Larger than any path across any grid this game will have, so an accepting consumer
         * *anywhere* on a run beats a full one next door. It is a tie-break with a very heavy thumb
         * rather than an exclusion, which is the whole point — see [derive].
         */
        const val FULL_PENALTY = 1_000_000

        /**
         * @param isSegment whether a tile carries a segment of the layer being derived
         * @param linked whether the segment at a tile is joined to its neighbour in a direction.
         *   Asked in one direction only; the caller keeps links symmetric.
         * @param sources tiles where material **enters** the layer — an *output* port with track
         *   under it. These give the flow its direction, and are what lets a fork be a fork.
         * @param accepting tiles where material can leave the layer **right now** — an input port
         *   with track under it and room behind it.
         * @param full input ports with track under them and no room. These still pull, but only
         *   once nothing else will: material queues up behind a blockage rather than abandoning it,
         *   and that queue is how a jam stays visible.
         */
        fun derive(
            grid: Grid,
            isSegment: (Int) -> Boolean,
            linked: (tile: Int, dir: Direction) -> Boolean,
            accepting: Collection<Int>,
            full: Collection<Int> = emptyList(),
            sources: Collection<Int> = emptyList(),
        ): FlowField {
            val distance = IntArray(grid.size) { -1 }
            /** Which sink each tile's shortest path leads to. Only read for tiles *at* a sink. */
            val nearest = IntArray(grid.size) { -1 }
            /** Steps from where material enters the layer. This is what "forward" means. */
            val depth = IntArray(grid.size) { -1 }

            val queue = ArrayDeque<Int>()

            // Sorted, so a world with several sinks produces the same field however the caller
            // happened to collect them. `sorted()` rather than `toSortedSet()`: the latter is a
            // JVM-only extension, and this has to compile for JS and Android too.
            fun sweep(field: IntArray, tiles: Collection<Int>, from: Int, trackNearest: Boolean) {
                for (tile in tiles.distinct().sorted()) {
                    if (tile in field.indices && isSegment(tile) && field[tile] < 0) {
                        field[tile] = from
                        if (trackNearest) nearest[tile] = tile
                        queue.addLast(tile)
                    }
                }
                while (queue.isNotEmpty()) {
                    val at = queue.removeFirst()
                    for (dir in Direction.ALL) {
                        if (!linked(at, dir)) continue
                        val next = grid.neighbour(at, dir)
                        if (next < 0 || !isSegment(next) || field[next] >= 0) continue
                        field[next] = field[at] + 1
                        if (trackNearest) nearest[next] = nearest[at]
                        queue.addLast(next)
                    }
                }
            }

            // Two tiers, and the order is the behaviour. Everything an accepting consumer can reach
            // is measured first, so a full machine that happens to sit on such a run comes out an
            // ordinary distance from a working one. Only then do the still-unreached full consumers
            // get seeded, far away, giving a line with nowhere else to go something to queue against.
            sweep(distance, accepting, 0, trackNearest = true)
            sweep(distance, full, FULL_PENALTY, trackNearest = true)
            sweep(depth, sources, 0, trackNearest = false)

            // ── The forward graph, and what each branch of it leads to ──
            //
            // Forward is away from the source: a step is legal only if it increases depth by one.
            // That makes this a DAG, which is what stops a packet circling, and it means both arms
            // of a genuine fork are legal where a shortest-path rule would have picked one.
            val forward = Array(grid.size) { EMPTY }
            for (tile in 0 until grid.size) {
                if (depth[tile] < 0) continue
                val onward = Direction.ALL.mapNotNull { dir ->
                    if (!linked(tile, dir)) return@mapNotNull null
                    val next = grid.neighbour(tile, dir)
                    if (next < 0 || depth[next] != depth[tile] + 1) null else next
                }
                if (onward.isNotEmpty()) forward[tile] = onward.sorted().toIntArray()
            }

            // Whether a branch is worth entering has to be asked **along the forward graph**, not
            // of the undirected one. A dead-end spur can always "reach" a consumer by turning round
            // and going back out the way it came in, so asking `distance >= 0` would send material
            // down it — the exact dead-end filling that pulling was introduced to stop.
            val acceptingSet = accepting.toHashSet()
            val fullSet = full.toHashSet()
            val reachesAccepting = BooleanArray(grid.size)
            val reachesAnything = BooleanArray(grid.size)
            // Deepest first, so every successor is settled before the tile that feeds it is asked.
            for (tile in (0 until grid.size).filter { depth[it] >= 0 }.sortedByDescending { depth[it] }) {
                reachesAccepting[tile] = tile in acceptingSet || forward[tile].any { reachesAccepting[it] }
                reachesAnything[tile] =
                    tile in acceptingSet || tile in fullSet || forward[tile].any { reachesAnything[it] }
            }

            /**
             * The older rule: step to a neighbour strictly closer to a consumer.
             *
             * Safe on its own terms — the potential falls every step, so nothing can circle — and
             * blind to forks, which is why it is no longer the main rule. It is still exactly right
             * wherever "forward" has nothing to say.
             */
            fun downhill(tile: Int): IntArray {
                if (distance[tile] < 0) return EMPTY
                if (distance[tile] == 0) {
                    // A tile at a sink is not the end of the road. Whatever the consumer there does
                    // not take carries on to the next one. Neighbours whose own nearest sink is
                    // *this* one are excluded, or material refused here would be sent to a tile that
                    // would only send it straight back.
                    return Direction.ALL.mapNotNull { dir ->
                        if (!linked(tile, dir)) return@mapNotNull null
                        val next = grid.neighbour(tile, dir)
                        if (next < 0 || distance[next] < 0 || nearest[next] == tile) null else next
                    }.sortedWith(compareBy<Int> { distance[it] }.thenBy { it }).toIntArray()
                }
                var found = 0
                val buffer = IntArray(4)
                for (dir in Direction.ALL) {
                    if (!linked(tile, dir)) continue
                    val next = grid.neighbour(tile, dir)
                    if (next >= 0 && distance[next] == distance[tile] - 1) buffer[found++] = next
                }
                return buffer.copyOf(found)
            }

            val successors = Array(grid.size) { EMPTY }
            /** Which of the two rules each tile ended up moving by. Read only to build [order]. */
            val movesForward = BooleanArray(grid.size)
            for (tile in 0 until grid.size) {
                if (depth[tile] >= 0) {
                    // A branch leading to a working consumer beats one leading only to a blockage,
                    // and only when there is no such branch at all does the queue form.
                    val useful = forward[tile].filter { reachesAccepting[it] }
                    val chosen = useful.ifEmpty { forward[tile].filter { reachesAnything[it] } }
                    if (chosen.isNotEmpty()) {
                        successors[tile] = chosen.toIntArray()
                        movesForward[tile] = true
                        continue
                    }
                }
                val onward = downhill(tile)
                if (onward.isNotEmpty()) successors[tile] = onward
            }

            // Most-downstream first, so the tile ahead is empty by the time the one behind tries to
            // move up and a packed run shuffles along its whole length in one pass.
            //
            // "Downstream" has to be measured the same way movement is, or a packet can be carried
            // several tiles in a single pass: walking a source-fed run in nearest-to-a-sink order
            // visits the fork *before* the tile it just moved into, and moves the same packet again.
            // So a fed tile is ranked by how far it is from the source, and one nothing feeds — which
            // moves by the fallback rule — by how close it is to a consumer.
            // A tile is ranked by the same measure it moves by, and the tiles moving downhill sort
            // ahead of the ones moving forward, because downhill is what the tail of a run does.
            val fed = (0 until grid.size).filter { distance[it] >= 0 || depth[it] >= 0 }
            val order = fed.sortedWith(
                compareBy<Int> { if (movesForward[it]) 1 else 0 }
                    .thenBy { if (movesForward[it]) -depth[it] else distance[it] }
                    .thenBy { it },
            )
            return FlowField(grid, distance, successors, order.toIntArray())
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
 * That second rule is an *optimisation*, and it is not always achievable. [FlowField.order] ranks a
 * tile by the measure it actually moves by, and where a run uses both rules at once — which is what
 * a machine's output port partway along a line produces, since it makes that tile a source and
 * leaves everything behind it with no forward — the two rankings interleave and a tile can be walked
 * *after* the tile that feeds it. So the one-step-per-pass guarantee is enforced here instead, by
 * `arrived`: a packet that landed on a tile this pass does not move again, whatever the order said.
 * Without it a packet crossing such a port jumps two tiles in a tick and appears to skip over it.
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
    log: MotionLog? = null,
    absorb: (tile: Int, packet: Packet) -> Packet?,
): Int {
    var moved = 0
    /**
     * Tiles that took delivery during this pass.
     *
     * A packet moves one tile per advance, and that has to be true of the *packet* rather than of
     * the walk. Where [FlowField.order] cannot put a tile ahead of the one feeding it — a run that
     * moves by both rules at once, which any output port partway along a line creates — this is what
     * keeps the step to one. It also means such a tile does not offer the new arrival to its own
     * port until next pass, which is what every other tile on the run does anyway.
     */
    val arrived = BooleanArray(held.size)
    for (tile in flow.order) {
        if (arrived[tile]) continue
        val packet = held[tile] ?: continue

        val leftover = absorb(tile, packet)
        held[tile] = leftover
        if (leftover == null) {
            log?.takenFromRail(tile, packet)
            continue
        }

        val options = flow.successorsOf(tile)
        val target = diverters.choose(tile, options) { held[it] == null }
        if (target >= 0) {
            held[target] = leftover
            held[tile] = null
            arrived[target] = true
            log?.moved(tile, target, flow.directionBetween(tile, target))
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
            arrived[option] = true
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
