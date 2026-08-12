package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Resource
import org.emerge.demo.outofspace.logistics.Capacity

/**
 * A buffer you can see the level of. Holds one form, releases it out the front while its RUN
 * activation is positive — so a storage wired to a sensor is a valve, and a storage wired to nothing
 * is a dead end that fills up.
 *
 * **Storage is also the vessel's inventory.** The global [Stockpile] construction draws on is the sum
 * of every storage aboard, computed fresh each tick — there is no separate act of "banking". That
 * keeps material in one place instead of two: what you can build with is exactly what you can walk
 * up to and point at, and blowing a hole beside a full warehouse costs you the contents.
 */
data class Storage(
    override val facing: Direction,
    val contents: Resource? = null,
    override val wiring: Wiring = Wiring.RUNNING,
    override val joules: Long = ambientJoules(MachineKind.Storage),
) : Directed {
    override val kind: MachineKind get() = MachineKind.Storage
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)
    override fun withJoules(joules: Long): Machine = copy(joules = joules)

    companion object {
        /**
         * How much a warehouse holds: **twenty belt-loads**.
         *
         * **Derivation**: `20 × PACKET_GRAMS`, which is a shade over three full tiles of solid steel
         * and so reads as a room packed with material rather than a crate. Stated in packets because
         * that is the unit the player actually experiences it filling in, and because it keeps
         * storage a whole multiple of what a belt can deliver — see [MACHINE_BUFFER_CAP] for why the
         * remainder matters.
         */
        val CAP = 20L * Capacity.PACKET_GRAMS
    }
}
