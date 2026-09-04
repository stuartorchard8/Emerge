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

/*
 * ⛔ **`exchangeLayers` stood here — a room and a pipe on one tile, relaxing to a common pressure.**
 * The pipe network is deleted (`PLAN_fluid_thrusters.md` §9): a diffusive network equalises and
 * cannot deliver, so fluids ride rails as packets instead. [Share] and [handOver] survive it, since
 * moving a fraction of one cell's gas is still what a pump does.
 */

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
