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

        // The room's share at a common pressure, and how far it is from it. Positive means the room
        // is holding more than its share and gas moves into the pipe.
        //
        // ### Why there is no temperature here any more
        //
        // Each side used to be weighed at **its own** temperature, which meant asking an *empty*
        // side how hot it was — and the answer was [Temperature.AMBIENT_KELVIN], a number nobody
        // measured, describing gas that is not there. On a ship running at 16 K that is wrong by a
        // factor of eighteen, and it was setting how eagerly gas moved into empty pipework.
        //
        // The two cells are about to mix, so the honest temperature is the *mixture's*:
        // `(E_room + E_pipe) / (C_room + C_pipe)`, which needs no assumption about a vacuum because
        // an empty side contributes zero to both terms and the occupied side simply gets the whole
        // say. It is also well defined exactly when this loop reaches it — the moles check above is
        // already the guarantee that at least one side has gas.
        //
        // ⚠️ **And then it cancels.** Put one common `T` into `volume × 293 / T` on both sides and
        // it divides out of the surplus completely, leaving pure volume share. So the mixture
        // temperature is the *reasoning*, not the code: the assumption is gone rather than replaced,
        // `pressureCapacity` and this file's `kelvinAt` go with it, and two floored divisions leave
        // the hot loop.
        //
        // ⛔ **What this gives up, knowingly** (Stu's call): real gas at equal pressure puts more
        // moles on the colder side, `n/V ∝ 1/T`, and a common temperature drops that — a cryogenic
        // pipe run no longer draws gas the way it physically would. The asymmetry was not being
        // modelled *correctly* before either, since it was computed off a fabricated 293 K for any
        // empty cell. Bringing it back means deriving it from something measured, not restoring
        // this.
        val roomVolume = VolumeField.FULL.toLong()
        val pipeVolume = pipeVolumes.at(tile).toLong()
        val surplus = (roomMoles * pipeVolume - pipeMoles * roomVolume) / (roomVolume + pipeVolume)
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

/*
 * ⛔ **`pressureCapacity` and this file's `kelvinAt` are gone, and their absence is the point.**
 *
 * They were the last two places in the game that asked an *empty* cell for a temperature and got a
 * number back — `AMBIENT_KELVIN`, standing in for gas that is not there. Nothing consumes such an
 * answer now: the room/pipe exchange and the pump both weigh the two cells at the temperature of the
 * mixture they are about to become, which cancels, and every other `kelvinAt` in the codebase is
 * either guarded by a capacity check before it is read (`SolidHeat`) or feeds a readout.
 *
 * ⚠️ So do not reintroduce a helper that answers "how hot is this vacuum". If a future model wants
 * the cold-side-holds-more-moles asymmetry back, it has to come from a temperature something
 * actually measured — see the note in [exchangeLayers].
 */
