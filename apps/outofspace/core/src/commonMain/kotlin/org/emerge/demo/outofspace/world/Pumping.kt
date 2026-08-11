package org.emerge.demo.outofspace.world

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
 * Apply pumps: gas from room→pipe, against pressure gradient (stalls at STALL_RATIO× intake pressure).
 * Runs with exchangeLayers, before either layer is diffused.
 */
fun applyPumps(
    demands: List<PumpDemand>,
    roomGrams: LongArray,
    roomJoules: LongArray?,
    pipeGrams: LongArray,
    pipeJoules: LongArray?,
    pipeVolumes: VolumeField,
): InterlayerStep {
    if (demands.isEmpty()) return InterlayerStep(0L, 0L)

    var movedGrams = 0L
    var movedJoules = 0L

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
    }

    return InterlayerStep(movedGrams, movedJoules)
}

