package org.emerge.demo.outofspace.world

/**
 * Takes gas out of the room it faces and forces it into the pipe beneath it.
 *
 * ### Why this is a machine when a valve is not
 *
 * A valve is a hole, and a hole has no rate, no direction and nothing to switch off — which is why it
 * ended up a property of a [Segment]. A pump is the opposite on all three counts. It has a rate, it
 * has a direction (a pump that ran either way at random would be a valve with extra steps), and it
 * does **work**, so it is the kind of thing a player should be able to wire to a sensor and throttle.
 * Wiring lives on machines. So this is one.
 *
 * ### Where it gets the gas, and why not from its own tile
 *
 * From the tile it **faces**, not the one it stands on. That is forced rather than chosen: placing a
 * building displaces the air out of every tile of its footprint, so a machine's own tile is by
 * definition empty of room air. A pump drawing from underneath itself would draw from a vacuum. It
 * delivers into the pipe on its own tile, which works because conduits run *underneath* buildings —
 * the same rule that lets track be threaded under a smelter.
 *
 * Facing therefore means intake side, and rotating a pump changes which room it empties. Both ends
 * are ordinary: no pipe underneath and it has nowhere to push, no air in front and it has nothing to
 * draw, and in either case it simply does nothing rather than failing.
 *
 * ### What makes it a pump rather than a fast valve
 *
 * It moves gas **against** the pressure gradient. [exchangeLayers] stops when the two sides agree;
 * this keeps going until the pipe is at [STALL_RATIO] times the pressure it is drawing from, which is
 * the one number that says how strong the machine is. Without a stall it would compress without
 * limit, which is not a pump so much as a fusion device.
 *
 * The reverse machine — pipe back out to the room — is deliberately not here. A valve already does
 * that whenever the pipe is the higher pressure, so the missing piece is a pump that *insists*, and
 * it is worth building when something wants suction rather than now.
 *
 * ### What it does not model
 *
 * Compression heats a gas, and this does not: what crosses carries the energy it already had, so the
 * pipe ends up holding cold high-pressure gas. Named rather than hidden because it is exactly the
 * term that makes a compressor interesting later — a chemical rocket's whole character is in the
 * work done on the working fluid — and it should be added deliberately, with the joules coming from
 * the pump's power draw so the energy ledger still closes.
 */
data class Pump(
    override val facing: Direction,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Pump),
) : Machine, Directed {
    override val kind: MachineKind get() = MachineKind.Pump
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)

    companion object {
        /**
         * Millimoles moved per tick at full activation.
         *
         * A tile of ordinary air is about 34,000 of these and a pipe cell an eighth that, so this
         * fills one length of pipe in a few dozen ticks and drains the room it is facing over a few
         * thousand. Fast enough to watch, slow enough that a network fills visibly from its pump
         * rather than appearing full.
         */
        const val MILLIMOLES_PER_TICK: Long = 200L

        /**
         * How far above its intake a pump can push before it stalls.
         *
         * The machine's strength, and the only thing that distinguishes a good pump from a poor one.
         * Four atmospheres against a roomful is a compressor a player can build a gas system around
         * without it being able to fill a pipe to the point of absurdity.
         */
        const val STALL_RATIO: Long = 4L
    }
}
