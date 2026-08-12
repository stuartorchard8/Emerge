package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio
import org.emerge.demo.outofspace.chem.Species
import org.emerge.demo.outofspace.world.Temperature

/**
 * What crossed between room and pipe.
 *
 * [grams] and [joules] are signed room-to-pipe (positive = room lost mass/energy).
 */
class InterlayerStep(
    val grams: Long,
    val joules: Long,
)

/**
 * Lets gas cross between a room and a pipe on the same tile wherever an opening exists.
 *
 * Relaxation, not diffusion: cells at the same place equalise by pressure capacity
 * (volume/temperature), rather than trading a fixed share the way neighbours on a layer do — two
 * cells at one place are not a gradient, they are one place with two occupants. Called before
 * [diffuseFluid] so pressure can propagate in the arriving tick.
 *
 * [openings] is per tile (CLOSED = no opening). All arrays edited in place.
 */
fun exchangeLayers(
    openings: IntArray,
    roomGrams: LongArray,
    roomJoules: LongArray?,
    pipeGrams: LongArray,
    pipeJoules: LongArray?,
    pipeVolumes: VolumeField,
): InterlayerStep {
    var movedGrams = 0L
    var movedJoules = 0L

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
        movedGrams += sign * moved.grams
        movedJoules += sign * moved.joules
    }

    return InterlayerStep(movedGrams, movedJoules)
}

/**
 * Fraction of a cell leaving, kept as a ratio. Multiplying then dividing keeps each quantity
 * (species grams, energy, momentum) at its own precision without rounding out trace species.
 *
 * ### Why [of] goes through [scaledRatio]
 *
 * [part] and [whole] are **millimoles** — invariant under the mass rescale, and a tile of air is
 * about 34,500 of them — while [quantity] is a mass or an energy and so carries the mass unit in
 * full. `quantity * part` is therefore linear in the unit with a five-digit multiplier on top of it,
 * and a tile of ambient air at one microgram per unit holds 2.9e14 millijoules: the product reaches
 * 2.9e19 and wraps.
 *
 * That wrap is how it presented, and the presentation is worth recording because it was nothing like
 * the cause. A tile ended up holding **negative joules**, which read back as a negative kelvin, which
 * became a negative reduced temperature, which indexed `Saturation.sample` at −1 — an
 * `ArrayIndexOutOfBoundsException` inside the equation of state, six frames and two packages away
 * from a ratio in the plumbing.
 *
 * Reducing the fraction first is exact wherever the old form did not overflow, and conservation does
 * not rest on it either way: [handOver] subtracts from the donor and adds to the acceptor the same
 * number, whatever that number is.
 */
internal class Share(val part: Long, val whole: Long) {
    fun of(quantity: Long): Long = scaledRatio(part, whole, quantity)
}

internal class Moved(val grams: Long, val joules: Long)

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
    var grams = 0L
    for (s in Species.ALL) {
        val i = base + s.ordinal
        val take = share.of(donorGrams[i])
        if (take == 0L) continue
        donorGrams[i] -= take
        acceptorGrams[target + s.ordinal] += take
        grams += take
    }

    // Energy as a fraction of donor (exact), not mass × temperature (accumulates rounding error).
    var joules = 0L
    if (donorJoules != null && acceptorJoules != null) {
        joules = share.of(donorJoules[donorTile])
        donorJoules[donorTile] -= joules
        acceptorJoules[acceptorTile] += joules
    }
    return Moved(grams, joules)
}

/**
 * Moles per unit of pressure (volume/temperature). Scaled for comfortable integers.
 */
internal fun pressureCapacity(volume: Int, kelvin: Int): Long =
    volume.toLong() * Temperature.AMBIENT_KELVIN / maxOf(kelvin, 1)

/** Cell gas temperature. No gas → ambient (same convention as [gasKelvin]). */
internal fun kelvinAt(grams: LongArray, gasJoules: LongArray?, tile: Int): Int {
    if (gasJoules == null) return Temperature.AMBIENT_KELVIN
    val capacity = gasCapacityAt(grams, tile)
    return if (capacity <= 0L) Temperature.AMBIENT_KELVIN else (gasJoules[tile] / capacity).toInt()
}
