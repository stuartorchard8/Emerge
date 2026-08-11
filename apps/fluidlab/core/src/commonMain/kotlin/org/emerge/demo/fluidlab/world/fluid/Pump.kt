package org.emerge.demo.fluidlab.world.fluid

import org.emerge.demo.fluidlab.world.Pump

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
 * Apply pumps: gas from room→pipe, against pressure gradient (stalls at STALL_RATIO× intake pressure).
 * Intake: destroys room-face momentum → hands all to vessel (ledger closed). Pipe receives zero momentum (emergent flow via pressure/projection).
 * Valve = hole (carries momentum through); pump = blades (interrupts flow).
 * Runs with exchangeLayers, before either layer solved.
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
