package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Form
import org.emerge.demo.outofspace.logistics.Capacity
import org.emerge.demo.outofspace.logistics.MergeResult
import org.emerge.demo.outofspace.logistics.Packet
import org.emerge.demo.outofspace.logistics.SolidPacket
import org.emerge.demo.outofspace.logistics.mergeInto

/**
 * Advance segments one step: port-first refusal, then nearest-to-sink-first (single-pass shuffle).
 * `arrived` prevents double-move when rankings interleave (machine output mid-line).
 * Powder packets squash/merge; ingots stay separate (player must keep streams apart).
 */
fun advanceSegments(
    flow: FlowGraph,
    held: Array<Packet?>,
    cursors: FlowCursors,
    log: MotionLog? = null,
    absorb: (tile: TileIndex, packet: Packet) -> Packet?,
): Int {
    var moved = 0
    /**
     * Tiles that took delivery during this pass.
     *
     * A packet moves one tile per advance, and that has to be true of the *packet* rather than of
     * the walk. Where [FlowGraph.order] cannot put a tile ahead of the one feeding it — a run that
     * moves by both rules at once, which any output port partway along a line creates — this is what
     * keeps the step to one. It also means such a tile does not offer the new arrival to its own
     * port until next pass, which is what every other tile on the run does anyway.
     */
    val arrived = BooleanArray(held.size)

    /**
     * Tiles already walked this pass.
     *
     * Only a merge needs this. Deciding whose turn it is means knowing which of the feeders still
     * have a turn coming — one already walked has had its chance and either moved or could not, and
     * holding the junction open for it would idle the junction for a tick.
     */
    val walked = BooleanArray(held.size)

    for (tile in flow.order) {
        walked[tile.index] = true
        if (arrived[tile.index]) continue
        val packet = held[tile.index] ?: continue

        val leftover = absorb(tile, packet)
        held[tile.index] = leftover
        if (leftover == null) {
            log?.takenFromRail(tile, packet)
            continue
        }

        val way = cursors.choose(flow, tile) { target ->
            held[target.index] == null && mayMerge(flow, cursors, held, walked, tile, target)
        }
        if (way != null) {
            val target = flow.neighbour(tile, way)
            cursors.mergeUsed(flow.feeders(target), target, tile)
            held[target.index] = leftover
            held[tile.index] = null
            arrived[target.index] = true
            log?.moved(tile, target, way)
            moved++
            continue
        }

        // Nowhere free. Squash forward into an identical packet if there is one with room. Checked
        // in the successors' own order so a fork behaves the same way it would when moving.
        for (option in flow.successorTiles(tile)) {
            val ahead = held[option.index] ?: continue
            val squashed = squashOnto(ahead, leftover) ?: continue
            held[option.index] = squashed.merged
            held[tile.index] = squashed.rejected
            arrived[option.index] = true
            moved++
            break
        }
    }
    return moved
}

/**
 * Whether [from] is the feeder whose turn it is to move into [target].
 *
 * Where two runs join, somebody has to go first, and until this existed the answer was whichever
 * feeder happened to sort earlier — which is not a preference but a starvation: the same run won
 * every tick for ever and the other never moved at all. A merge takes turns, exactly as a fork does.
 *
 * A feeder only counts as waiting if it has something to hand over and has not already been walked
 * this pass. Otherwise a junction would hold its turn open for a run that is empty, or for one that
 * has already been past, and lose a tick of throughput to a queue that was never there.
 */
private fun mayMerge(
    flow: FlowGraph,
    cursors: FlowCursors,
    held: Array<Packet?>,
    walked: BooleanArray,
    from: TileIndex,
    target: TileIndex,
): Boolean {
    val feeders = flow.feeders(target)
    if (feeders.size <= 1) return true
    val turn = cursors.preferredFeeder(feeders, target) { feeder ->
        held[feeder.index] != null && (feeder == from || !walked[feeder.index])
    }
    return turn == from
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
