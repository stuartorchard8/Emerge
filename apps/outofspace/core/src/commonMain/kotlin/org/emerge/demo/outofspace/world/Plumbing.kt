package org.emerge.demo.outofspace.world

/**
 * Pump: draws gas from faced tile, delivers to pipe beneath.
 * Machine (not valve): has rate, direction, wiring.
 * Moves gas against pressure gradient (stalls at STALL_RATIO× intake pressure).
 * Does not model compression heating (gas carries its own energy).
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
