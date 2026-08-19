package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.num.scaledRatio

/**
 * What crossed between room and pipe.
 *
 * [mass] and [energy] are signed room-to-pipe (positive = room lost mass/energy).
 */
class InterlayerStep(
    val mass: Long,
    val energy: Long,
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
    roomMass: MassArray,
    roomEnergy: EnergyArray?,
    pipeMass: MassArray,
    pipeEnergy: EnergyArray?,
    pipeVolumes: VolumeField,
): InterlayerStep {
    var movedMass = 0L
    var movedEnergy = 0L

    for (i in openings.indices) {
        val opening = openings[i]
        if (opening <= 0) continue
        val tile = TileIndex(i)

        val roomMoles = millimolesOf(roomMass, tile)
        val pipeMoles = millimolesOf(pipeMass, tile)
        if (roomMoles == 0L && pipeMoles == 0L) continue

        val roomCapacity = pressureCapacity(VolumeField.FULL, kelvinAt(roomMass, roomEnergy, tile))
        val pipeCapacity = pressureCapacity(pipeVolumes.at(tile), kelvinAt(pipeMass, pipeEnergy, tile))

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
            handOver(share, tile, tile, roomMass, roomEnergy, pipeMass, pipeEnergy)
        } else {
            handOver(share, tile, tile, pipeMass, pipeEnergy, roomMass, roomEnergy)
        }
        // Signed room-to-pipe, so a valve breathing in and out reads as the small net it is.
        val sign = if (fromRoom) 1L else -1L
        movedMass += sign * moved.mass
        movedEnergy += sign * moved.energy
    }

    return InterlayerStep(movedMass, movedEnergy)
}

/**
 * Fraction of a cell leaving, kept as a ratio. Multiplying then dividing keeps each quantity
 * (species mass, energy, momentum) at its own precision without rounding out trace species.
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
 * the cause. A tile ended up holding **negative energy**, which read back as a negative kelvin, which
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

internal class Moved(val mass: Long, val energy: Long)

/**
 * Moves [share] of one cell's gas species-by-species, with energy. Tiles separate for pump usage
 * (draws from adjacent tile), same tile for valve (exchanges at one place).
 */
internal fun handOver(
    share: Share,
    donorTile: TileIndex,
    acceptorTile: TileIndex,
    donorMass: MassArray,
    donorEnergy: EnergyArray?,
    acceptorMass: MassArray,
    acceptorEnergy: EnergyArray?,
): Moved {
    var mass = 0L
    donorMass.forEachFluid(donorTile) { f, held ->
        val take = share.of(held)
        if (take != 0L) {
            donorMass[donorTile, f] = held - take
            acceptorMass.add(acceptorTile, f, take)
            mass += take
        }
    }

    // Energy as a fraction of donor (exact), not mass × temperature (accumulates rounding error).
    var energy = 0L
    if (donorEnergy != null && acceptorEnergy != null) {
        energy = share.of(donorEnergy[donorTile])
        donorEnergy[donorTile] -= energy
        acceptorEnergy[acceptorTile] += energy
    }
    return Moved(mass, energy)
}

/**
 * Moles per unit of pressure (volume/temperature). Scaled for comfortable integers.
 */
internal fun pressureCapacity(volume: Int, kelvin: Int): Long =
    volume.toLong() * Temperature.AMBIENT_KELVIN / maxOf(kelvin, 1)

/** Cell gas temperature. No gas → ambient (same convention as [gasKelvin]). */
internal fun kelvinAt(mass: MassArray, gasEnergy: EnergyArray?, tile: TileIndex): Int {
    if (gasEnergy == null) return Temperature.AMBIENT_KELVIN
    val capacity = heatCapacityAt(mass, tile)
    return if (capacity <= 0L) Temperature.AMBIENT_KELVIN else (gasEnergy[tile] / capacity).toInt()
}
