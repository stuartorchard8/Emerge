package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.MergeResult
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.logistics.mergeInto

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
