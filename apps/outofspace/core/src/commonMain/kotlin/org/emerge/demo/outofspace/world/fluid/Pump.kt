package org.emerge.demo.outofspace.world.fluid

import org.emerge.demo.outofspace.world.Pump

/**
 * One pump's request for this tick: take [millimoles] out of the room at [from] and put it into the
 * pipe at [into].
 *
 * A plain request rather than the machine itself, because the amount has already had the pump's RUN
 * activation applied to it. That keeps every question about signals and throttling in the reducer,
 * where the rest of the machine behaviour lives, and leaves this file with nothing to know except
 * how gas moves.
 */
class PumpDemand(val from: Int, val into: Int, val millimoles: Long)

/**
 * Runs every pump: gas out of a room, into a pipe, uphill.
 *
 * ### Uphill is the whole point
 *
 * [exchangeLayers] moves gas until two cells agree and then stops, which is what a hole does. A pump
 * keeps going past that, and the thing that eventually stops it is [Pump.STALL_RATIO] — the pressure
 * it can hold against what it is drawing from. Pressure is compared through the same volume-over-
 * temperature capacity the valve uses, so a pipe cell being an eighth of a tile is accounted for
 * once, in one place, rather than being a factor of eight sprinkled through two files.
 *
 * ### The intake destroys momentum, and the ship gets it
 *
 * Gas being drawn into a machine stops going wherever it was going: the intake is a solid object and
 * the momentum ends up in it. So a pump takes its share of the four faces of the tile it is drawing
 * from and hands **all** of it to the vessel, rather than carrying any across.
 *
 * That is the difference from a valve, which carries momentum through onto the identical face
 * wherever the far side is open. A valve is a hole and a hole does not interrupt a flow; a pump has
 * blades in the way. It is the same accounting [applyDrag] does, and it is what makes a pump usable
 * as a thruster later — mount one so it draws along an axis and the reaction is real, booked, and in
 * the ledger rather than conjured.
 *
 * The gas arrives in the pipe with **no** momentum at all, which is deliberate and is where the
 * emergent behaviour comes from. Nothing tells it which way to go down the run. It arrives as
 * pressure, and [applyPressureForce] and [project] work out the flow on the next pass exactly as they
 * would for any other pressurised cell — so a pump on a tee feeds both branches, feeds the emptier
 * one harder, and reverses when the network's balance changes, with none of that written anywhere.
 *
 * Every array is **edited in place**. Runs alongside [exchangeLayers], before either layer is solved.
 */
fun applyPumps(
    edges: EdgeGrid,
    demands: List<PumpDemand>,
    roomGrams: LongArray,
    roomJoules: LongArray?,
    roomMx: LongArray,
    roomMy: LongArray,
    pipeGrams: LongArray,
    pipeJoules: LongArray?,
    pipeVolumes: VolumeField,
): InterlayerStep {
    if (demands.isEmpty()) return InterlayerStep(0L, 0L, 0L, 0L)

    var movedGrams = 0L
    var movedJoules = 0L
    var vesselX = 0L
    var vesselY = 0L

    for (demand in demands) {
        if (demand.millimoles <= 0L) continue

        val roomMoles = millimolesOf(roomGrams, demand.from)
        if (roomMoles <= 0L) continue

        val roomCapacity = pressureCapacity(VolumeField.FULL, kelvinAt(roomGrams, roomJoules, demand.from))
        val pipeCapacity =
            pressureCapacity(pipeVolumes.at(demand.into), kelvinAt(pipeGrams, pipeJoules, demand.into))
        val pipeMoles = millimolesOf(pipeGrams, demand.into)

        // Stalled: the pipe is already holding this pump's limit. Compared as a cross-multiplication
        // rather than by forming two pressures, so a thin cell cannot round its way past the check.
        if (pipeMoles * roomCapacity >= Pump.STALL_RATIO * roomMoles * pipeCapacity) continue

        // Never more than the room actually has. A pump on a nearly empty deck slows down and stops,
        // which is the honest behaviour and also stops the share below exceeding one.
        val taken = minOf(demand.millimoles, roomMoles)
        val share = Share(taken, roomMoles)

        val moved = handOver(share, demand.from, demand.into, roomGrams, roomJoules, pipeGrams, pipeJoules)
        movedGrams += moved.grams
        movedJoules += moved.joules

        vesselX += absorb(share, roomMx, edges.leftEdgeOf(demand.from), edges.rightEdgeOf(demand.from))
        vesselY += absorb(share, roomMy, edges.upEdgeOf(demand.from), edges.downEdgeOf(demand.from))
    }

    return InterlayerStep(movedGrams, movedJoules, vesselX, vesselY)
}

/**
 * Takes the drawn gas's momentum off both faces of one axis and returns it, for the vessel.
 *
 * Halved per face for the reason it is halved in a valve: a face is shared with the neighbouring
 * cell, and only this cell's half is being drawn out of the room.
 */
private fun absorb(share: Share, momentum: LongArray, first: Int, second: Int): Long {
    var absorbed = 0L
    for (edge in intArrayOf(first, second)) {
        val carried = share.of(momentum[edge]) / 2
        if (carried == 0L) continue
        momentum[edge] -= carried
        absorbed += carried
    }
    return absorbed
}
