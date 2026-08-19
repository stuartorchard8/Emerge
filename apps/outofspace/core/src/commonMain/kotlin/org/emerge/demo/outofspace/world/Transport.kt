package org.emerge.demo.outofspace.world

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
    rail: RailLayer,
    cursors: FlowCursors,
    log: MotionLog? = null,
    /**
     * Whether what is standing at `from` may enter `to` at all.
     *
     * ⛔ Not a preference — the rule that stops a **ghost** being a free length of track. A ghost
     * draws material to itself, and if anything at all could cross its tile a player would build a
     * whole network out of whatever was to hand and never pay a gram of iron for it. So the refusal
     * is at the door, on the way in, and not a matter of what the ghost keeps once material is past.
     *
     * Everything else admits everything, which is what a length of finished track has always done.
     */
    admits: (from: TileIndex, to: TileIndex) -> Boolean = { _, _ -> true },
    absorb: (tile: TileIndex) -> Packet?,
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
    val arrived = BooleanArray(rail.tileCount)

    /**
     * Tiles already walked this pass.
     *
     * Only a merge needs this. Deciding whose turn it is means knowing which of the feeders still
     * have a turn coming — one already walked has had its chance and either moved or could not, and
     * holding the junction open for it would idle the junction for a tick.
     */
    val walked = BooleanArray(rail.tileCount)

    for (tile in flow.order) {
        walked[tile.index] = true
        if (arrived[tile.index]) continue
        if (rail.isEmpty(tile)) continue

        // Whatever a port took, if it took the lot. Returned rather than read back off the layer
        // because by then it is gone, and the departure ghost is drawn from what left.
        val taken = absorb(tile)
        if (rail.isEmpty(tile)) {
            if (taken != null) log?.takenFromRail(tile, taken)
            continue
        }

        val way = cursors.choose(flow, tile) { target ->
            rail.isEmpty(target) && admits(tile, target) &&
                mayMerge(flow, cursors, rail, walked, tile, target, admits)
        }
        if (way != null) {
            val target = flow.neighbour(tile, way)
            cursors.mergeUsed(flow.feeders(target), target, tile)
            rail.moveInto(tile, target)
            arrived[target.index] = true
            log?.moved(tile, target, way)
            moved++
            continue
        }

        // Nowhere free. Squash forward into an identical packet if there is one with room. Checked
        // in the successors' own order so a fork behaves the same way it would when moving.
        for (option in flow.successorTiles(tile)) {
            if (rail.isEmpty(option)) continue
            // Squashing forward is still entering, so it is refused at the same door. Without this
            // a ghost that had already admitted one lump would take anything pressed in behind it.
            if (!admits(tile, option)) continue
            if (rail.squashInto(tile, option) == Squash.Refused) continue
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
 * A feeder only counts as waiting if it has something the junction **will take**, and has not
 * already been walked this pass. Otherwise a junction holds its turn open for a run that is empty,
 * for one that has already been past, or — the expensive one — for one that can never move at all.
 *
 * ⛔ **"Has something" is not the same question as "can hand it over".** A stub of raw ore ending at
 * a junction on the way to a ghost rail has something, for ever: the ghost refuses it, so it never
 * moves, so the cursor — which only advances when a move actually happens — never leaves it, and
 * the branch carrying the iron is starved permanently by a branch that is not moving either. Found
 * in Stu's save, and called from the symptom: a ghost stuck at 24% with a full packet of iron
 * standing five tiles away on a road that was open the whole time.
 *
 * ⚠️ The turn is only *held* by a feeder that has already had its go this pass. That much was always
 * right, and it is why the same jam does not appear when the stuck branch happens to be walked
 * first — which is exactly why it took a save to find.
 */
private fun mayMerge(
    flow: FlowGraph,
    cursors: FlowCursors,
    rail: RailLayer,
    walked: BooleanArray,
    from: TileIndex,
    target: TileIndex,
    admits: (TileIndex, TileIndex) -> Boolean,
): Boolean {
    val feeders = flow.feeders(target)
    if (feeders.size <= 1) return true
    val turn = cursors.preferredFeeder(feeders, target) { feeder ->
        // ⚠️ [admits] reads a lump off the layer, so it is asked last and only at a junction — the
        // one place in the transport pass where more than one tile's contents decide the answer.
        !rail.isEmpty(feeder) && (feeder == from || !walked[feeder.index]) && admits(feeder, target)
    }
    return turn == from
}

/**
 * Merges [incoming] into [ahead] where the two can genuinely combine, else null.
 *
 * Only capacity stands in the way now. This used to also refuse anything that was not a powder —
 * two ingots pressed together on a jammed belt are still two ingots — and that rule went with
 * [org.emerge.demo.outofspace.chem.Mixture] becoming the only thing a packet is. It is the *reason*
 * belt blending works: everything on a belt is a heap, and heaps combine.
 */
fun squashOnto(ahead: Packet, incoming: Packet): MergeResult? {
    if (Capacity.headroom(ahead) <= 0L) return null
    return mergeInto(ahead, incoming)
}
