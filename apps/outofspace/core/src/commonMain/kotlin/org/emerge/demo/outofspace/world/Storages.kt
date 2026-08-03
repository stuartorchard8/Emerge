package org.emerge.demo.outofspace.world

import org.emerge.demo.outofspace.chem.Resource

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
) : Directed {
    override val kind: MachineKind get() = MachineKind.Storage
    override fun rotated(): Machine = copy(facing = facing.clockwise)
    override fun withWiring(wiring: Wiring): Machine = copy(wiring = wiring)

    companion object {
        const val CAP = 20_000L
    }
}
