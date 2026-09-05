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
    /**
     * How much of what stands at [from] the route into [to] can usefully take, or [Long.MAX_VALUE]
     * for "all of it" — asked **only at a fork**, and only after [admits] has already said yes.
     *
     * ⛔ **Only at a fork, and that is not an optimisation.** Handing over less than the whole lump
     * leaves a remainder standing, and a remainder standing in a corridor with one way out is not a
     * saving — it is the material failing to arrive, with the run behind it stopped as well. Where a
     * tile has a second way out the remainder has somewhere to be, and it goes there in this same
     * pass: a fork is exactly where a lump has a choice, which is the same place demand is rationed.
     *
     * The caller sizes the slice and is responsible for it being one the door would still take — a
     * proportional slice is only representative while it is big enough to carry every species.
     */
    handOver: (from: TileIndex, to: TileIndex) -> Long = { _, _ -> Long.MAX_VALUE },
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

        // ── One way out, or several ─────────────────────────────────────────
        //
        // A fork is the one place a lump can be **divided**: a route that wants 30kg of the 100kg
        // standing here takes 30kg, and the rest goes the other way in this same pass rather than
        // riding to a place that has no use for it. See [handOver], and [RailLayer.splitInto] for
        // why taking from a lump is allowed where adding to one is not.
        //
        // ⚠️ Each branch can be used at most once — it stops being empty the moment it is fed — so
        // the loop is bounded by the number of ways out however the sizing behaves.
        val forked = flow.outDegree(tile) > 1
        var handedOn = false
        while (true) {
            val way = cursors.choose(flow, tile) { target ->
                rail.isEmpty(target) && admits(tile, target) &&
                    mayMerge(flow, cursors, rail, walked, tile, target, admits)
            } ?: break
            val target = flow.neighbour(tile, way)
            cursors.mergeUsed(flow.feeders(target), target, tile)
            val here = rail.massAt(tile)
            val room = if (forked) handOver(tile, target) else Long.MAX_VALUE
            if (room >= here) {
                rail.moveInto(tile, target)
                arrived[target.index] = true
                log?.moved(tile, target, way)
                moved++
                handedOn = true
                break
            }
            // Less than the whole. Nought means the route can take nothing after all, which the
            // door said it could — refuse rather than spin, and let the lump wait a step.
            if (rail.splitInto(tile, target, room) == null) break
            arrived[target.index] = true
            log?.splitOff(tile, target, way)
            moved++
            handedOn = true
            // …and round again with what is left, for the next way out of this tile.
        }
        if (handedOn) continue

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
 * Merges [incoming] into [ahead] where capacity allows, else null.
 *
 * ⚠️ **Not the merge rule, and nothing on the transport path calls this.** What decides whether two
 * lumps on a run may become one is [org.emerge.demo.outofspace.world.RailLayer.squashInto], which
 * asks what they are *made of* — this only asks whether they fit. Reach for that one.
 */
fun squashOnto(ahead: Packet, incoming: Packet): MergeResult? {
    if (Capacity.headroom(ahead) <= 0L) return null
    return mergeInto(ahead, incoming)
}
