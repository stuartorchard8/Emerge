package org.emerge.demo.fluidlab.world.fluid

import org.emerge.demo.fluidlab.chem.Species
import org.emerge.demo.fluidlab.world.Temperature

/**
 * What crossed between room and pipe, and vessel impulse from momentum absorption.
 *
 * [mass] and [energy] are signed room-to-pipe (positive = room lost mass/energy).
 */
class InterlayerStep(
    val mass: Long,
    val energy: Long,
    val vesselX: Long,
    val vesselY: Long,
)

/**
 * Lets gas cross between a room and a pipe on the same tile wherever an opening exists.
 *
 * Relaxation, not advection: cells at the same place equalise by pressure capacity
 * (volume/temperature). Momentum rides the same fluxes; shut acceptor faces send momentum to vessel.
 * Called before [stepFluid] so pressure can propagate in the arriving tick.
 *
 * [openings] is per tile (CLOSED = no opening). All arrays edited in place.
 */
fun exchangeLayers(
    edges: EdgeGrid,
    openings: IntArray,
    roomApertures: ApertureField,
    roomGrams: LongArray,
    roomJoules: LongArray?,
    roomMx: LongArray,
    roomMy: LongArray,
    pipeApertures: ApertureField,
    pipeGrams: LongArray,
    pipeJoules: LongArray?,
    pipeMx: LongArray,
    pipeMy: LongArray,
    pipeVolumes: VolumeField,
): InterlayerStep {
    var movedGrams = 0L
    var movedJoules = 0L
    var vesselX = 0L
    var vesselY = 0L

    for (tile in openings.indices) {
        val opening = openings[tile]
        if (opening <= 0) continue

        val roomMoles = millimolesOf(roomGrams, tile)
        val pipeMoles = millimolesOf(pipeGrams, tile)
        if (roomMoles == 0L && pipeMoles == 0L) continue

        val roomCapacity = pressureCapacity(VolumeField.FULL, kelvinAt(roomGrams, roomJoules, tile))
        val pipeCapacity = pressureCapacity(pipeVolumes.at(tile), kelvinAt(pipeGrams, pipeJoules, tile))

        // The room's share at a common pressure, and how far it is from it. Positive means the room
        // is holding more than its share and gas moves into the pipe.
        val total = roomCapacity + pipeCapacity
        val surplus = (roomMoles * pipeCapacity - pipeMoles * roomCapacity) / total
        if (surplus == 0L) continue

        // Throttled by how wide the way is, which is what makes a part-open valve a part-open valve.
        val crossing = surplus * opening / ApertureField.OPEN
        if (crossing == 0L) continue

        val fromRoom = crossing > 0L
        val donorMoles = if (fromRoom) roomMoles else pipeMoles
        // Nothing to give. Can happen when the *other* side is hot enough to want moles out of an
        // empty cell, and there is no meaningful transfer to make.
        if (donorMoles <= 0L) continue

        val share = Share(if (crossing < 0L) -crossing else crossing, donorMoles)

        val moved = if (fromRoom) {
            handOver(share, tile, tile, roomGrams, roomJoules, pipeGrams, pipeJoules)
        } else {
            handOver(share, tile, tile, pipeGrams, pipeJoules, roomGrams, roomJoules)
        }
        // Signed room-to-pipe, so a valve breathing in and out reads as the small net it is.
        val sign = if (fromRoom) 1L else -1L
        movedGrams += sign * moved.mass
        movedJoules += sign * moved.energy

        val push = handOverMomentum(
            edges, tile, share,
            donorX = if (fromRoom) roomMx else pipeMx,
            donorY = if (fromRoom) roomMy else pipeMy,
            acceptorX = if (fromRoom) pipeMx else roomMx,
            acceptorY = if (fromRoom) pipeMy else roomMy,
            acceptorApertures = if (fromRoom) pipeApertures else roomApertures,
        )
        vesselX += push.x
        vesselY += push.y
    }

    return InterlayerStep(movedGrams, movedJoules, vesselX, vesselY)
}

/**
 * Fraction of a cell leaving, kept as a ratio. Multiplying then dividing keeps each quantity
 * (species mass, energy, momentum) at its own precision without rounding out trace species.
 */
internal class Share(val part: Long, val whole: Long) {
    fun of(quantity: Long): Long = quantity * part / whole
}

internal class Moved(val mass: Long, val energy: Long)

private class Push(val x: Long, val y: Long)

/**
 * Moves [share] of one cell's gas species-by-species, with energy. Tiles separate for pump usage
 * (draws from adjacent tile), same tile for valve (exchanges at one place).
 */
internal fun handOver(
    share: Share,
    donorTile: Int,
    acceptorTile: Int,
    donorGrams: LongArray,
    donorJoules: LongArray?,
    acceptorGrams: LongArray,
    acceptorJoules: LongArray?,
): Moved {
    val base = donorTile * Species.COUNT
    val target = acceptorTile * Species.COUNT
    var mass = 0L
    for (s in Species.ALL) {
        val i = base + s.ordinal
        val take = share.of(donorGrams[i])
        if (take == 0L) continue
        donorGrams[i] -= take
        acceptorGrams[target + s.ordinal] += take
        mass += take
    }

    // Energy as a fraction of donor (exact), not mass × temperature (accumulates rounding error).
    var energy = 0L
    if (donorJoules != null && acceptorJoules != null) {
        energy = share.of(donorJoules[donorTile])
        donorJoules[donorTile] -= energy
        acceptorJoules[acceptorTile] += energy
    }
    return Moved(mass, energy)
}

/**
 * Hands donor's share of each face to acceptor, or to vessel if acceptor face is shut.
 */
private fun handOverMomentum(
    edges: EdgeGrid,
    tile: Int,
    share: Share,
    donorX: LongArray,
    donorY: LongArray,
    acceptorX: LongArray,
    acceptorY: LongArray,
    acceptorApertures: ApertureField,
): Push {
    var vesselX = 0L
    var vesselY = 0L

    fun cross(edge: Int, donor: LongArray, acceptor: LongArray, open: Boolean): Long {
        // Half, because the face is shared with the neighbour and only this cell's half is leaving.
        val carried = share.of(donor[edge]) / 2
        if (carried == 0L) return 0L
        donor[edge] -= carried
        if (open) {
            acceptor[edge] += carried
            return 0L
        }
        return carried
    }

    for (edge in intArrayOf(edges.leftEdgeOf(tile), edges.rightEdgeOf(tile))) {
        vesselX += cross(edge, donorX, acceptorX, acceptorApertures.isXOpen(edge))
    }
    for (edge in intArrayOf(edges.upEdgeOf(tile), edges.downEdgeOf(tile))) {
        vesselY += cross(edge, donorY, acceptorY, acceptorApertures.isYOpen(edge))
    }
    return Push(vesselX, vesselY)
}

/**
 * Moles per unit of pressure (volume/temperature). Scaled for comfortable integers.
 */
internal fun pressureCapacity(volume: Int, kelvin: Int): Long =
    volume.toLong() * Temperature.AMBIENT_KELVIN / maxOf(kelvin, 1)

/** Cell gas temperature. No gas → ambient (same convention as [gasKelvin]). */
internal fun kelvinAt(mass: LongArray, gasJoules: LongArray?, tile: Int): Int {
    if (gasJoules == null) return Temperature.AMBIENT_KELVIN
    val capacity = gasCapacityAt(mass, tile)
    return if (capacity <= 0L) Temperature.AMBIENT_KELVIN else (gasJoules[tile] / capacity).toInt()
}
